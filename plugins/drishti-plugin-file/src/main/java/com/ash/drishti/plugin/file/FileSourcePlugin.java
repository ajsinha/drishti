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
import com.ash.drishti.api.DateCoverage;
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
    private JsonlFormat format = JsonlFormat.DEFAULT;
    /** Days whose file had unreadable lines, duplicate ids, or could not be read: for health and stats. */
    private final java.util.Map<DayKey, JsonlDay.Report> problems = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.LongAdder indexBuilds = new java.util.concurrent.atomic.LongAdder();
    private static final System.Logger LOG = System.getLogger(FileSourcePlugin.class.getName());

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
        this.format = JsonlFormat.of(ctx.settings());
        ctx.settings().forEach((k, v) -> {
            if (k.startsWith("mode.")) {
                modes.put(k.substring(5), v);
            } else if (k.startsWith("layout.") && k.endsWith(".columns")) {
                promoted.put(k.substring(7, k.length() - 8), java.util.Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
            }
        });
        this.days = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .maximumWeight(Long.parseLong(ctx.setting("index-cache-mb", "1024")) * 1024 * 1024)
                .weigher((DayKey k, JsonlDay v) -> (int) Math.min(Integer.MAX_VALUE, v.weight()))
                .removalListener((DayKey k, JsonlDay v, com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
                    if (v != null) {
                        v.close();                                 // its file: a read still holding it builds a new index
                    }
                }).build();
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

    /**
     * A day's index, built on first use and again whenever the file changes. A file with unreadable lines, duplicate
     * ids, or that cannot be read at all is indexed once (and logged once) and kept until it changes.
     *
     * @throws java.io.UncheckedIOException when the file is gone
     */
    private JsonlDay day(String kind, LocalDate day) {
        DayKey key = new DayKey(kind, day);
        JsonlDay d = days.getIfPresent(key);
        if (d != null && !d.current()) {
            days.asMap().remove(key, d);
        }
        return days.get(key, k -> {
            try {
                Path file = jsonlFile(kind, day);
                JsonlDay built = JsonlDay.index(file, day.equals(UNDATED) ? null : day, promoted.getOrDefault(kind, List.of()), idField, format);
                indexBuilds.increment();
                report(k, built);
                return built;
            } catch (IOException e) {
                problems.remove(k);
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    /** Where a day's file is, relative to the root, as health and answers name it. */
    private String where(DayKey k) {
        Path f = jsonlFile(k.kind(), k.day());
        return f == null ? k.kind() + ".jsonl" : root.relativize(f).toString().replace('\\', '/');
    }

    /** Records what was wrong with a day's file (logged once per version of the file), or that nothing was. */
    private void report(DayKey k, JsonlDay d) {
        JsonlDay.Report r = d.report();
        if (r.clean()) {
            problems.remove(k);
            return;
        }
        problems.put(k, r);
        LOG.log(System.Logger.Level.WARNING, "{0}: {1}", sourceName, r.describe(where(k)));
    }

    /** Why the day's columns may be missing entities, or null. */
    private String incomplete(DayKey k, JsonlDay d) {
        return d.report().incomplete() ? d.report().describe(where(k)) : null;
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
            Optional<EntityDocument> found = fromJsonlDay(ref, d);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * The entity's line in one day's file. A read that finds the file changed under its index (replaced, rewritten)
     * builds a new index and reads again, so it never uses one file's offsets on another.
     */
    private Optional<EntityDocument> fromJsonlDay(EntityRef ref, LocalDate d) throws IOException {
        DayKey key = new DayKey(ref.kind(), d);
        for (int attempt = 0; ; attempt++) {
            JsonlDay index;
            try {
                index = day(ref.kind(), d);
            } catch (java.io.UncheckedIOException e) {
                if (e.getCause() instanceof java.nio.file.NoSuchFileException || !Files.exists(jsonlFile(ref.kind(), d))) {
                    return Optional.empty();                       // the file went away: the next rescan forgets it
                }
                // the file is there but cannot be read (permissions, an I/O error): a failure, which stops the read with
                // DRS-1003, not "not held", which would let another store answer with other data (unreadable lines are
                // skipped and counted by the index: that day is incomplete, not failed)
                throw e.getCause();
            }
            if (index.report().failure() != null) {
                // the file is there but could not be read at all (indexed once, until it changes): a failure, not "not
                // held"; a day with some unreadable lines skipped is served (its searches say partial)
                throw new IOException(sourceName + " cannot read " + where(key) + ": " + index.report().failure());
            }
            Optional<byte[]> doc;
            try {
                doc = index.document(ref.id());
            } catch (JsonlDay.StaleIndexException e) {
                days.asMap().remove(key, index);
                if (attempt < 4) {
                    continue;
                }
                throw e;
            }
            if (doc.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new EntityDocument(ref, format.read(doc.get()),
                    new Provenance(sourceName, index.modified(), Instant.now(), false, d.equals(UNDATED) ? null : d)));
        }
    }

    /**
     * A kind kept as dated JSON-lines snapshots (every entity every day) is held for a date when a day's file serves it
     * (the newest on or before the date, within {@code lookback-days}): an entity that file does not list is not held
     * then, and no store behind this one is asked (DATA-12). With no such file, and no feed folder the date could be
     * read from, the date is not held; nor is any date of a kind the connector has no file or folder of (the shipped
     * {@code file} connector serves every kind until files appear). Undated files, {@code effective} kinds (a line only
     * when an entity changes) and feed folders cannot tell.
     */
    @Override
    public DateCoverage coverage(String kind, AsOf asOf) {
        if (!jsonl.containsKey(kind)) {                 // per-entity files only, or nothing of the kind at all
            return feedFolders(kind, asOf.businessDate()) ? DateCoverage.UNKNOWN : DateCoverage.NOT_HELD;
        }
        if (effective(kind)) {
            return DateCoverage.UNKNOWN;
        }
        Optional<LocalDate> d = snapshotDay(kind, asOf.businessDate());
        if (d.isPresent()) {
            return d.get().equals(UNDATED) ? DateCoverage.UNKNOWN : DateCoverage.HELD;
        }
        return feedFolders(kind, asOf.businessDate()) ? DateCoverage.UNKNOWN : DateCoverage.NOT_HELD;
    }

    /** True when a feed folder ({@code <root>/<date>/<kind>/} within the lookback, or {@code <root>/<kind>/}) may hold the kind. */
    private boolean feedFolders(String kind, LocalDate want) {
        Path undated = root.resolve(kind).normalize();
        if (undated.startsWith(root) && Files.isDirectory(undated)) {
            return true;
        }
        for (LocalDate d : dates) {
            if (want != null && (d.isAfter(want) || d.isBefore(want.minusDays(lookbackDays)))) {
                continue;
            }
            Path dir = root.resolve(d.toString()).resolve(kind).normalize();
            if (dir.startsWith(root) && Files.isDirectory(dir)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Set<String> columnar(String kind) {
        return effective(kind) || !jsonl.containsKey(kind) ? Set.of() : Set.copyOf(promoted.getOrDefault(kind, List.of()));
    }

    @Override
    public Optional<com.ash.drishti.api.ColumnSet> columns(String kind, java.util.Collection<String> paths, AsOf asOf) throws IOException {
        if (!columnar(kind).containsAll(paths)) {
            return Optional.empty();
        }
        Optional<LocalDate> d = snapshotDay(kind, asOf.businessDate());
        if (d.isEmpty()) {
            return Optional.empty();                               // a day these files do not hold: another store may
        }
        JsonlDay index = day(kind, d.get());
        if (!index.readable()) {
            // the file is there but cannot be read (health says why): a failure, so another store's columns are not used in
            // its place; the search says partial and names this connector (DATA-03)
            throw new IOException(sourceName + " cannot read " + where(new DayKey(kind, d.get())) + ": " + index.report().failure());
        }
        com.ash.drishti.api.ColumnSet all = index.columns().select(paths);
        // a day with unreadable lines answers what it holds and says why it may be missing some, for a partial answer
        return Optional.of(new com.ash.drishti.api.ColumnSet(all.ids(), all.numbers(), all.texts(), all.businessDate(),
                incomplete(new DayKey(kind, d.get()), index), all.mixed()));
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind) {
        return reverse(target, kind, AsOf.LATEST);
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind, AsOf asOf) {
        List<EntityRef> out = new ArrayList<>();
        for (String k : kind == null ? jsonl.keySet() : Set.of(kind)) {
            if (effective(k)) {
                effectiveReverse(target, k, asOf.businessDate()).forEach(i -> out.add(EntityRef.of(k, i)));
                continue;
            }
            Optional<LocalDate> d = snapshotDay(k, asOf.businessDate());
            if (d.isEmpty()) {
                continue;
            }
            try {
                JsonlDay index = day(k, d.get());
                java.util.Collection<String> found;
                if (!columnar(k).isEmpty()) {                       // promoted link columns: no line is read
                    found = new java.util.TreeSet<>();
                    com.ash.drishti.api.ColumnSet c = index.columns();
                    List<Object[]> columns = new ArrayList<>(c.texts().values());
                    columns.addAll(c.mixed().values());
                    for (Object[] values : columns) {
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

    /**
     * Entities of a kind kept in {@code effective} mode whose version on {@code asked} (their newest line on or before
     * it, as {@link #fetch} reads them) mentions the target (DATA-08): the days are read newest first and each entity
     * only in its newest line, at most {@code max-load-rows} lines in all. A day that cannot be read ends the lookup
     * there, so an older version never stands in for a newer one.
     */
    private List<String> effectiveReverse(EntityRef target, String kind, LocalDate asked) {
        java.util.NavigableSet<LocalDate> ds = jsonl.get(kind);
        List<String> found = new ArrayList<>();
        if (ds == null) {
            return found;
        }
        Set<String> seen = new java.util.HashSet<>();
        for (LocalDate d : (asked == null ? ds : ds.headSet(asked, true)).descendingSet()) {
            int left = maxLoadRows - seen.size();
            if (left <= 0) {
                break;
            }
            try {
                found.addAll(day(kind, d).mentioning(target.id(), left, seen));
            } catch (IOException | RuntimeException e) {
                break;                                             // no referrers from here, not an error page
            }
        }
        return found;
    }

    @Override
    public java.util.Map<String, Object> cacheStats() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("jsonlKinds", jsonl.size());
        m.put("jsonlDays", jsonl.values().stream().mapToInt(Set::size).sum());
        m.put("indexedDays", days == null ? 0 : days.estimatedSize());
        m.put("ids", index.size());
        m.put("indexBuilds", indexBuilds.sum());
        m.put("unreadableLines", problems.values().stream().mapToLong(JsonlDay.Report::unreadable).sum());
        m.put("duplicateIds", problems.values().stream().mapToLong(JsonlDay.Report::duplicates).sum());
        m.put("unreadableFiles", problems.values().stream().filter(r -> r.failure() != null).count());
        return m;
    }

    @Override
    public void purgeCaches() {
        if (days != null) {
            days.invalidateAll();
        }
        problems.clear();
    }

    @Override
    public void close() {
        purgeCaches();
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
        problems.keySet().removeIf(k -> !lines.getOrDefault(k.kind(), java.util.Collections.emptyNavigableSet()).contains(k.day()));   // files gone
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

    /**
     * UP, and what is wrong with the files indexed so far: "UP (3 unreadable lines in trading/2026-09-30/trade.jsonl
     * (line 201: ...))". The rest of each such file is served.
     */
    @Override
    public String health() {
        if (!Files.isDirectory(root)) {
            return "DOWN: no directory " + root;
        }
        List<String> said = problems.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey(java.util.Comparator.comparing(DayKey::day).reversed().thenComparing(DayKey::kind)))
                .map(e -> e.getValue().describe(where(e.getKey()))).toList();
        if (said.isEmpty()) {
            return "UP";
        }
        return "UP (" + String.join("; ", said.subList(0, Math.min(3, said.size()))) + (said.size() > 3 ? "; and " + (said.size() - 3) + " more files" : "") + ")";
    }
}
