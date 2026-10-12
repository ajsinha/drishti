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
package com.ash.drishti.server.admin;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The archive of uninstalled artifacts ({@code drishti.admin.uninstalled-dir}): one folder per uninstall, named
 * {@code <timestamp>-<kind>-<name>}, holding {@code payload/} (the folder or file exactly as it was) and
 * {@code uninstalled.json} (who, when, why, and where it came from, so a restore knows where to put it back). Nothing here deletes
 * an artifact: uninstalling <em>moves</em> it in, restoring moves it out, and only {@link #purge} and {@link #prune} remove an
 * archive, after the retention or on an administrator's typed confirmation. It knows nothing of packs, connectors or BI; the caller
 * says where a payload came from and where it goes back.
 */
public final class ArtifactArchive {

    static final String META = "uninstalled.json";
    static final String PAYLOAD = "payload";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,150}");
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * One archive.
     *
     * @param id the folder name (also the id the API uses)
     * @param kind pack, connector or bi
     * @param subtype for bi: dataset, report, board, notebook or python; otherwise empty
     * @param name the artifact's name
     * @param area where it lived: packs, installed, connectors or bi
     * @param rel its path under that area's folder (a folder name, a file name, or {@code datasets/x.dataset.yaml})
     * @param at when it was uninstalled
     * @param by who did it
     * @param summary the impact summary at the time
     * @param bytes the size of the payload
     */
    public record Entry(String id, String kind, String subtype, String name, String area, String rel, Instant at, String by, String summary, long bytes) {}

    private final Path dir;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    public ArtifactArchive(Path dir, Clock clock) {
        this.dir = dir;
        this.clock = clock;
    }

    public Path dir() {
        return dir;
    }

    /** Moves {@code source} (a folder or a file) into a new archive and returns its entry. */
    public Entry archive(String kind, String subtype, String name, String area, String rel, Path source, String by, String summary) {
        lock.lock();
        try {
            Instant now = clock.instant();
            String base = STAMP.format(now) + "-" + kind + "-" + safe(name);
            String id = base;
            for (int i = 2; Files.exists(dir.resolve(id)); i++) {
                id = base + "-" + i;
            }
            Path folder = dir.resolve(id);
            Files.createDirectories(folder.resolve(PAYLOAD));
            Path target = folder.resolve(PAYLOAD).resolve(source.getFileName().toString());
            moveTree(source, target);
            Entry e = new Entry(id, kind, subtype == null ? "" : subtype, name, area, rel, now, by == null ? "" : by, summary == null ? "" : summary, size(target));
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("kind", e.kind());
            meta.put("subtype", e.subtype());
            meta.put("name", e.name());
            meta.put("area", e.area());
            meta.put("rel", e.rel());
            meta.put("at", e.at().toString());
            meta.put("by", e.by());
            meta.put("summary", e.summary());
            meta.put("bytes", e.bytes());
            Files.writeString(folder.resolve(META), JSON.writeValueAsString(meta));
            return e;
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot archive " + name + ": " + ex.getMessage(), ex);
        } finally {
            lock.unlock();
        }
    }

    /** Every archive, newest first; a folder whose record cannot be read is skipped. */
    public List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(Files::isDirectory).toList()) {
                read(f).ifPresent(out::add);
            }
        } catch (IOException e) {
            return out;
        }
        out.sort(Comparator.comparing(Entry::at).reversed().thenComparing(Entry::id));
        return out;
    }

    public Optional<Entry> find(String id) {
        if (id == null || !SAFE.matcher(id).matches()) {
            return Optional.empty();
        }
        Path f = dir.resolve(id);
        return Files.isDirectory(f) ? read(f) : Optional.empty();
    }

    /** The archived file or folder (the one thing in {@code payload/}). */
    public Path payload(Entry e) {
        Path p = dir.resolve(e.id()).resolve(PAYLOAD);
        try (Stream<Path> s = Files.list(p)) {
            return s.findFirst().orElseThrow(() -> new DrishtiException(ErrorCode.ARTIFACT_NOT_FOUND, "the archive " + e.id() + " has no payload"));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Moves the payload to {@code target} (which must not exist) and removes the archive. */
    public void restoreTo(Entry e, Path target) {
        lock.lock();
        try {
            if (Files.exists(target)) {
                throw new DrishtiException(ErrorCode.ARTIFACT_CONFLICT,
                        e.kind() + " '" + e.name() + "' exists again; restore with replace to archive it and put this one back");
            }
            Path payload = payload(e);
            Files.createDirectories(target.getParent());
            moveTree(payload, target);
            deleteTree(dir.resolve(e.id()));
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot restore " + e.name() + ": " + ex.getMessage(), ex);
        } finally {
            lock.unlock();
        }
    }

    /** Removes one archive for good. */
    public boolean purge(String id) {
        lock.lock();
        try {
            if (find(id).isEmpty()) {
                return false;
            }
            deleteTree(dir.resolve(id));
            return true;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } finally {
            lock.unlock();
        }
    }

    /** Removes the archives older than {@code keep}; returns their ids. A zero or negative {@code keep} keeps everything. */
    public List<String> prune(Duration keep) {
        List<String> gone = new ArrayList<>();
        if (keep == null || keep.isZero() || keep.isNegative()) {
            return gone;
        }
        Instant cutoff = clock.instant().minus(keep);
        for (Entry e : list()) {
            if (e.at().isBefore(cutoff) && purge(e.id())) {
                gone.add(e.id());
            }
        }
        return gone;
    }

    private Optional<Entry> read(Path folder) {
        Path meta = folder.resolve(META);
        if (!Files.isRegularFile(meta)) {
            return Optional.empty();
        }
        try {
            var n = JSON.readTree(meta.toFile());
            return Optional.of(new Entry(folder.getFileName().toString(), n.path("kind").asText(), n.path("subtype").asText(), n.path("name").asText(),
                    n.path("area").asText(), n.path("rel").asText(), Instant.parse(n.path("at").asText()), n.path("by").asText(), n.path("summary").asText(),
                    n.path("bytes").asLong()));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String safe(String name) {
        String s = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    private static void moveTree(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomic) {
            copyTree(from, to);                                    // another file system: copy, then remove the original
            deleteTree(from);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
            return;
        }
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.copy(p, t, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(x);
            }
        }
    }

    private static long size(Path p) throws IOException {
        if (!Files.isDirectory(p)) {
            return Files.size(p);
        }
        try (Stream<Path> s = Files.walk(p)) {
            long total = 0;
            for (Path x : s.filter(Files::isRegularFile).toList()) {
                total += Files.size(x);
            }
            return total;
        }
    }
}
