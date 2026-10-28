/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.server.var;

import com.ash.drishti.common.TempFiles;
import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.compute.var.VarHotFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The tiered memory's files (VAR_GUIDE.md section 26): one hot file per loaded business date under {@code <data folder>/var/hot/<date>/},
 * named by the digest of the inputs it was built from ({@code inputs-<16 hex>.vhot}). A date is <em>open</em> (mapped, its aggregates
 * and columns on the heap) while it is among the hot dates; evicted, it is unmapped after a grace for the requests still reading it, and
 * its file stays on disk, so the date opens again by mapping, without the lake (a history date opened lazily). At most
 * {@code disk-dates} files are kept (the least recently used beyond are deleted); a file built from inputs that have since changed is
 * deleted once no request uses it. At shutdown every date is unmapped and, unless {@code keep-on-shutdown}, the files deleted; at start
 * the files a stopped server left are deleted too (or, kept, reused when their digest still matches). No hidden files: visible names
 * only, everything under the data folder; the load's spill and the file being written are temporary files of this folder
 * ({@link TempFiles}).
 */
final class VarTier implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(VarTier.class);
    static final String PREFIX = "inputs-";

    private final VarProperties.Tier settings;
    private final Path root;
    private final ReentrantLock lock = new ReentrantLock();
    /** Open dates' books by their file (one request may open the file another evicted: each book is closed on its own). */
    private final Map<Path, List<VarBook>> open = new LinkedHashMap<>();
    /** When each file was last used (milliseconds), for the disk tier's least-recently-used eviction. */
    private final Map<Path, Long> used = new LinkedHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong built = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong reopened = new java.util.concurrent.atomic.AtomicLong();
    private final ScheduledExecutorService later = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "drishti-var-tier");
        t.setDaemon(true);
        return t;
    });

    VarTier(VarProperties.Tier settings) {
        this.settings = settings;
        this.root = Path.of(settings.dir()).toAbsolutePath().normalize();
    }

    boolean mapped() {
        return settings.mapped();
    }

    Path root() {
        return root;
    }

    VarProperties.Tier settings() {
        return settings;
    }

    /** At start: the temporary files a stopped server left are deleted, and its hot files unless they are kept. */
    VarTier start() {
        if (!Files.isDirectory(root)) {
            return this;
        }
        int removed = 0;
        try (Stream<Path> s = Files.walk(root, 2)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String n = p.getFileName().toString();
                boolean temporary = n.endsWith(".part") || n.endsWith(".f64");
                if (temporary || n.endsWith(VarHotFile.SUFFIX) && !settings.keepOnShutdown()) {
                    Files.deleteIfExists(p);
                    removed++;
                } else if (n.endsWith(VarHotFile.SUFFIX)) {
                    used.put(p, Files.getLastModifiedTime(p).toMillis());
                }
            }
        } catch (IOException e) {
            LOG.warn("VaR hot files: cannot tidy {}: {}", root, e.toString());
        }
        removeEmptyFolders();
        if (removed > 0) {
            LOG.info("VaR hot files: removed {} files a stopped server left in {}", removed, root);
        }
        return this;
    }

    /**
     * The digest of the inputs a date is built from: the data versions of its vectors, hierarchy and scenario set, the position rules'
     * digest and the file format. Empty when the vectors' connector cannot say its version (such a date is always built from the lake).
     */
    static String digest(LocalDate date, String vectors, String hierarchy, String scenarioSet, String rules) {
        if (vectors == null || vectors.isBlank()) {
            return "";
        }
        String key = "format=" + VarHotFile.VERSION + "|date=" + date + "|vectors=" + vectors + "|hierarchy=" + hierarchy + "|scenario-set="
                + scenarioSet + "|rules=" + rules;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    Path folder(LocalDate date) {
        return folder(date, VarDate.USD);
    }

    /**
     * A date's folder: {@code <date>} for its USD book, {@code <date>-<CCY>} for a reporting currency's (VAR_GUIDE.md section 26: each its
     * own folder, so the date's other currency is never taken for an older build of it).
     */
    Path folder(LocalDate date, String currency) {
        return root.resolve(VarDate.USD.equals(currency) ? date.toString() : date + "-" + currency);
    }

    /**
     * A shard's folder (VAR_SHARDING.md): {@code <date>-shard-<i>} (USD) or {@code <date>-<CCY>-shard-<i>}, each its own, so a shard's file
     * is never taken for another's or the whole date's older build.
     */
    Path folder(LocalDate date, String currency, int shard) {
        return root.resolve(folder(date, currency).getFileName() + "-shard-" + shard);
    }

    /** A shard's hot file built from exactly these inputs, if one is on disk and whole. */
    Optional<Path> existing(LocalDate date, String currency, int shard, String digest) {
        if (digest.isEmpty()) {
            return Optional.empty();
        }
        Path f = folder(date, currency, shard).resolve(PREFIX + digest.substring(0, 16) + VarHotFile.SUFFIX);
        VarHotFile.Header h = Files.isRegularFile(f) ? VarHotFile.header(f) : null;
        return h != null && h.digest().equals(digest) && h.businessDate().equals(date) ? Optional.of(f) : Optional.empty();
    }

    /** Where a shard's book writes (its own folder). */
    VarBook.HotTarget target(LocalDate date, String currency, int shard, String digest) {
        built.incrementAndGet();
        Path dir = folder(date, currency, shard);
        try {
            Files.createDirectories(dir);
            Path spill = TempFiles.createFile(dir, "spill-", ".f64");
            Path part = TempFiles.createFile(dir, "build-", ".part");
            String d = digest.isEmpty() ? "unversioned-" + System.nanoTime() : digest;
            Path file = dir.resolve(PREFIX + (digest.isEmpty() ? d : digest.substring(0, 16)) + VarHotFile.SUFFIX);
            return new VarBook.HotTarget(spill, part, file, d);
        } catch (IOException e) {
            throw new UncheckedIOException("the VaR hot folder " + dir + " cannot be written: " + e.getMessage(), e);
        }
    }

    Path file(LocalDate date, String digest) {
        return file(date, VarDate.USD, digest);
    }

    Path file(LocalDate date, String currency, String digest) {
        return folder(date, currency).resolve(PREFIX + digest.substring(0, 16) + VarHotFile.SUFFIX);
    }

    /** The hot file built from exactly these inputs, if one is on disk and whole. */
    Optional<Path> existing(LocalDate date, String digest) {
        return existing(date, VarDate.USD, digest);
    }

    /** The hot file of a date's book in a currency built from exactly these inputs, if one is on disk and whole. */
    Optional<Path> existing(LocalDate date, String currency, String digest) {
        if (digest.isEmpty()) {
            return Optional.empty();
        }
        Path f = file(date, currency, digest);
        VarHotFile.Header h = Files.isRegularFile(f) ? VarHotFile.header(f) : null;
        return h != null && h.digest().equals(digest) && h.businessDate().equals(date) ? Optional.of(f) : Optional.empty();
    }

    /** Where a new load of the date writes: its spill and its part file made in the date's folder, and the final name. */
    VarBook.HotTarget target(LocalDate date, String digest) {
        return target(date, VarDate.USD, digest);
    }

    /** Where a date's book in a currency writes (its own folder). */
    VarBook.HotTarget target(LocalDate date, String currency, String digest) {
        built.incrementAndGet();
        try {
            Path dir = Files.createDirectories(folder(date, currency));
            String d = digest.isEmpty() ? "unversioned-" + System.nanoTime() : digest;
            Path spill = TempFiles.createFile(dir, "spill-", ".f64");
            Path part = TempFiles.createFile(dir, "build-", ".part");
            Path file = digest.isEmpty() ? dir.resolve(PREFIX + "unversioned-" + System.nanoTime() + VarHotFile.SUFFIX) : file(date, currency, digest);
            return new VarBook.HotTarget(spill, part, file, d);
        } catch (IOException e) {
            throw new UncheckedIOException("the VaR hot folder " + folder(date, currency) + " cannot be written: " + e.getMessage(), e);
        }
    }

    /** Opens a date's hot file (a history date: mapped, no lake read). */
    VarBook open(Path file, long t0) {
        try {
            VarBook b = VarHotFile.open(file, (System.nanoTime() - t0) / 1_000_000);
            reopened.incrementAndGet();
            return b;
        } catch (IOException e) {
            throw new UncheckedIOException("the VaR hot file " + file + " cannot be opened: " + e.getMessage(), e);
        }
    }

    /**
     * A mapped date is open: remembered, its use stamped; the date's other files (older inputs) are deleted once unused, and the disk
     * tier is trimmed to {@code disk-dates} files.
     */
    void opened(VarBook book) {
        if (book.hot() == null) {
            return;
        }
        Path f = book.hot().file();
        List<Path> stale = new ArrayList<>();
        lock.lock();
        try {
            open.computeIfAbsent(f, k -> new ArrayList<>()).add(book);
            used.put(f, System.currentTimeMillis());
            try (Stream<Path> s = Files.list(f.getParent())) {
                s.filter(p -> p.getFileName().toString().endsWith(VarHotFile.SUFFIX) && !p.equals(f) && !open.containsKey(p)).forEach(stale::add);
            } catch (IOException e) {
                // nothing to compare against
            }
            List<Path> byAge = new ArrayList<>(used.keySet());
            byAge.sort((a, b) -> Long.compare(used.get(a), used.get(b)));
            int over = byAge.size() - settings.diskDates();
            for (Path p : byAge) {
                if (over <= 0) {
                    break;
                }
                if (!open.containsKey(p)) {
                    stale.add(p);
                    over--;
                }
            }
            stale.forEach(used::remove);
            for (Path p : stale) {
                delete(p);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * A date leaves the open tier (evicted, reloaded, restated): it is unmapped after the grace (requests still reading it finish),
     * and its file deleted when {@code delete} or when nothing else keeps it.
     */
    void retire(VarBook book, boolean delete) {
        if (book.hot() == null) {
            return;
        }
        Runnable close = () -> {
            Path f = book.hot().file();
            lock.lock();
            try {
                List<VarBook> l = open.get(f);
                if (l != null) {
                    l.remove(book);
                    if (l.isEmpty()) {
                        open.remove(f);
                    }
                }
                if (delete && !open.containsKey(f)) {
                    used.remove(f);
                    book.closeAndDelete();
                } else {
                    book.close();
                }
            } finally {
                lock.unlock();
            }
        };
        if (settings.closeGraceSeconds() == 0) {
            close.run();
        } else {
            later.schedule(close, settings.closeGraceSeconds(), TimeUnit.SECONDS);
        }
    }

    /**
     * The retention of the currencies built ahead (VAR_GUIDE.md section 15.11): the hot files of {@code currencies} (not USD) of every date
     * not in {@code keep} are deleted, unless a request has them open (then they go when retired, or at the next pass). Returns the files
     * deleted.
     */
    int pruneCurrencies(java.util.Set<LocalDate> keep, java.util.Collection<String> currencies) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        List<Path> gone = new ArrayList<>();
        lock.lock();
        try (Stream<Path> s = Files.list(root)) {
            for (Path dir : s.filter(Files::isDirectory).toList()) {
                String n = dir.getFileName().toString();
                if (n.length() != 14 || n.charAt(10) != '-' || !currencies.contains(n.substring(11)) || VarDate.USD.equals(n.substring(11))) {
                    continue;
                }
                LocalDate d;
                try {
                    d = LocalDate.parse(n.substring(0, 10));
                } catch (java.time.format.DateTimeParseException e) {
                    continue;
                }
                if (keep.contains(d)) {
                    continue;
                }
                try (Stream<Path> in = Files.list(dir)) {
                    in.filter(p -> p.getFileName().toString().endsWith(VarHotFile.SUFFIX) && !open.containsKey(p)).forEach(gone::add);
                }
            }
            gone.forEach(used::remove);
        } catch (IOException e) {
            LOG.debug("VaR hot files: currency retention not applied now: {}", e.toString());
        } finally {
            lock.unlock();
        }
        gone.forEach(this::delete);
        if (!gone.isEmpty()) {
            removeEmptyFolders();
        }
        return gone.size();
    }

    /** The bytes and files a date's books in other currencies take on disk ({@code <date>-<CCY>} folders), for the status answer. */
    Map<String, Object> currencyFiles() {
        long bytes = 0;
        int files = 0;
        if (Files.isDirectory(root)) {
            try (Stream<Path> s = Files.walk(root, 2)) {
                for (Path p : s.filter(Files::isRegularFile).toList()) {
                    String dir = p.getParent().getFileName().toString();
                    if (dir.length() == 14 && dir.charAt(10) == '-' && p.getFileName().toString().endsWith(VarHotFile.SUFFIX)) {
                        bytes += Files.size(p);
                        files++;
                    }
                }
            } catch (IOException e) {
                // gone meanwhile
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("files", files);
        m.put("bytes", bytes);
        return m;
    }

    /** The files on disk and the dates open, for the status answer. */
    Map<String, Object> describe() {
        lock.lock();
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mode", settings.mode());
            m.put("diskDates", settings.diskDates());
            m.put("keepOnShutdown", settings.keepOnShutdown());
            m.put("closeGraceSeconds", settings.closeGraceSeconds());
            m.put("mapping", com.ash.drishti.compute.vector.MappedMatrix.mappingMethod());
            m.put("built", built.get());
            m.put("reopened", reopened.get());
            m.put("files", used.size());
            m.put("open", open.size());
            long bytes = 0;
            for (Path p : used.keySet()) {
                try {
                    bytes += Files.size(p);
                } catch (IOException e) {
                    // gone meanwhile
                }
            }
            m.put("diskBytes", bytes);
            m.put("currencies", currencyFiles());
            return m;
        } finally {
            lock.unlock();
        }
    }

    private void delete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            LOG.debug("VaR hot files: {} not deleted now ({}); removed at the next start", p, e.toString());
        }
    }

    private void removeEmptyFolders() {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> s = Files.list(root)) {
            for (Path d : s.filter(Files::isDirectory).toList()) {
                try (Stream<Path> in = Files.list(d)) {
                    if (in.findAny().isEmpty()) {
                        Files.deleteIfExists(d);
                    }
                }
            }
        } catch (IOException e) {
            // left for the next start
        }
    }

    /** At shutdown: every open date unmapped now; the files deleted unless they are kept. */
    @Override
    public void close() {
        later.shutdownNow();
        lock.lock();
        try {
            for (List<VarBook> l : open.values()) {
                for (VarBook b : l) {
                    if (settings.keepOnShutdown()) {
                        b.close();
                    } else {
                        b.closeAndDelete();
                    }
                }
            }
            open.clear();
            if (!settings.keepOnShutdown()) {
                used.keySet().forEach(this::delete);
                used.clear();
            }
        } finally {
            lock.unlock();
        }
        removeEmptyFolders();
    }
}
