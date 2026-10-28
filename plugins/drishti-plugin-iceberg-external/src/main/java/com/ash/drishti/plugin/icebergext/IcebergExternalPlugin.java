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
import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.iceberg.catalog.Catalogs;
import com.ash.drishti.iceberg.read.JsonValues;
import com.ash.drishti.plugin.icebergext.ExternalTable.Day;
import com.ash.drishti.plugin.icebergext.ExternalTable.Layout;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serves entities from Iceberg tables Drishti does not write (ICEBERG_EXTERNAL_CONNECTOR.md): tables of Spark, Flink,
 * Trino, Snowflake, Glue, dbt or pyiceberg, in their own layout, read through drishti-iceberg (no Hadoop) and never
 * written. A kind is a table ({@code tables.<kind>.table}); its document is the row as JSON (every column; structs as
 * objects, lists as arrays, maps as objects), or a JSON column; its id is one column or several joined
 * ({@code id}, by default the table's identifier fields); its business date a date column, a timestamp's day
 * ({@code date-from: day}, {@code date-zone}), by default the partition spec's date, or none (current only). Every
 * primitive column (nested struct fields to {@code depth}, 2) is searchable without a pack declaring it; columns named
 * in {@code links} make reverse lookups. {@code tables: auto} with {@code namespace} serves every table of a namespace,
 * named after it, with its identifier fields and partition date.
 *
 * <p>One entity: the day's files pruned with the id (a table sorted or bucketed by its id reads one row group); for an
 * unsorted table, an id index built on first use per snapshot and day ({@code id-index-mb}, 1024; Health suggests
 * sorting when one day passes {@code id-index-warn-rows}, 5,000,000). Type-ahead from the newest day's ids (every
 * day's for {@code effective} kinds); a table without a date bounded by {@code max-index-rows} (10,000,000), marked
 * partial beyond it. Time travel by {@code knownAt} through the snapshot log. Settings: the catalog's, as the
 * {@code iceberg} connector's (section 15.4); {@code decimals: number|string}, {@code binary: base64|omit},
 * {@code refresh-seconds} (10), {@code lookback-days} (10), {@code doc-cache-mb} (256), {@code columns-cache-mb} (1024),
 * {@code max-concurrent-reads} (16), {@code source-name} ({@code iceberg-external}).
 */
public final class IcebergExternalPlugin implements SourcePlugin {

    private record SnapKey(String kind, long snapshotId) {}

    private record DayKey(String kind, LocalDate date, long fingerprint) {}

    private record Columns(String[] ids, Map<String, double[]> numbers, Map<String, String[]> texts) {
        long bytes() {
            long b = 64L + 48L * ids.length;
            for (String id : ids) {
                b += id.length();
            }
            return b + 8L * ids.length * (numbers.size() + texts.size());
        }
    }

    private final Map<String, ExternalTable> tables = new ConcurrentHashMap<>();
    private final Map<String, Long> current = new ConcurrentHashMap<>();
    private final Map<String, String> problems = new ConcurrentHashMap<>();
    private final Map<String, Long> bigIndexes = new ConcurrentHashMap<>();
    private final Map<List<String>, Catalog> catalogs = new ConcurrentHashMap<>();
    private final HitIndex index = new HitIndex();
    private final Set<String> partial = ConcurrentHashMap.newKeySet();
    private final ExecutorService loaders = Executors.newVirtualThreadPerTaskExecutor();
    private final ReentrantLock refreshing = new ReentrantLock();
    private AsyncCache<SnapKey, Layout> layouts;
    private AsyncCache<DayKey, Map<String, int[]>> idIndexes;
    private AsyncCache<String, Columns> columnSets;
    private Cache<String, String> docs;
    private com.ash.drishti.api.ReadSlots reads;      // bounded queue; a read whose caller has gone leaves it (QA3A-01)
    private SourceContext context;
    private Map<String, String> settings;
    private String sourceName;
    private int lookbackDays;
    private long maxIndexRows;
    private long warnRows;
    private JsonValues.Options options;
    private volatile Instant lastUpdate;

    @Override
    public PluginManifest manifest() {
        return new PluginManifest(sourceName == null ? "iceberg-external" : sourceName, "1.0", tables.keySet(), new SourceCapabilities(false, true, true, true));
    }

    @Override
    public boolean timeTravel() {
        return true;
    }

    @Override
    public void start(SourceContext ctx) throws IOException {
        if (ctx.setting("root", "").isBlank() && ctx.setting("uri", "").isBlank() && ctx.settings().keySet().stream().noneMatch(k -> k.startsWith("metadata-location."))) {
            throw new com.ash.drishti.api.PluginNotConfigured("iceberg-external needs settings.root, settings.uri (a REST catalog) or metadata-location.<kind>");
        }
        String engine = ctx.setting("engine", System.getenv().getOrDefault("DRISHTI_ICEBERG_ENGINE", "auto")).trim().toLowerCase(Locale.ROOT);
        if (engine.equals("library")) {
            throw new IllegalArgumentException("iceberg-external reads through drishti-iceberg (engine: native or auto); engine: library is the "
                    + "iceberg connector's only. For tables in Azure, Google Cloud Storage or HDFS put a REST catalog with S3 in front, or use the iceberg connector");
        }
        if (!engine.equals("native") && !engine.equals("auto")) {
            throw new IllegalArgumentException("unknown Iceberg engine '" + engine + "': use native or auto");
        }
        this.context = ctx;
        this.settings = Map.copyOf(ctx.settings());
        this.sourceName = ctx.setting("source-name", "iceberg-external");
        this.lookbackDays = Integer.parseInt(ctx.setting("lookback-days", "10"));
        this.maxIndexRows = Long.parseLong(ctx.setting("max-index-rows", "10000000"));
        this.warnRows = Long.parseLong(ctx.setting("id-index-warn-rows", "5000000"));
        this.options = new JsonValues.Options("string".equalsIgnoreCase(ctx.setting("decimals", "number")), "omit".equalsIgnoreCase(ctx.setting("binary", "base64")));
        this.layouts = Caffeine.newBuilder().executor(loaders).maximumSize(64).buildAsync();
        this.idIndexes = Caffeine.newBuilder().executor(loaders).maximumWeight(mb("id-index-mb", "1024"))
                .weigher((DayKey k, Map<String, int[]> v) -> (int) Math.min(Integer.MAX_VALUE, 64L + 60L * v.size())).buildAsync();
        this.columnSets = Caffeine.newBuilder().executor(loaders).maximumWeight(mb("columns-cache-mb", "1024"))
                .weigher((String k, Columns v) -> (int) Math.min(Integer.MAX_VALUE, v.bytes())).buildAsync();
        this.docs = Caffeine.newBuilder().maximumWeight(mb("doc-cache-mb", "256")).weigher((String k, String v) -> 2 * (k.length() + v.length()) + 64).build();
        this.reads = new com.ash.drishti.api.ReadSlots("document reads of " + ctx.setting("source-name", "iceberg-external"),
                Integer.parseInt(ctx.setting("max-concurrent-reads", "16")), Integer.parseInt(ctx.setting("max-queued-reads", "4096")));
        refresh();
        long every = Long.parseLong(ctx.setting("refresh-seconds", "10"));
        ctx.scheduler().scheduleWithFixedDelay(() -> loaders.execute(this::refresh), every, every, TimeUnit.SECONDS);
    }

    private long mb(String key, String fallback) {
        return Long.parseLong(context.setting(key, fallback)) * 1024 * 1024;
    }

    /** The catalog of one namespace (REST: the namespace; path-based: the folder {@code <root>/<levels…>}). */
    private Catalog catalog(List<String> namespace) {
        return catalogs.computeIfAbsent(namespace, ns -> {
            Map<String, String> s = new HashMap<>(settings);
            s.remove("domain");
            if ("rest".equalsIgnoreCase(s.getOrDefault("catalog", ""))) {
                s.put("namespace", String.join(".", ns));
            } else {
                s.put("domain", String.join("/", ns));
            }
            return Catalogs.open(s);
        });
    }

    /** The kinds configured, and (tables: auto) the namespace's tables. */
    private List<KindConfig> kinds() throws IOException {
        List<KindConfig> out = new ArrayList<>(KindConfig.from(settings));
        Set<String> metadataKinds = new TreeSet<>();
        settings.keySet().forEach(k -> {
            if (k.startsWith("metadata-location.")) {
                metadataKinds.add(k.substring("metadata-location.".length()));
            }
        });
        for (String k : metadataKinds) {
            if (out.stream().noneMatch(c -> c.kind().equals(k))) {
                out.add(KindConfig.of(k, settings, "tables." + k + ".", k));
            }
        }
        if ("auto".equalsIgnoreCase(settings.getOrDefault("tables", "").trim())) {
            String ns = settings.getOrDefault("namespace", "");
            List<String> levels = ns.isBlank() ? List.of() : List.of(ns.split("\\."));
            for (String t : catalog(levels).tables()) {
                String kind = KindConfig.kindOf(t);
                if (out.stream().noneMatch(c -> c.kind().equals(kind))) {
                    out.add(KindConfig.of(kind, settings, "tables." + kind + ".", ns.isBlank() ? t : ns + "." + t));
                }
            }
        }
        return out;
    }

    private Optional<Table> open(KindConfig k) throws IOException {
        if (settings.containsKey("metadata-location." + k.kind())) {
            return catalog(List.of()).load(k.kind());
        }
        return catalog(k.namespace()).load(k.tableName());
    }

    /** Why a kind's table was not found: its catalog's account of what it looked for (a path-based one names the metadata files). */
    private String missing(KindConfig k) {
        try {
            String named = settings.containsKey("metadata-location." + k.kind()) ? k.kind() : k.tableName();
            String why = catalog(settings.containsKey("metadata-location." + k.kind()) ? List.of() : k.namespace()).missing(named);
            return why.startsWith("no table " + named) ? "no table " + k.table() + why.substring(("no table " + named).length()) : why;
        } catch (RuntimeException e) {
            return "no table " + k.table();
        }
    }

    void refresh() {
        if (!refreshing.tryLock()) {
            return;
        }
        try {
            List<KindConfig> kinds;
            try {
                kinds = kinds();
            } catch (IOException | RuntimeException e) {
                problems.put("(catalog)", "cannot list the tables: " + e.getMessage());
                return;
            }
            problems.remove("(catalog)");
            for (KindConfig k : kinds) {
                if (tables.containsKey(k.kind())) {
                    continue;
                }
                try {
                    Optional<Table> t = open(k);
                    if (t.isEmpty()) {                          // Health names the files looked for and what is there instead (QA2-203)
                        problems.put(k.kind(), k.kind() + ": " + missing(k));
                        continue;
                    }
                    ExternalTable et = new ExternalTable(k, t.get(), loaders, options);
                    String problem = t.get().currentSnapshot().map(s -> Shape.of(k, t.get().metadata(), t.get().metadata().schemaFor(s)).problem(k))
                            .orElse(null);
                    if (problem != null) {
                        problems.put(k.kind(), problem);
                        continue;
                    }
                    problems.remove(k.kind());
                    tables.put(k.kind(), et);
                } catch (IOException | RuntimeException e) {
                    problems.put(k.kind(), k.kind() + ": " + e.getMessage());
                }
            }
            boolean changed = false;
            for (Map.Entry<String, ExternalTable> e : tables.entrySet()) {
                try {
                    Optional<Long> id = e.getValue().refresh();
                    if (id.isPresent() && !id.get().equals(current.get(e.getKey()))) {
                        current.put(e.getKey(), id.get());
                        changed = true;
                        Instant committed = Instant.ofEpochMilli(layout(e.getKey(), null).map(Layout::timestampMs).orElse(0L));
                        if (lastUpdate == null || committed.isAfter(lastUpdate)) {
                            lastUpdate = committed;
                        }
                    }
                } catch (RuntimeException ex) {
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
        for (String kind : tables.keySet()) {
            try {
                layout(kind, null).ifPresent(l -> {
                    if (l.days().isEmpty()) {
                        return;
                    }
                    String subtitle = kind + " · " + sourceName;
                    Collection<Day> days = tables.get(kind).config().effective() ? l.days().values() : List.of(l.days().lastEntry().getValue());
                    Set<String> seen = new HashSet<>();
                    for (Day d : days) {
                        Map<String, int[]> ids = idIndex(kind, l, d);
                        if (ids.size() >= maxIndexRows) {
                            partial.add(kind);
                        }
                        for (String id : ids.keySet()) {
                            if (seen.add(id)) {
                                hits.add(new EntityHit(EntityRef.of(kind, id), id, subtitle));
                            }
                        }
                    }
                });
            } catch (RuntimeException e) {
                // indexed next time
            }
        }
        index.replaceAll(hits);
    }

    private Optional<Layout> layout(String kind, Instant knownAt) {
        ExternalTable t = tables.get(kind);
        if (t == null) {
            return Optional.empty();
        }
        Optional<Long> snap;
        if (knownAt == null) {
            Long id = current.get(kind);
            snap = id != null ? Optional.of(id) : t.current();
        } else {
            snap = t.snapshotAt(knownAt.toEpochMilli());
        }
        return snap.map(id -> join(layouts.get(new SnapKey(kind, id), k -> t.layout(id))));
    }

    private Map<String, int[]> idIndex(String kind, Layout l, Day d) {
        Map<String, int[]> ids = join(idIndexes.get(new DayKey(kind, d.date(), d.fingerprint()), k -> tables.get(kind).ids(l, d, maxIndexRows)));
        if (ids.size() > warnRows) {
            bigIndexes.put(kind, (long) ids.size());
        }
        return ids;
    }

    private static <T> T join(CompletableFuture<T> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    /** The days to look in for {@code date}, newest first (current-only kinds have one). */
    private List<Day> candidates(String kind, Layout l, LocalDate date) {
        boolean effective = tables.get(kind).config().effective();
        if (l.days().containsKey(LocalDate.MIN) && l.days().size() == 1) {
            return List.of(l.days().get(LocalDate.MIN));
        }
        var head = date == null ? l.days().descendingMap() : l.days().headMap(date, true).descendingMap();
        List<Day> out = new ArrayList<>();
        for (Day d : head.values()) {
            if (!effective && date != null && d.date().isBefore(date.minusDays(lookbackDays))) {
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
        Optional<Layout> l = layout(ref.kind(), asOf.knownAt());
        if (l.isEmpty()) {
            return Optional.empty();
        }
        ExternalTable t = tables.get(ref.kind());
        Optional<List<Object>> values = t.parse(l.get().shape(), ref.id());
        if (values.isEmpty()) {
            return Optional.empty();
        }
        for (Day d : candidates(ref.kind(), l.get(), asOf.businessDate())) {
            String key = ref.kind() + "\u001f" + d.fingerprint() + "\u001f" + d.date() + "\u001f" + ref.id();
            String json = docs.getIfPresent(key);
            if (json == null) {
                List<Integer> files = t.candidates(l.get(), d, t.idFilter(l.get().shape(), values.get()));
                if (files.size() > 2) {
                    int[] known = idIndex(ref.kind(), l.get(), d).get(ref.id());   // an unsorted table: the day's id index names the file
                    files = new ArrayList<>();
                    if (known != null) {
                        for (int f : known) {
                            files.add(f);
                        }
                    }
                }
                if (files.isEmpty()) {
                    continue;
                }
                reads.acquire();
                try {
                    json = t.fetch(l.get(), d, ref.id(), values.get(), files).orElse(null);
                } finally {
                    reads.release();
                }
                if (json != null) {
                    docs.put(key, json);
                }
            }
            if (json != null) {
                DataNode doc = context.parseJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
                return Optional.of(new EntityDocument(ref, doc, new Provenance(sourceName, l.get().sequenceNumber(), Instant.now(), false,
                        LocalDate.MIN.equals(d.date()) ? null : d.date())));
            }
        }
        return Optional.empty();
    }

    @Override
    public Set<String> columnar(String kind) {
        ExternalTable t = tables.get(kind);
        if (t == null || t.config().effective()) {
            return Set.of();
        }
        try {
            return layout(kind, null).map(l -> l.shape().searchable().keySet()).orElse(Set.of());
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    private Columns columnSet(String kind, Layout l, Day d) {
        Set<String> paths = l.shape().searchable().keySet();
        String key = kind + "\u001f" + d.date() + "\u001f" + d.fingerprint();
        return join(columnSets.get(key, k -> {
            List<String> ps = new ArrayList<>(paths);
            List<Object[]> rows = tables.get(kind).columns(l, d, ps, maxIndexRows);
            if (rows.size() >= maxIndexRows) {
                partial.add(kind);
            }
            String[] ids = new String[rows.size()];
            Map<String, double[]> nums = new LinkedHashMap<>();
            Map<String, String[]> texts = new LinkedHashMap<>();
            for (int c = 0; c < ps.size(); c++) {
                if (ExternalTable.numeric(l.shape().searchable().get(ps.get(c)).type())) {
                    nums.put(ps.get(c), new double[rows.size()]);
                } else {
                    texts.put(ps.get(c), new String[rows.size()]);
                }
            }
            for (int i = 0; i < rows.size(); i++) {
                Object[] r = rows.get(i);
                ids[i] = (String) r[0];
                for (int c = 0; c < ps.size(); c++) {
                    Object v = r[c + 1];
                    double[] n = nums.get(ps.get(c));
                    if (n != null) {
                        n[i] = v == null ? Double.NaN : (Double) v;
                    } else {
                        texts.get(ps.get(c))[i] = (String) v;
                    }
                }
            }
            return new Columns(ids, nums, texts);
        }));
    }

    @Override
    public Optional<ColumnSet> columns(String kind, Collection<String> paths, AsOf asOf) {
        ExternalTable t = tables.get(kind);
        if (t == null || t.config().effective()) {
            return Optional.empty();
        }
        Optional<Layout> l = layout(kind, asOf.knownAt());
        if (l.isEmpty() || !l.get().shape().searchable().keySet().containsAll(paths)) {
            return Optional.empty();
        }
        List<Day> days = candidates(kind, l.get(), asOf.businessDate());
        if (days.isEmpty()) {
            return Optional.empty();
        }
        Day d = days.get(0);
        Columns all = columnSet(kind, l.get(), d);
        Map<String, double[]> nums = new LinkedHashMap<>();
        Map<String, String[]> texts = new LinkedHashMap<>();
        for (String p : paths) {
            if (all.numbers().containsKey(p)) {
                nums.put(p, all.numbers().get(p));
            } else {
                texts.put(p, all.texts().get(p));
            }
        }
        return Optional.of(new ColumnSet(all.ids(), nums, texts, LocalDate.MIN.equals(d.date()) ? null : d.date()));
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind) {
        return reverse(target, kind, AsOf.LATEST);
    }

    /** Entities whose link columns ({@code links}: column to kind) name {@code target}, from the columns alone. */
    @Override
    public List<EntityRef> reverse(EntityRef target, String kind, AsOf asOf) {
        List<EntityRef> out = new ArrayList<>();
        for (String k : kind == null ? tables.keySet() : Set.of(kind)) {
            ExternalTable t = tables.get(k);
            if (t == null) {
                continue;
            }
            List<String> cols = new ArrayList<>();
            t.config().links().forEach((column, linked) -> {
                if (linked.equals(target.kind())) {
                    cols.add(column);
                }
            });
            if (cols.isEmpty()) {
                continue;
            }
            layout(k, asOf.knownAt()).ifPresent(l -> {
                Set<String> found = new TreeSet<>();
                Set<String> seen = new HashSet<>();
                for (Day d : candidates(k, l, asOf.businessDate())) {
                    Columns c = columnSet(k, l, d);
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
                found.forEach(id -> out.add(EntityRef.of(k, id)));
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
        m.put("tables", tables.size());
        m.put("snapshots", layouts.synchronous().estimatedSize());
        m.put("idIndexes", idIndexes.synchronous().estimatedSize());
        m.put("columnSets", columnSets.synchronous().estimatedSize());
        m.put("documents", docs.estimatedSize());
        m.put("ids", index.size());
        m.put("engine", "native");
        if (!partial.isEmpty()) {
            m.put("partial", new TreeSet<>(partial).toString());
        }
        return m;
    }

    @Override
    public void purgeCaches() {
        layouts.synchronous().invalidateAll();
        idIndexes.synchronous().invalidateAll();
        columnSets.synchronous().invalidateAll();
        docs.invalidateAll();
        com.ash.drishti.lakeio.ParquetFooters.purge();
        com.ash.drishti.iceberg.scan.Planner.purge();
        com.ash.drishti.iceberg.read.Deletes.purge();
    }

    @Override
    public String health() {
        if (tables.isEmpty()) {
            return "DOWN: no table served" + (problems.isEmpty() ? "" : ": " + String.join("; ", new TreeSet<>(problems.values())));
        }
        List<String> notes = new ArrayList<>(new TreeSet<>(problems.values()));
        bigIndexes.forEach((k, n) -> notes.add(k + ": the id index of one day holds " + n + " ids; sort the table by its id (a sort order or bucket(id))"
                + " so one entity reads one row group"));
        if (!partial.isEmpty()) {
            notes.add("partial (max-index-rows): " + String.join(", ", new TreeSet<>(partial)));
        }
        if (!problems.isEmpty()) {                              // a configured table not served (missing, no id): the connector is degraded (QA2-203)
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
        tables.forEach((kind, t) -> layout(kind, null).ifPresent(l -> out.put(kind, StarterSchemas.of(t.config(), l.shape()))));
        return out;
    }

    @Override
    public void close() {
        loaders.shutdownNow();
        catalogs.values().forEach(c -> {
            try {
                c.close();
            } catch (RuntimeException e) {
                // closing a client: nothing to do
            }
        });
    }
}
