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
package com.ash.drishti.plugin.icebergext;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
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
import com.ash.drishti.lakehost.LakeReads;
import com.ash.lake.read.ColumnAsk;
import com.ash.lake.read.ColumnBlock;
import com.ash.lake.read.DayFiles;
import com.ash.lake.read.IdMap;
import com.ash.lake.read.LakeReader;
import com.ash.lake.read.TableReader;
import com.ash.lake.read.TableShape;
import com.ash.lake.read.TableSnapshot;
import com.ash.lake.spi.ColumnType;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serves entities from Iceberg tables Drishti does not write (plugins/drishti-plugin-iceberg-external/README.md): tables of Spark, Flink,
 * Trino, Snowflake, Glue, dbt or pyiceberg, in their own layout, read through Drishti Lake's shaped read of a foreign table
 * ({@link LakeReader#foreign}, {@link TableShape}; no Hadoop) and never written. A kind is a table ({@code tables.<kind>.table}); its
 * document is the row as JSON, or a JSON column; its id is one column or several joined; its business date a date column, a timestamp's
 * day, by default the partition's date, or none (current only). The shape's rules (what is a document, an id, a date, a searchable path)
 * are the product's; what stays here is the connector's: the kinds, the id and column caches, the type-ahead, links, refresh and health.
 * {@code tables: auto} with {@code namespace} serves every table of a namespace. Time travel by {@code knownAt} through the snapshot log.
 * Settings: the catalog's, as the {@code iceberg} connector's; {@code decimals}, {@code binary}, {@code refresh-seconds} (10),
 * {@code lookback-days} (10), {@code doc-cache-mb} (256), {@code columns-cache-mb} (1024), {@code id-index-mb} (1024),
 * {@code max-concurrent-reads} (16), {@code source-name} ({@code iceberg-external}).
 */
public final class IcebergExternalPlugin implements SourcePlugin {

    private record Kind(TableShape shape, TableReader reader, boolean effective) {}

    private record Columns(String[] ids, Map<String, double[]> numbers, Map<String, String[]> texts) {
        long bytes() {
            long b = 64L + 48L * ids.length;
            for (String id : ids) {
                b += id.length();
            }
            return b + 8L * ids.length * (numbers.size() + texts.size());
        }
    }

    private final Map<String, Kind> kinds = new ConcurrentHashMap<>();
    private final Map<String, TableSnapshot> current = new ConcurrentHashMap<>();
    private final Map<String, String> problems = new ConcurrentHashMap<>();
    private final Map<String, Long> bigIndexes = new ConcurrentHashMap<>();
    private final HitIndex index = new HitIndex();
    private final Set<String> partial = ConcurrentHashMap.newKeySet();
    private final ReentrantLock refreshing = new ReentrantLock();
    private Cache<String, IdMap> idMaps;
    private Cache<String, Columns> columnSets;
    private Cache<String, String> docs;
    private com.ash.drishti.api.ReadSlots reads;      // bounded queue; a read whose caller has gone leaves it (QA3A-01)
    private SourceContext context;
    private Map<String, String> settings;
    private LakeReader lake;
    private String sourceName;
    private int lookbackDays;
    private long maxIndexRows;
    private long warnRows;
    private volatile Instant lastUpdate;

    @Override
    public PluginManifest manifest() {
        return new PluginManifest(sourceName == null ? "iceberg-external" : sourceName, "1.0", kinds.keySet(), new SourceCapabilities(false, true, true, true));
    }

    @Override
    public boolean timeTravel() {
        return true;
    }

    @Override
    public void start(SourceContext ctx) throws IOException {
        boolean s3Tables = "s3tables".equalsIgnoreCase(ctx.setting("catalog", ""));
        if (s3Tables && ctx.setting("table-bucket-arn", "").isBlank()) {
            throw new com.ash.drishti.api.PluginNotConfigured("iceberg-external with catalog: s3tables needs settings.table-bucket-arn");
        }
        if (ctx.setting("root", "").isBlank() && ctx.setting("uri", "").isBlank() && !s3Tables
                && ctx.settings().keySet().stream().noneMatch(k -> k.startsWith("metadata-location."))) {
            throw new com.ash.drishti.api.PluginNotConfigured("iceberg-external needs settings.root, settings.uri (a REST catalog) or metadata-location.<kind>");
        }
        this.context = ctx;
        this.settings = Map.copyOf(ctx.settings());
        this.sourceName = ctx.setting("source-name", "iceberg-external");
        this.lookbackDays = Integer.parseInt(ctx.setting("lookback-days", "10"));
        this.maxIndexRows = Long.parseLong(ctx.setting("max-index-rows", "10000000"));
        this.warnRows = Long.parseLong(ctx.setting("id-index-warn-rows", "5000000"));
        Map<String, String> readerSettings = new java.util.HashMap<>(settings);
        String namespace = settings.getOrDefault("namespace", "");
        boolean rest = "rest".equalsIgnoreCase(settings.getOrDefault("catalog", "")) || s3Tables;
        if (!rest && !namespace.isBlank() && !settings.containsKey("domain")) {      // a path-based namespace is the folder <root>/<levels>
            readerSettings.put("domain", namespace.replace('.', '/'));
        }
        this.lake = LakeReads.open("iceberg", readerSettings);
        this.idMaps = Caffeine.newBuilder().maximumWeight(mb("id-index-mb", "1024"))
                .weigher((String k, IdMap v) -> (int) Math.min(Integer.MAX_VALUE, 64L + Math.max(v.bytes(), 60L * v.size()))).build();
        this.columnSets = Caffeine.newBuilder().maximumWeight(mb("columns-cache-mb", "1024"))
                .weigher((String k, Columns v) -> (int) Math.min(Integer.MAX_VALUE, v.bytes())).build();
        this.docs = Caffeine.newBuilder().maximumWeight(mb("doc-cache-mb", "256")).weigher((String k, String v) -> 2 * (k.length() + v.length()) + 64).build();
        this.reads = new com.ash.drishti.api.ReadSlots("document reads of " + sourceName,
                Integer.parseInt(ctx.setting("max-concurrent-reads", "16")), Integer.parseInt(ctx.setting("max-queued-reads", "4096")));
        refresh();
        long every = Long.parseLong(ctx.setting("refresh-seconds", "10"));
        ctx.scheduler().scheduleWithFixedDelay(this::refresh, every, every, TimeUnit.SECONDS);
    }

    private long mb(String key, String fallback) {
        return Long.parseLong(context.setting(key, fallback)) * 1024 * 1024;
    }

    /** The kinds configured, those named by a metadata file, and (tables: auto) the namespace's tables. */
    private List<TableShape> shapes() throws IOException {
        List<TableShape> out = new ArrayList<>(TableShape.all(settings));
        Set<String> metadataKinds = new TreeSet<>();
        settings.keySet().forEach(k -> {
            if (k.startsWith("metadata-location.")) {
                metadataKinds.add(k.substring("metadata-location.".length()));
            }
        });
        for (String k : metadataKinds) {
            if (out.stream().noneMatch(c -> c.kind().equals(k))) {
                out.add(TableShape.of(k, settings, k));
            }
        }
        if ("auto".equalsIgnoreCase(settings.getOrDefault("tables", "").trim())) {
            String ns = settings.getOrDefault("namespace", "");
            for (String t : lake.tables()) {
                String kind = TableShape.kindOf(t);
                if (out.stream().noneMatch(c -> c.kind().equals(kind))) {
                    out.add(TableShape.of(kind, settings, ns.isBlank() ? t : ns + "." + t));
                }
            }
        }
        return out;
    }

    void refresh() {
        if (!refreshing.tryLock()) {
            return;
        }
        try {
            List<TableShape> wanted;
            try {
                wanted = shapes();
            } catch (IOException | RuntimeException e) {
                problems.put("(catalog)", "cannot list the tables: " + e.getMessage());
                return;
            }
            problems.remove("(catalog)");
            lake.refresh();                                            // each table's current snapshot is read again at its next snapshot()
            for (TableShape s : wanted) {
                if (kinds.containsKey(s.kind())) {
                    continue;
                }
                try {
                    TableReader r = lake.foreign(s.table(), s);
                    Optional<TableSnapshot> snap = r.snapshot(com.ash.lake.spi.AsOf.LATEST);
                    if (snap.isEmpty()) {                              // the reader names the files looked for and what is there instead (QA2-203)
                        problems.put(s.kind(), s.kind() + ": no table " + s.table());
                        continue;
                    }
                    problems.remove(s.kind());
                    kinds.put(s.kind(), new Kind(s, r, "effective".equalsIgnoreCase(settings.getOrDefault("tables." + s.kind() + ".mode", "snapshot"))));
                } catch (IOException | RuntimeException e) {
                    problems.put(s.kind(), s.kind() + ": " + e.getMessage());
                }
            }
            boolean changed = false;
            for (Map.Entry<String, Kind> e : kinds.entrySet()) {
                try {
                    Optional<TableSnapshot> snap = e.getValue().reader().snapshot(com.ash.lake.spi.AsOf.LATEST);
                    TableSnapshot before = current.get(e.getKey());
                    if (snap.isPresent() && (before == null || before.version() != snap.get().version())) {
                        current.put(e.getKey(), snap.get());
                        changed = true;
                        if (lastUpdate == null || snap.get().committed().isAfter(lastUpdate)) {
                            lastUpdate = snap.get().committed();
                        }
                    }
                } catch (IOException | RuntimeException ex) {
                    // unreachable for now: the last snapshot keeps serving
                }
            }
            if (changed || index.size() == 0) {
                reindex();
            }
        } finally {
            refreshing.unlock();
        }
    }

    /** Type-ahead: the newest day's ids (every day's for an effective kind; all ids, bounded, for a current-only kind). */
    private void reindex() {
        List<EntityHit> hits = new ArrayList<>();
        for (Map.Entry<String, Kind> e : kinds.entrySet()) {
            String kind = e.getKey();
            try {
                TableSnapshot s = snapshot(kind, null).orElse(null);
                if (s == null || s.dates().isEmpty()) {
                    continue;
                }
                String subtitle = kind + " · " + sourceName;
                Collection<LocalDate> days = e.getValue().effective() ? s.dates() : List.of(s.dates().last());
                Set<String> seen = new HashSet<>();
                for (LocalDate d : days) {
                    IdMap ids = idMap(kind, s, d);
                    if (ids.size() >= maxIndexRows) {
                        partial.add(kind);
                    }
                    for (int i = 0; i < ids.size(); i++) {
                        String id = ids.id(i);
                        if (seen.add(id)) {
                            hits.add(new EntityHit(EntityRef.of(kind, id), id, subtitle));
                        }
                    }
                }
            } catch (IOException | RuntimeException ex) {
                // indexed next time
            }
        }
        index.replaceAll(hits);
    }

    private Optional<TableSnapshot> snapshot(String kind, Instant knownAt) {
        Kind k = kinds.get(kind);
        if (k == null) {
            return Optional.empty();
        }
        if (knownAt == null) {
            return Optional.ofNullable(current.get(kind));
        }
        try {
            return k.reader().snapshot(com.ash.lake.spi.AsOf.time(knownAt));
        } catch (IOException e) {
            throw LakeReads.failure(kind, "log", e);
        }
    }

    private IdMap idMap(String kind, TableSnapshot s, LocalDate d) throws IOException {
        String key = kind + "\u001f" + s.version() + "\u001f" + d;
        IdMap m = idMaps.getIfPresent(key);
        if (m == null) {
            m = s.ids(d);
            idMaps.put(key, m);
        }
        if (m.size() > warnRows) {
            bigIndexes.put(kind, (long) m.size());
        }
        return m;
    }

    private String stampOf(TableSnapshot s, LocalDate d) {
        return s.day(d).map(DayFiles::stamp).orElse("");
    }

    /** The days to look in for {@code date}, newest first (current-only kinds have one). */
    private List<LocalDate> candidates(String kind, TableSnapshot s, LocalDate date) {
        boolean effective = kinds.get(kind).effective();
        if (s.dates().size() == 1 && s.dates().first().equals(LocalDate.MIN)) {
            return List.of(LocalDate.MIN);
        }
        var head = date == null ? s.dates().descendingSet() : s.dates().headSet(date, true).descendingSet();
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d : head) {
            if (!effective && date != null && d.isBefore(date.minusDays(lookbackDays))) {
                break;
            }
            out.add(d);
            if (!effective) {
                break;
            }
        }
        return out;
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws IOException {
        return fetch(ref, AsOf.LATEST);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) throws IOException {
        Optional<TableSnapshot> o = snapshot(ref.kind(), asOf.knownAt());
        if (o.isEmpty()) {
            return Optional.empty();
        }
        TableSnapshot s = o.get();
        for (LocalDate d : candidates(ref.kind(), s, asOf.businessDate())) {
            String key = ref.kind() + "\u001f" + stampOf(s, d) + "\u001f" + d + "\u001f" + ref.id();
            String json = docs.getIfPresent(key);
            if (json == null) {
                int file = idMap(ref.kind(), s, d).file(ref.id());
                if (file < 0) {
                    continue;
                }
                reads.acquire();
                try {
                    json = s.document(d, file, ref.id()).orElse(null);
                } catch (IOException | RuntimeException e) {
                    throw LakeReads.failure(ref.kind(), d.toString(), e);
                } finally {
                    reads.release();
                }
                if (json != null) {
                    docs.put(key, json);
                }
            }
            if (json != null) {
                DataNode doc = context.parseJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
                return Optional.of(new EntityDocument(ref, doc, new Provenance(sourceName, s.sequence(), Instant.now(), false,
                        LocalDate.MIN.equals(d) ? null : d)));
            }
        }
        return Optional.empty();
    }

    @Override
    public Set<String> columnar(String kind) {
        Kind k = kinds.get(kind);
        if (k == null || k.effective()) {
            return Set.of();
        }
        try {
            return snapshot(kind, null).map(s -> s.promoted().keySet()).orElse(Set.of());
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    private Columns columnSet(String kind, TableSnapshot s, LocalDate d) throws IOException {
        String key = kind + "\u001f" + d + "\u001f" + stampOf(s, d) + "\u001f" + s.version();
        Columns c = columnSets.getIfPresent(key);
        if (c != null) {
            return c;
        }
        Map<String, ColumnType> types = s.columns().types();
        List<ColumnAsk> asks = new ArrayList<>();
        s.promoted().forEach((path, column) -> asks.add(new ColumnAsk(path, column, numeric(types.get(column)))));
        ColumnBlock b = s.columns(d, asks);
        if (b.ids().length >= maxIndexRows) {
            partial.add(kind);
        }
        c = new Columns(b.ids(), b.numbers(), b.texts());
        columnSets.put(key, c);
        return c;
    }

    private static boolean numeric(ColumnType t) {
        return t == ColumnType.DOUBLE || t == ColumnType.LONG || t == ColumnType.INT;
    }

    @Override
    public Optional<ColumnSet> columns(String kind, Collection<String> paths, AsOf asOf) {
        Kind k = kinds.get(kind);
        if (k == null || k.effective()) {
            return Optional.empty();
        }
        Optional<TableSnapshot> o = snapshot(kind, asOf.knownAt());
        if (o.isEmpty() || !o.get().promoted().keySet().containsAll(paths)) {
            return Optional.empty();
        }
        List<LocalDate> days = candidates(kind, o.get(), asOf.businessDate());
        if (days.isEmpty()) {
            return Optional.empty();
        }
        LocalDate d = days.get(0);
        try {
            Columns all = columnSet(kind, o.get(), d);
            Map<String, double[]> nums = new LinkedHashMap<>();
            Map<String, String[]> texts = new LinkedHashMap<>();
            for (String p : paths) {
                if (all.numbers().containsKey(p)) {
                    nums.put(p, all.numbers().get(p));
                } else {
                    texts.put(p, all.texts().get(p));
                }
            }
            return Optional.of(new ColumnSet(all.ids(), nums, texts, LocalDate.MIN.equals(d) ? null : d));
        } catch (IOException | RuntimeException e) {
            throw LakeReads.failure(kind, d.toString(), e);
        }
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind) {
        return reverse(target, kind, AsOf.LATEST);
    }

    /** Entities whose link columns ({@code links}: column to kind) name {@code target}, from the columns alone. */
    @Override
    public List<EntityRef> reverse(EntityRef target, String kind, AsOf asOf) {
        List<EntityRef> out = new ArrayList<>();
        for (String name : kind == null ? kinds.keySet() : Set.of(kind)) {
            Kind k = kinds.get(name);
            if (k == null) {
                continue;
            }
            List<String> cols = new ArrayList<>();
            k.shape().links().forEach((column, linked) -> {
                if (linked.equals(target.kind())) {
                    cols.add(column);
                }
            });
            if (cols.isEmpty()) {
                continue;
            }
            snapshot(name, asOf.knownAt()).ifPresent(s -> {
                Set<String> found = new TreeSet<>();
                Set<String> seen = new HashSet<>();
                try {
                    for (LocalDate d : candidates(name, s, asOf.businessDate())) {
                        Columns c = columnSet(name, s, d);
                        for (int i = 0; i < c.ids().length; i++) {
                            if (!seen.add(c.ids()[i])) {
                                continue;
                            }
                            for (String col : cols) {
                                String[] values = c.texts().get(col);
                                double[] nums = c.numbers().get(col);
                                if (values != null && target.id().equals(values[i])
                                        || nums != null && !Double.isNaN(nums[i]) && target.id().equals(plain(nums[i]))) {
                                    found.add(c.ids()[i]);
                                    break;
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    throw LakeReads.failure(name, "columns", e);
                }
                found.forEach(id -> out.add(EntityRef.of(name, id)));
            });
        }
        return out;
    }

    private static String plain(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return index.search(kind, text, limit);
    }

    @Override
    public Instant lastUpdate() {
        return lastUpdate;
    }

    @Override
    public Map<String, Object> cacheStats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tables", kinds.size());
        m.put("snapshots", current.size());
        m.put("idIndexes", idMaps.estimatedSize());
        m.put("columnSets", columnSets.estimatedSize());
        m.put("documents", docs.estimatedSize());
        m.put("ids", index.size());
        if (!partial.isEmpty()) {
            m.put("partial", new TreeSet<>(partial).toString());
        }
        return m;
    }

    @Override
    public void purgeCaches() {
        idMaps.invalidateAll();
        columnSets.invalidateAll();
        docs.invalidateAll();
        lake.purge();
    }

    @Override
    public String health() {
        if (kinds.isEmpty()) {
            return "DOWN: no table served" + (problems.isEmpty() ? "" : ": " + String.join("; ", new TreeSet<>(problems.values())));
        }
        List<String> notes = new ArrayList<>(new TreeSet<>(problems.values()));
        bigIndexes.forEach((k, n) -> notes.add(k + ": the id index of one day holds " + n + " ids; sort the table by its id (a sort order or bucket(id))"
                + " so one entity reads one row group"));
        if (!partial.isEmpty()) {
            notes.add("partial (max-index-rows): " + String.join(", ", new TreeSet<>(partial)));
        }
        lake.problems().all().stream().map(p -> p.table() + ": " + p.reason()).sorted().forEach(notes::add);
        if (!problems.isEmpty() || !lake.problems().all().isEmpty()) {   // a configured table not served (missing, no id): the connector is degraded (QA2-203)
            return "DEGRADED: " + String.join("; ", notes);
        }
        return notes.isEmpty() ? "UP" : "UP (" + String.join("; ", notes) + ")";
    }

    /** Kinds not served and why (a table with no id, a table missing): Admin → Connectors lists them. */
    public Map<String, String> notServed() {
        return Map.copyOf(problems);
    }

    /** The served kinds' starter-pack schemas ({@link StarterSchemas}), for {@code drishti.py pack make --schema}. */
    public Map<String, com.fasterxml.jackson.databind.node.ObjectNode> starterSchemas() {
        Map<String, com.fasterxml.jackson.databind.node.ObjectNode> out = new LinkedHashMap<>();
        kinds.forEach((kind, k) -> snapshot(kind, null).ifPresent(s -> out.put(kind, StarterSchemas.of(k.shape(), s))));
        return out;
    }

    @Override
    public void close() {
        if (lake != null) {
            lake.close();
        }
    }
}
