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
package com.ash.drishti.examples.dayfolder;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.DateCoverage;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.HitIndex;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.PluginNotConfigured;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.UnreadableData;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A dated source over a folder: {@code <root>/<kind>/<yyyy-MM-dd>.jsonl}, one JSON document per line.
 *
 * <p>It exists to teach the plugin contract, so it is small and honest rather than fast: the whole folder is read into
 * memory at start and again every {@code rescan-seconds}, and the new state replaces the old in one atomic swap.
 * Settings: {@code root} (required), {@code source-name}, {@code id-field} (default {@code id}) and
 * {@code id-field.<kind>}, {@code mode.<kind>} ({@code snapshot}, the default, or {@code effective}),
 * {@code link.<kind>.<target-kind>} (the field of a document that names an entity of the target kind, for reverse
 * lookups) and {@code rescan-seconds} (default 30, 0 turns the rescan off).
 */
public final class DayFolderSourcePlugin implements SourcePlugin {

    /** What one scan of the folder found: kind, then day, then id, then document. Immutable once built. */
    private record Snapshot(Map<String, TreeMap<LocalDate, Map<String, DataNode>>> kinds, long generation, Instant loadedAt) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), 0, Instant.EPOCH);

        int documents() {
            return kinds.values().stream().flatMap(d -> d.values().stream()).mapToInt(Map::size).sum();
        }
    }

    /** One document as seen on a date: the day whose file it came from, and its content. */
    private record Held(LocalDate day, DataNode doc) {}

    private final AtomicLong generations = new AtomicLong();
    private final HitIndex hits = new HitIndex();
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile String problem; // why the last rescan failed, or null
    private volatile SourceContext ctx;
    private volatile Path root;
    private volatile String name = "dayfolder";
    private volatile ScheduledFuture<?> rescan;

    @Override
    public PluginManifest manifest() {
        // Before start() the plugin cannot know its kinds; an empty set means "any kind", which is harmless then.
        Set<String> kinds = snapshot.kinds().keySet();
        return new PluginManifest("dayfolder", "1.0", kinds, new SourceCapabilities(false, true, true, true));
    }

    @Override
    public void start(SourceContext context) throws Exception {
        String r = context.setting("root", "");
        if (r.isEmpty()) {
            throw new PluginNotConfigured("dayfolder: no root folder set"); // installed but idle, not a failure
        }
        this.ctx = context;
        this.root = Path.of(r);
        this.name = context.setting("source-name", "dayfolder");
        reload(); // a folder that cannot be read at start fails the start: better loud than silently empty
        int every = Integer.parseInt(context.setting("rescan-seconds", "30"));
        if (every > 0) {
            rescan = context.scheduler().scheduleWithFixedDelay(this::rescanQuietly, every, every, TimeUnit.SECONDS);
        }
    }

    private void rescanQuietly() {
        try {
            reload();
        } catch (Exception e) {
            problem = e.getMessage(); // keep serving the previous snapshot; health and listingProblem say why
        }
    }

    private void reload() throws IOException {
        Map<String, TreeMap<LocalDate, Map<String, DataNode>>> kinds = new TreeMap<>();
        List<EntityHit> found = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            throw new IOException("no folder " + root.getFileName());
        }
        List<Path> kindDirs;
        try (var s = Files.list(root)) {
            kindDirs = s.filter(Files::isDirectory).sorted().toList();
        }
        for (Path kindDir : kindDirs) {
            String kind = kindDir.getFileName().toString();
            TreeMap<LocalDate, Map<String, DataNode>> days = new TreeMap<>();
            List<Path> dayFiles;
            try (var s = Files.list(kindDir)) {
                dayFiles = s.filter(f -> f.toString().endsWith(".jsonl")).sorted().toList();
            }
            for (Path file : dayFiles) {
                LocalDate day = dayOf(file);
                if (day != null) {
                    days.put(day, readDay(kind, file, found));
                }
            }
            kinds.put(kind, days);
        }
        snapshot = new Snapshot(kinds, generations.incrementAndGet(), Instant.now());
        hits.replaceAll(found);
        problem = null;
    }

    private static LocalDate dayOf(Path file) {
        String base = file.getFileName().toString().replace(".jsonl", "");
        try {
            return LocalDate.parse(base);
        } catch (DateTimeParseException e) {
            return null; // not one of ours: ignore it
        }
    }

    private Map<String, DataNode> readDay(String kind, Path file, List<EntityHit> found) throws IOException {
        Map<String, DataNode> byId = new TreeMap<>();
        String idField = ctx.setting("id-field." + kind, ctx.setting("id-field", "id"));
        int lineNo = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNo++;
            if (line.isBlank()) {
                continue;
            }
            try {
                DataNode doc = ctx.parseJson(new ByteArrayInputStream(line.getBytes(StandardCharsets.UTF_8)));
                String id = doc.get(idField).asText();
                if (id.isEmpty()) {
                    throw new IOException("no " + idField);
                }
                byId.put(id, doc);
                found.add(new EntityHit(EntityRef.of(kind, id), id, kind));
            } catch (IOException e) {
                // the data is there but cannot be read: say where, with no content that could be a secret
                throw new UnreadableData(kind + "/" + file.getFileName() + " line " + lineNo + " is not a document", e);
            }
        }
        return byId;
    }

    /** The documents of a kind as of a date: the newest day on or before it (snapshot), or all days up to it merged (effective). */
    private Map<String, Held> view(String kind, LocalDate date) {
        TreeMap<LocalDate, Map<String, DataNode>> days = snapshot.kinds().get(kind);
        Map<String, Held> out = new TreeMap<>();
        if (days == null || days.isEmpty()) {
            return out;
        }
        LocalDate upTo = date == null ? days.lastKey() : date;
        if (!"effective".equals(ctx.setting("mode." + kind, "snapshot"))) {
            Map.Entry<LocalDate, Map<String, DataNode>> day = days.floorEntry(upTo);
            if (day != null) {
                day.getValue().forEach((id, doc) -> out.put(id, new Held(day.getKey(), doc)));
            }
            return out;
        }
        days.headMap(upTo, true).forEach((day, docs) -> docs.forEach((id, doc) -> out.put(id, new Held(day, doc))));
        return out;
    }

    private EntityDocument document(EntityRef ref, Held held) {
        Snapshot s = snapshot;
        return new EntityDocument(ref, held.doc(), new Provenance(name, s.generation(), s.loadedAt(), false, held.day()));
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) {
        return fetch(ref, AsOf.LATEST);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) {
        // Optional.empty() only because we know we do not hold it; a store problem would have thrown.
        return Optional.ofNullable(view(ref.kind(), asOf.businessDate()).get(ref.id())).map(h -> document(ref, h));
    }

    @Override
    public DateCoverage coverage(String kind, AsOf asOf) {
        TreeMap<LocalDate, ?> days = snapshot.kinds().get(kind);
        if (days == null || asOf.businessDate() == null) {
            return DateCoverage.UNKNOWN; // never guess: UNKNOWN lets the next source answer
        }
        return days.floorKey(asOf.businessDate()) != null ? DateCoverage.HELD : DateCoverage.NOT_HELD;
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind) {
        return reverse(target, kind, AsOf.LATEST);
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind, AsOf asOf) {
        String field = ctx.setting("link." + kind + "." + target.kind(), "");
        List<EntityRef> out = new ArrayList<>();
        if (!field.isEmpty()) {
            view(kind, asOf.businessDate()).forEach((id, held) -> {
                if (target.id().equals(held.doc().get(field).asText())) {
                    out.add(EntityRef.of(kind, id));
                }
            });
        }
        return out;
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return hits.search(kind, text, limit); // from memory, no I/O: it runs on every keystroke
    }

    @Override
    public Optional<String> listingProblem(String kind) {
        return Optional.ofNullable(problem == null ? null : "the folder could not be rescanned: " + problem);
    }

    @Override
    public Map<String, Object> cacheStats() {
        Snapshot s = snapshot;
        return Map.of("kinds", s.kinds().size(), "documents", s.documents(), "generation", s.generation());
    }

    @Override
    public void purgeCaches() {
        rescanQuietly(); // everything is cached: dropping it means reading the folder again
    }

    @Override
    public Instant lastUpdate() {
        return snapshot.loadedAt() == Instant.EPOCH ? null : snapshot.loadedAt();
    }

    @Override
    public String health() {
        Snapshot s = snapshot;
        String detail = s.kinds().size() + " kinds, " + s.documents() + " documents";
        return problem == null ? "UP (" + detail + ")" : "DEGRADED: " + problem + " (serving the previous scan: " + detail + ")";
    }

    @Override
    public void close() {
        ScheduledFuture<?> f = rescan;
        if (f != null) {
            f.cancel(false);
        }
    }

    /** The days a kind has files for, oldest first: handy in tests. */
    public Set<LocalDate> days(String kind) {
        TreeMap<LocalDate, ?> d = snapshot.kinds().get(kind);
        return d == null ? Set.of() : new TreeSet<>(d.keySet());
    }
}
