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
package com.ash.drishti.server.loads;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * The history of data loads, one file per pack ({@code <dir>/<pack>.json}) beside the "late" flags that say which missing
 * loads were already announced. Everything is kept in memory and written through atomically; a lock (never held across a
 * source read) makes check-and-insert one step, so two announcements of the same batch never both run.
 */
public final class LoadStore {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /** What a pack's file holds. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Doc(List<LoadRecord> loads, Map<String, Instant> late) {
        public Doc {
            loads = loads == null ? new ArrayList<>() : new ArrayList<>(loads);
            late = late == null ? new LinkedHashMap<>() : new LinkedHashMap<>(late);
        }
    }

    private final Path dir;
    private final int keep;
    private final ObjectMapper json = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Map<String, Doc> packs = new java.util.concurrent.ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private long sequence;

    public LoadStore(Path dir, int keep) {
        this.dir = dir.toAbsolutePath().normalize();
        this.keep = keep;
    }

    public Path dir() {
        return dir;
    }

    private Path file(String pack) {
        if (!NAME.matcher(pack).matches()) {
            throw new IllegalArgumentException("not a pack name: " + pack);
        }
        return dir.resolve(pack + ".json");
    }

    private Doc doc(String pack) {
        return packs.computeIfAbsent(pack, p -> {
            Path f = file(p);
            try {
                return Files.isRegularFile(f) ? json.readValue(f.toFile(), Doc.class) : new Doc(null, null);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + f, e);
            }
        });
    }

    private void save(String pack) {
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "." + pack + "-", ".tmp");
            json.writeValue(tmp.toFile(), doc(pack));
            Files.move(tmp, file(pack), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What {@link #begin} decided. */
    public record Begun(LoadRecord record, boolean duplicate) {}

    /**
     * Records the announcement unless the same pack, kind, date and batch already announced the same outcome (then that record is
     * returned and {@code duplicate} is true: nothing runs twice). A different outcome for the same key is a new record with the
     * attempt counted; a {@code ready} load of a kind and date that already has one is marked a reload.
     */
    public Begun begin(String pack, String kind, LocalDate date, String status, Long rows, Long rejected, String source, String batch,
            String note, Instant now, String by) {
        lock.lock();
        try {
            Doc d = doc(pack);
            String key = LoadRecord.key(pack, kind, date, batch);
            List<LoadRecord> same = d.loads().stream().filter(r -> r.key().equals(key)).toList();
            if (!same.isEmpty()) {
                LoadRecord last = same.get(same.size() - 1);
                if (last.sameAs(status, rows, rejected)) {
                    return new Begun(last, true);
                }
            }
            boolean reload = LoadRecord.READY.equals(status) && d.loads().stream().anyMatch(r -> r.kind().equals(kind)
                    && r.businessDate().equals(date) && LoadRecord.READY.equals(r.status()));
            String id = Long.toString(now.toEpochMilli(), 36) + "-" + Long.toString(++sequence, 36);
            LoadRecord r = new LoadRecord(id, pack, kind, date, status, rows, rejected, source, batch, note, now, by, same.size() + 1, reload, null,
                    null, 0, 0, List.of(), 0, false);
            d.loads().add(r);
            while (d.loads().size() > keep) {
                d.loads().remove(0);
            }
            save(pack);
            return new Begun(r, false);
        } finally {
            lock.unlock();
        }
    }

    /** Replaces the record with the same id (its steps are done). */
    public void update(LoadRecord r) {
        lock.lock();
        try {
            List<LoadRecord> ls = doc(r.pack()).loads();
            for (int i = 0; i < ls.size(); i++) {
                if (ls.get(i).id().equals(r.id())) {
                    ls.set(i, r);
                }
            }
            save(r.pack());
        } finally {
            lock.unlock();
        }
    }

    /** Newest first; any filter may be null. */
    public List<LoadRecord> list(String pack, String kind, LocalDate date, String status, int limit) {
        lock.lock();
        try {
            List<LoadRecord> out = new ArrayList<>(doc(pack).loads());
            out.removeIf(r -> kind != null && !kind.equals(r.kind()) || date != null && !date.equals(r.businessDate())
                    || status != null && !status.equals(r.status()));
            out.sort(Comparator.comparing(LoadRecord::receivedAt).reversed());
            return out.size() > limit ? out.subList(0, limit) : out;
        } finally {
            lock.unlock();
        }
    }

    public Optional<LoadRecord> find(String pack, String id) {
        return list(pack, null, null, null, Integer.MAX_VALUE).stream().filter(r -> r.id().equals(id)).findFirst();
    }

    /** The newest announcement of the kind on the date (whatever its outcome), if any. */
    public Optional<LoadRecord> latest(String pack, String kind, LocalDate date) {
        return list(pack, kind, date, null, Integer.MAX_VALUE).stream().findFirst();
    }

    /** The newest {@code ready} announcement of the kind on the date, if any. */
    public Optional<LoadRecord> latestReady(String pack, String kind, LocalDate date) {
        return list(pack, kind, date, LoadRecord.READY, Integer.MAX_VALUE).stream().findFirst();
    }

    // -- late flags --------------------------------------------------------------------------------------------------------------

    public static String lateKey(String kind, LocalDate date) {
        return kind + "|" + date;
    }

    /** Flags the load as announced late; false when it already was (so the notice goes out once). */
    public boolean flagLate(String pack, String kind, LocalDate date, Instant now) {
        lock.lock();
        try {
            boolean first = doc(pack).late().putIfAbsent(lateKey(kind, date), now) == null;
            if (first) {
                save(pack);
            }
            return first;
        } finally {
            lock.unlock();
        }
    }

    /** Clears the flag; true when there was one. */
    public boolean clearLate(String pack, String kind, LocalDate date) {
        lock.lock();
        try {
            boolean had = doc(pack).late().remove(lateKey(kind, date)) != null;
            if (had) {
                save(pack);
            }
            return had;
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Instant> lateFlags(String pack) {
        lock.lock();
        try {
            return new LinkedHashMap<>(doc(pack).late());
        } finally {
            lock.unlock();
        }
    }
}
