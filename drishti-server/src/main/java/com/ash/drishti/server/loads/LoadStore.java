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

    private final com.ash.drishti.identity.content.ContentStore content;

    public LoadStore(Path dir, int keep) {
        this(dir, keep, null);
    }

    /**
     * With {@code content} ({@code drishti.loads.store=content}, the default; DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 9) each pack's history is the document
     * {@code loads/<pack>.json} there, read every time and changed by compare-and-set, so every node of a cluster sees every announcement
     * and a late flag goes out once. A pack's file {@code <dir>/<pack>.json} is imported the first time the pack is read (its document's
     * existence is the marker) and left in place.
     */
    public LoadStore(Path dir, int keep, com.ash.drishti.identity.content.ContentStore content) {
        this.dir = dir.toAbsolutePath().normalize();
        this.keep = keep;
        this.content = content;
    }

    private String key(String pack) {
        file(pack);                                                         // checks the name
        return "loads/" + pack + ".json";
    }

    private Optional<com.ash.drishti.identity.content.ContentStore.Item> item(String pack) {
        Optional<com.ash.drishti.identity.content.ContentStore.Item> it = content.get(key(pack));
        if (it.isEmpty() && Files.isRegularFile(file(pack))) {
            try {
                content.put(key(pack), Files.readAllBytes(file(pack)), null);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + file(pack), e);
            } catch (com.ash.drishti.common.DrishtiException e) {
                if (e.errorCode() != com.ash.drishti.common.ErrorCode.CONTENT_CONFLICT) {
                    throw e;                                                // another node imported it first
                }
            }
            it = content.get(key(pack));
        }
        return it;
    }

    private Doc read(byte[] bytes) {
        try {
            return json.readValue(bytes, Doc.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs {@code change} on the pack's history and keeps the result: under the lock (files) or as a compare-and-set, retried (content). */
    private <R> R change(String pack, java.util.function.Function<Doc, R> change) {
        if (content == null) {
            lock.lock();
            try {
                R r = change.apply(doc(pack));
                save(pack);
                return r;
            } finally {
                lock.unlock();
            }
        }
        for (int attempt = 0; ; attempt++) {
            Optional<com.ash.drishti.identity.content.ContentStore.Item> it = item(pack);
            Doc d = it.map(i -> read(i.bytes())).orElseGet(() -> new Doc(null, null));
            R r = change.apply(d);
            try {
                content.put(key(pack), json.writeValueAsBytes(d), it.map(com.ash.drishti.identity.content.ContentStore.Item::version).orElse(null));
                return r;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (com.ash.drishti.common.DrishtiException e) {
                if (e.errorCode() != com.ash.drishti.common.ErrorCode.CONTENT_CONFLICT || attempt >= 20) {
                    throw e;
                }
            }
        }
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
        if (content != null) {
            return item(pack).map(i -> read(i.bytes())).orElseGet(() -> new Doc(null, null));
        }
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
            Path tmp = com.ash.drishti.common.TempFiles.createFile(dir, pack + "-", ".tmp");
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
        String suffix = content == null ? "" : "-" + Long.toString(java.util.concurrent.ThreadLocalRandom.current().nextLong(1L << 40), 36);
        return change(pack, d -> {
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
            String id = Long.toString(now.toEpochMilli(), 36) + "-" + Long.toString(++sequence, 36) + suffix;
            LoadRecord r = new LoadRecord(id, pack, kind, date, status, rows, rejected, source, batch, note, now, by, same.size() + 1, reload, null,
                    null, 0, 0, List.of(), 0, false);
            d.loads().add(r);
            while (d.loads().size() > keep) {
                d.loads().remove(0);
            }
            return new Begun(r, false);
        });
    }

    /** Replaces the record with the same id (its steps are done). */
    public void update(LoadRecord r) {
        change(r.pack(), d -> {
            List<LoadRecord> ls = d.loads();
            for (int i = 0; i < ls.size(); i++) {
                if (ls.get(i).id().equals(r.id())) {
                    ls.set(i, r);
                }
            }
            return null;
        });
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
        if (doc(pack).late().containsKey(lateKey(kind, date))) {
            return false;
        }
        return change(pack, d -> d.late().putIfAbsent(lateKey(kind, date), now) == null);
    }

    /** Clears the flag; true when there was one. */
    public boolean clearLate(String pack, String kind, LocalDate date) {
        if (!doc(pack).late().containsKey(lateKey(kind, date))) {
            return false;
        }
        return change(pack, d -> d.late().remove(lateKey(kind, date)) != null);
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
