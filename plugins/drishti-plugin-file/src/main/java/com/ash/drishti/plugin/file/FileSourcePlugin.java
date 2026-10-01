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
package com.ash.drishti.plugin.file;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.HitIndex;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Serves JSON-lines files, one per kind per business day ({@code <root>/<yyyy-MM-dd>/<kind>.jsonl}, or undated
 * {@code <root>/<kind>.jsonl}), each line an entity (see {@link JsonlDay}); a day's file is indexed once (ids with
 * their byte offsets, and the kind's promoted fields as columns), so a million lines a day are served by a positioned
 * read per entity and searches, derived kinds, impact and reverse lookups from the columns. See
 * {@code docs/connectors/FILE_CONNECTOR.md}.
 *
 * <p>Also serves feed files from a directory laid out as {@code <root>/<kind>/<id>.json} or {@code .csv}, and, for dated
 * data, {@code <root>/<yyyy-MM-dd>/<kind>/<id>.json}. A read for a business date takes the file from the latest
 * dated folder on or before that date (within {@code lookback-days}), then the undated folder. The generation is
 * the file's modification time in milliseconds, so a rewritten file is newer data.
 *
 * <p>Settings: {@code root} (required; with {@code domain}, the folder {@code <root>/<domain>}), {@code source-name}
 * (default {@code file}), {@code rescan-seconds} (default 30) for the search index and the list of dated folders,
 * {@code lookback-days} (default 10), {@code mode.<kind>} ({@code snapshot}: every entity every day, the default, or
 * {@code effective}: a line when an entity changes), {@code layout.<kind>.columns} (the promoted paths),
 * {@code id-field} (the id of a plain document per line, default {@code id}), {@code index-cache-mb} (1024: days of
 * indexes kept by memory), {@code max-load-rows} (200000: lines a reverse lookup reads for a kind without promoted
 * link columns).
 */
public final class FileSourcePlugin implements SourcePlugin {

    private static final java.util.regex.Pattern DATE = java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final HitIndex index = new HitIndex();
    private volatile List<LocalDate> dates = List.of();   // dated folders, newest first
    private SourceContext context;
    private Path root;
    private String sourceName;
    private int lookbackDays;
    private static final LocalDate UNDATED = LocalDate.MIN;
    private record DayKey(String kind, LocalDate day) {}
    private final java.util.Map<String, String> modes = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, List<String>> promoted = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile java.util.Map<String, java.util.NavigableSet<LocalDate>> jsonl = java.util.Map.of();   // kind -> days with a file
    private com.github.benmanes.caffeine.cache.Cache<DayKey, JsonlDay> days;
    private String idField;
    private volatile Set<String> kinds = Set.of();
    private int maxLoadRows;

    @Override
    public PluginManifest manifest() {
        // the kinds found (JSON-lines files and kind folders) once there are JSON-lines files; else any kind, as files appear
        return new PluginManifest("file", "1.0", jsonl.isEmpty() ? Set.of() : kinds, new SourceCapabilities(false, true, true, true));
    }

    @Override
    public void start(SourceContext ctx) {
        this.context = ctx;
        Path base = Path.of(ctx.setting("root", "data/feeds"));
        String domain = ctx.setting("domain", "");
        this.root = (domain.isBlank() ? base : base.resolve(domain)).toAbsolutePath().normalize();
        this.idField = ctx.setting("id-field", "id");
        this.maxLoadRows = Integer.parseInt(ctx.setting("max-load-rows", "200000"));
        ctx.settings().forEach((k, v) -> {
            if (k.startsWith("mode.")) {
                modes.put(k.substring(5), v);
            } else if (k.startsWith("layout.") && k.endsWith(".columns")) {
                promoted.put(k.substring(7, k.length() - 8), java.util.Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
            }
        });
        this.days = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .maximumWeight(Long.parseLong(ctx.setting("index-cache-mb", "1024")) * 1024 * 1024)
                .weigher((DayKey k, JsonlDay v) -> (int) Math.min(Integer.MAX_VALUE, v.weight())).build();
        this.sourceName = ctx.setting("source-name", "file");
        long rescan = Long.parseLong(ctx.setting("rescan-seconds", "30"));
        this.lookbackDays = Integer.parseInt(ctx.setting("lookback-days", "10"));
        rescan();
        ctx.scheduler().scheduleWithFixedDelay(this::rescan, rescan, rescan, TimeUnit.SECONDS);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws IOException {
        return fetch(ref, AsOf.LATEST);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) throws IOException {
        Optional<EntityDocument> line = fromJsonl(ref, asOf.businessDate());
        if (line.isPresent()) {
            return line;
        }
        LocalDate want = asOf.businessDate();
        for (LocalDate d : dates) {
            if (want != null && (d.isAfter(want) || d.isBefore(want.minusDays(lookbackDays)))) {
                continue;
            }
            Optional<EntityDocument> hit = read(ref, root.resolve(d.toString()), d);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return read(ref, root, null);
    }

    private Optional<EntityDocument> read(EntityRef ref, Path base, LocalDate date) throws IOException {
        for (String ext : List.of(".json", ".csv")) {
            Path p = resolve(base, ref, ext);
            if (p != null && Files.isRegularFile(p)) {
                return Optional.of(new EntityDocument(ref, parse(p), new Provenance(sourceName,
                        Files.getLastModifiedTime(p).toMillis(), Instant.now(), false, date)));
            }
        }
        return Optional.empty();
    }

    /** Resolves the file for {@code ref}, refusing identifiers that would escape the root directory. */
    Path resolve(EntityRef ref, String ext) {
        return resolve(root, ref, ext);
    }

    private Path resolve(Path base, EntityRef ref, String ext) {
        Path p = base.resolve(ref.kind()).resolve(ref.id() + ext).normalize();
        return p.startsWith(base) && p.startsWith(root) ? p : null;
    }

    private DataNode parse(Path p) throws IOException {
        if (p.toString().endsWith(".csv")) {
            try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                return CsvReader.read(r);
            }
        }
        try (InputStream in = Files.newInputStream(p)) {
            return context.parseJson(in);
        }
    }

    private boolean effective(String kind) {
        return "effective".equals(modes.get(kind));
    }

    private Path jsonlFile(String kind, LocalDate day) {
        Path p = (day.equals(UNDATED) ? root : root.resolve(day.toString())).resolve(kind + ".jsonl").normalize();
        return p.startsWith(root) ? p : null;
    }

    /** A day's index, built on first use and again whenever the file changes. */
    private JsonlDay day(String kind, LocalDate day) {
        DayKey key = new DayKey(kind, day);
        JsonlDay d = days.getIfPresent(key);
        if (d != null && !d.current()) {
            days.invalidate(key);
        }
        return days.get(key, k -> {
            try {
                return JsonlDay.index(jsonlFile(kind, day), day.equals(UNDATED) ? null : day, promoted.getOrDefault(kind, List.of()), idField);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    /** The snapshot day of a kind on {@code asked}: its newest dated file on or before it, within the lookback. */
    private Optional<LocalDate> snapshotDay(String kind, LocalDate asked) {
        java.util.NavigableSet<LocalDate> ds = jsonl.get(kind);
        if (ds == null) {
            return Optional.empty();
        }
        java.util.NavigableSet<LocalDate> dated = ds.tailSet(UNDATED, false);
        if (dated.isEmpty()) {
            return ds.contains(UNDATED) ? Optional.of(UNDATED) : Optional.empty();
        }
        LocalDate d = asked == null ? dated.last() : dated.floor(asked);
        if (d == null || asked != null && d.isBefore(asked.minusDays(lookbackDays))) {
            return ds.contains(UNDATED) ? Optional.of(UNDATED) : Optional.empty();
        }
        return Optional.of(d);
    }

    private Optional<EntityDocument> fromJsonl(EntityRef ref, LocalDate asked) throws IOException {
        if (!jsonl.containsKey(ref.kind())) {
            return Optional.empty();
        }
        List<LocalDate> tries = new ArrayList<>();
        if (effective(ref.kind())) {                                 // the entity's latest line on or before the date
            java.util.NavigableSet<LocalDate> ds = jsonl.get(ref.kind());
            (asked == null ? ds : ds.headSet(asked, true)).descendingSet().forEach(tries::add);
        } else {
            snapshotDay(ref.kind(), asked).ifPresent(tries::add);
        }
        for (LocalDate d : tries) {
            JsonlDay index;
            try {
                index = day(ref.kind(), d);
            } catch (java.io.UncheckedIOException e) {
                continue;                                          // the file went away: the next rescan forgets it
            }
            Optional<byte[]> doc = index.document(ref.id());
            if (doc.isPresent()) {
                Path f = jsonlFile(ref.kind(), d);
                return Optional.of(new EntityDocument(ref, context.parseJson(new java.io.ByteArrayInputStream(doc.get())),
                        new Provenance(sourceName, Files.getLastModifiedTime(f).toMillis(), Instant.now(), false, d.equals(UNDATED) ? null : d)));
            }
        }
        return Optional.empty();
    }

    @Override
    public Set<String> columnar(String kind) {
        return effective(kind) || !jsonl.containsKey(kind) ? Set.of() : Set.copyOf(promoted.getOrDefault(kind, List.of()));
    }

    @Override
    public Optional<com.ash.drishti.api.ColumnSet> columns(String kind, java.util.Collection<String> paths, AsOf asOf) {
        if (!columnar(kind).containsAll(paths)) {
            return Optional.empty();
        }
        Optional<LocalDate> d = snapshotDay(kind, asOf.businessDate());
        if (d.isEmpty()) {
            return Optional.empty();                               // a day these files do not hold: another store may
        }
        com.ash.drishti.api.ColumnSet all = day(kind, d.get()).columns();
        java.util.Map<String, double[]> nums = new java.util.LinkedHashMap<>();
        java.util.Map<String, String[]> texts = new java.util.LinkedHashMap<>();
        for (String p : paths) {
            if (all.numbers().containsKey(p)) {
                nums.put(p, all.numbers().get(p));
            } else if (all.texts().containsKey(p)) {
                texts.put(p, all.texts().get(p));
            }
        }
        return Optional.of(new com.ash.drishti.api.ColumnSet(all.ids(), nums, texts, all.businessDate()));
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind) {
        return reverse(target, kind, AsOf.LATEST);
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind, AsOf asOf) {
        List<EntityRef> out = new ArrayList<>();
        for (String k : kind == null ? jsonl.keySet() : Set.of(kind)) {
            Optional<LocalDate> d = effective(k) ? Optional.empty() : snapshotDay(k, asOf.businessDate());
            if (d.isEmpty()) {
                continue;
            }
            try {
                JsonlDay index = day(k, d.get());
                java.util.Collection<String> found;
                if (!columnar(k).isEmpty()) {                       // promoted link columns: no line is read
                    found = new java.util.TreeSet<>();
                    com.ash.drishti.api.ColumnSet c = index.columns();
                    for (String[] values : c.texts().values()) {
                        for (int i = 0; i < values.length; i++) {
                            if (target.id().equals(values[i])) {
                                found.add(c.ids()[i]);
                            }
                        }
                    }
                } else {
                    found = index.mentioning(target.id(), maxLoadRows);
                }
                found.forEach(i -> out.add(EntityRef.of(k, i)));
            } catch (IOException | RuntimeException e) {
                // no referrers from here, not an error page
            }
        }
        return out;
    }

    @Override
    public java.util.Map<String, Object> cacheStats() {
        return java.util.Map.of("jsonlKinds", jsonl.size(), "jsonlDays", jsonl.values().stream().mapToInt(Set::size).sum(), "indexedDays",
                days == null ? 0 : days.estimatedSize(), "ids", index.size());
    }

    @Override
    public void purgeCaches() {
        if (days != null) {
            days.invalidateAll();
        }
    }

    /** The newest file's modification time: when the folder last received new data. */
    private volatile java.time.Instant lastUpdate;

    @Override
    public java.time.Instant lastUpdate() {
        return lastUpdate;
    }

    void rescan() {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> all = Files.walk(root)) {
            all.filter(Files::isRegularFile).map(f -> {
                try {
                    return Files.getLastModifiedTime(f).toInstant();
                } catch (IOException e) {
                    return null;
                }
            }).filter(java.util.Objects::nonNull).max(java.util.Comparator.naturalOrder()).ifPresent(t -> lastUpdate = t);
        } catch (IOException | java.io.UncheckedIOException e) {
            // the next rescan tries again
        }
        List<EntityHit> hits = new ArrayList<>();
        List<LocalDate> found = new ArrayList<>();
        java.util.Map<String, java.util.NavigableSet<LocalDate>> lines = new java.util.TreeMap<>();
        try (Stream<Path> kinds = Files.list(root)) {
            for (Path f : kinds.toList()) {
                String n = f.getFileName().toString();
                if (Files.isRegularFile(f) && n.endsWith(".jsonl")) {
                    lines.computeIfAbsent(n.substring(0, n.length() - 6), k -> new java.util.TreeSet<>()).add(UNDATED);
                } else if (Files.isDirectory(f) && DATE.matcher(n).matches()) {
                    try (Stream<Path> inDay = Files.list(f)) {
                        for (Path g : inDay.toList()) {
                            String m = g.getFileName().toString();
                            if (Files.isRegularFile(g) && m.endsWith(".jsonl")) {
                                lines.computeIfAbsent(m.substring(0, m.length() - 6), k -> new java.util.TreeSet<>()).add(LocalDate.parse(n));
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            return;
        }
        jsonl = java.util.Collections.unmodifiableMap(lines);
        Set<String> held = new java.util.TreeSet<>(lines.keySet());
        try (Stream<Path> kinds = Files.list(root)) {
            for (Path kindDir : kinds.filter(Files::isDirectory).toList()) {
                String kind = kindDir.getFileName().toString();
                if (DATE.matcher(kind).matches()) {
                    found.add(LocalDate.parse(kind));
                    continue;
                }
                held.add(kind);
                try (Stream<Path> files = Files.list(kindDir)) {
                    files.map(f -> f.getFileName().toString())
                            .filter(n -> n.endsWith(".json") || n.endsWith(".csv"))
                            .forEach(n -> {
                                String id = n.substring(0, n.lastIndexOf('.'));
                                hits.add(new EntityHit(EntityRef.of(kind, id), id, kind + " · " + sourceName));
                            });
                }
            }
        } catch (IOException e) {
            return;
        }
        found.sort(java.util.Comparator.reverseOrder());
        dates = List.copyOf(found);
        kinds = Set.copyOf(held);
        java.util.Map<EntityRef, EntityHit> unique = new java.util.LinkedHashMap<>();
        hits.forEach(h -> unique.putIfAbsent(h.ref(), h));
        if (!found.isEmpty()) {
            indexDated(root.resolve(found.get(0).toString()), unique);
        }
        // each JSON-lines kind's newest file (or its undated one): its ids, from the day's index (built here, in the background)
        lines.forEach((kind, ds) -> {
            LocalDate newest = effective(kind) ? null : snapshotDay(kind, null).orElse(null);
            for (LocalDate d : newest == null ? ds : Set.of(newest)) {
                try {
                    for (String id : day(kind, d).ids()) {
                        unique.putIfAbsent(EntityRef.of(kind, id), new EntityHit(EntityRef.of(kind, id), id, kind + " · " + sourceName));
                    }
                } catch (RuntimeException e) {
                    // an unreadable file: its kind is not suggested until it is fixed
                }
            }
        });
        index.replaceAll(new ArrayList<>(unique.values()));
    }

    /** Adds the newest dated folder's entities to the search index (ids are stable across dates). */
    private void indexDated(Path dir, java.util.Map<EntityRef, EntityHit> hits) throws java.io.UncheckedIOException {
        try (Stream<Path> kinds = Files.list(dir)) {
            for (Path kindDir : kinds.filter(Files::isDirectory).toList()) {
                String kind = kindDir.getFileName().toString();
                try (Stream<Path> files = Files.list(kindDir)) {
                    files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".json") || n.endsWith(".csv")).forEach(n -> {
                        String id = n.substring(0, n.lastIndexOf('.'));
                        EntityRef r = EntityRef.of(kind, id);
                        hits.putIfAbsent(r, new EntityHit(r, id, kind + " · " + sourceName));   // a map: a million ids stay linear
                    });
                }
            }
        } catch (IOException ignored) {
            // a folder that vanished mid-scan is picked up next time
        }
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return index.search(kind, text, limit);
    }

    @Override
    public String health() {
        return Files.isDirectory(root) ? "UP" : "DOWN: no directory " + root;
    }
}
