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
package com.ash.drishti.server.exposure;

import com.ash.drishti.compute.ccr.ExposureBook;
import com.ash.drishti.compute.ccr.ExposureHotFile;
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
 * The exposure engine's hot files (EXP.md section 7.2): one per loaded business date under {@code <hot-folder>/<date>/}, named by the
 * digest of the inputs it was built from ({@code run-<16 hex>.xhot}), as VaR's (VAR.md section 7.3). A date is open (mapped) while it is
 * among the hot dates; evicted, it is unmapped after a grace and its file stays, so the date opens again without the lake. At most
 * {@code disk-dates} files are kept, the least recently used beyond deleted unless the retention keeps them (a month-end within
 * {@code retention.set-matrices-month-end-years}, any date within {@code retention.set-matrices-years}); a file built from inputs that
 * have since changed is deleted once unused. At shutdown every date is unmapped and, unless {@code keep-on-shutdown}, the files deleted;
 * at start the files a stopped server left are deleted too (or, kept, reused when their digest still matches).
 */
final class ExposureTier implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ExposureTier.class);
    static final String PREFIX = "run-";

    private final ExposureProperties props;
    private final Path root;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Path, List<ExposureBook>> open = new LinkedHashMap<>();
    private final Map<Path, Long> used = new LinkedHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong built = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong reopened = new java.util.concurrent.atomic.AtomicLong();
    private final ScheduledExecutorService later = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "drishti-exposure-tier");
        t.setDaemon(true);
        return t;
    });

    ExposureTier(ExposureProperties props) {
        this.props = props;
        this.root = Path.of(props.hotFolder()).toAbsolutePath().normalize();
    }

    Path root() {
        return root;
    }

    /** At start: the files a stopped server left are deleted unless they are kept. */
    ExposureTier start() {
        if (!Files.isDirectory(root)) {
            return this;
        }
        try (Stream<Path> s = Files.walk(root, 2)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String n = p.getFileName().toString();
                if (n.endsWith(".part") || n.endsWith(ExposureHotFile.SUFFIX) && !props.keepOnShutdown()) {
                    Files.deleteIfExists(p);
                } else if (n.endsWith(ExposureHotFile.SUFFIX)) {
                    used.put(p, Files.getLastModifiedTime(p).toMillis());
                }
            }
        } catch (IOException e) {
            LOG.warn("exposure hot files: cannot tidy {}: {}", root, e.toString());
        }
        removeEmptyFolders();
        return this;
    }

    /** The digest of what a date is built from: its inputs' data versions, the run, the collateral and IM models, the file format. */
    static String digest(LocalDate date, Map<String, String> versions, String runId, String collateral, String im) {
        String key = "format=" + ExposureHotFile.VERSION + "|date=" + date + "|run=" + runId + "|collateral=" + collateral + "|im=" + im + "|"
                + new java.util.TreeMap<>(versions);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    Path folder(LocalDate date) {
        return root.resolve(date.toString());
    }

    Path file(LocalDate date, String digest) {
        return folder(date).resolve(PREFIX + digest.substring(0, 16) + ExposureHotFile.SUFFIX);
    }

    /** The hot file built from exactly these inputs, if one is on disk and whole. */
    Optional<Path> existing(LocalDate date, String digest) {
        Path f = file(date, digest);
        ExposureHotFile.Header h = Files.isRegularFile(f) ? ExposureHotFile.header(f) : null;
        return h != null && h.digest().equals(digest) && h.businessDate().equals(date) ? Optional.of(f) : Optional.empty();
    }

    /** Where a new load of the date writes its file. */
    Path target(LocalDate date, String digest) {
        built.incrementAndGet();
        try {
            Files.createDirectories(folder(date));
            return file(date, digest);
        } catch (IOException e) {
            throw new UncheckedIOException("the exposure hot folder " + folder(date) + " cannot be written: " + e.getMessage(), e);
        }
    }

    ExposureBook open(Path file) {
        try {
            ExposureBook b = ExposureBook.open(file);
            reopened.incrementAndGet();
            return b;
        } catch (IOException e) {
            throw new UncheckedIOException("the exposure hot file " + file + " cannot be opened: " + e.getMessage(), e);
        }
    }

    /** A mapped date is open: remembered; the date's other files deleted once unused; the disk trimmed (retention kept). */
    void opened(ExposureBook book) {
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
                s.filter(p -> p.getFileName().toString().endsWith(ExposureHotFile.SUFFIX) && !p.equals(f) && !open.containsKey(p)).forEach(stale::add);
            } catch (IOException e) {
                // nothing to compare against
            }
            List<Path> byAge = new ArrayList<>(used.keySet());
            byAge.sort((a, b) -> Long.compare(used.get(a), used.get(b)));
            int over = byAge.size() - props.diskDates();
            LocalDate newest = byAge.stream().map(ExposureTier::dateOf).filter(java.util.Objects::nonNull).max(LocalDate::compareTo).orElse(null);
            for (Path p : byAge) {
                if (over <= 0) {
                    break;
                }
                LocalDate d = dateOf(p);
                if (!open.containsKey(p) && !(d != null && newest != null && ExposureRetention.keepsSetMatrices(props.retention(), d, newest))) {
                    stale.add(p);
                    over--;
                }
            }
            stale.forEach(used::remove);
            stale.forEach(this::delete);
        } finally {
            lock.unlock();
        }
    }

    private static LocalDate dateOf(Path file) {
        try {
            return LocalDate.parse(file.getParent().getFileName().toString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A date leaves the open tier: unmapped after the grace, its file deleted when {@code delete}. */
    void retire(ExposureBook book, boolean delete) {
        if (book.hot() == null) {
            return;
        }
        Runnable close = () -> {
            Path f = book.hot().file();
            lock.lock();
            try {
                List<ExposureBook> l = open.get(f);
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
        if (props.closeGraceSeconds() == 0) {
            close.run();
        } else {
            later.schedule(close, props.closeGraceSeconds(), TimeUnit.SECONDS);
        }
    }

    Map<String, Object> describe() {
        lock.lock();
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mode", props.tier());
            m.put("folder", root.toString());
            m.put("diskDates", props.diskDates());
            m.put("keepOnShutdown", props.keepOnShutdown());
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
            return m;
        } finally {
            lock.unlock();
        }
    }

    private void delete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            LOG.debug("exposure hot files: {} not deleted now ({}); removed at the next start", p, e.toString());
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

    @Override
    public void close() {
        later.shutdownNow();
        lock.lock();
        try {
            for (List<ExposureBook> l : open.values()) {
                for (ExposureBook b : l) {
                    if (props.keepOnShutdown()) {
                        b.close();
                    } else {
                        b.closeAndDelete();
                    }
                }
            }
            open.clear();
            if (!props.keepOnShutdown()) {
                used.keySet().forEach(this::delete);
                used.clear();
            }
        } finally {
            lock.unlock();
        }
        removeEmptyFolders();
    }
}
