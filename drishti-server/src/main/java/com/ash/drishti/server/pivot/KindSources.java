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
package com.ash.drishti.server.pivot;

import com.ash.drishti.bicompute.core.CellKey;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.bicompute.sql.DuckDbExecutor;
import com.ash.drishti.bicompute.sql.TabularSource;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.server.data.DataSelection;
import com.ash.drishti.server.data.DataViewService;
import com.ash.drishti.server.edition.EditionCommands;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The tables the pivot engine reads (PIVOT_GUIDE.md, sources): every stored kind of the loaded packs a person may open in the Data view
 * ({@code DATA}), on any date it holds. A table is read once through its connector for a person's mask signature, date and data version
 * ({@link DataViewService#walk}: the Data view's own read, the person's masks applied to every row) and staged in the executor's DuckDB, in
 * memory; masked columns are never read, so they cannot be a dimension, a measure's input or a filter. A table over
 * {@code drishti.pivot.stage-max-rows} rows is refused in words, never cut short.
 */
public final class KindSources implements TabularSource.Resolver {

    private static final Set<String> NUMBER = Set.of("amount", "integer", "rate", "number", "decimal", "double", "long", "float");
    private static final Set<String> DATE = Set.of("date", "datetime", "timestamp");

    private final DataViewService data;
    private final SourceRouter router;
    private final Entitlements entitlements;
    private final EditionCommands edition;
    private final PivotProperties props;
    private final com.ash.drishti.engine.search.SearchProperties search;
    /** Staged table name to its row count, in least-recently-used order. */
    private final Map<String, Long> staged = java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true));
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private volatile DuckDbExecutor exec;
    /** Per source and data version: the document fields a sample of its documents shows (PIVOT_ENGINE.md section 6.7). */
    private final Map<String, Map<String, DocPath>> shapes = new ConcurrentHashMap<>();
    /** Per source: how the last document view read its fields (the provenance's route words). */
    private final Map<String, String> lastRoute = new ConcurrentHashMap<>();
    /** Per source: the paths people typed (fields beyond the sampled list), and how often each was used, for the administrator. */
    private final Map<String, Map<String, Typed>> typed = new ConcurrentHashMap<>();
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** A typed path and its use. */
    static final class Typed {
        final DocPath path;
        final java.util.concurrent.atomic.AtomicLong uses = new java.util.concurrent.atomic.AtomicLong();
        volatile String last = "";

        Typed(DocPath path) {
            this.path = path;
        }
    }

    private volatile DatasetLayers layers;

    /** The BI datasets whose cached layers hold a document list's fields (the dataset route). */
    void layers(DatasetLayers l) {
        this.layers = l;
    }

    /** How many documents the field list samples. */
    static final int SAMPLE = 1_000;

    public KindSources(DataViewService data, SourceRouter router, Entitlements entitlements, EditionCommands edition, PivotProperties props,
            com.ash.drishti.engine.search.SearchProperties search) {
        this.search = search;
        this.data = data;
        this.router = router;
        this.entitlements = entitlements;
        this.edition = edition;
        this.props = props;
    }

    static Principal principal(Viewer v) {
        if (v.principal() instanceof Principal p) {
            return p;
        }
        throw new DrishtiException(ErrorCode.FORBIDDEN, "the pivot engine needs a signed-in person");
    }

    private boolean visible(Principal p, String kind) {
        return entitlements.mayOpen(p, kind) && edition.kindShown(kind);
    }

    @Override
    public Map<String, String> sources(Viewer v) {
        Principal p = principal(v);
        Map<String, String> out = new LinkedHashMap<>();
        for (DataViewService.Dataset d : data.datasets()) {
            if (visible(p, d.kind())) {
                out.put(d.kind(), d.title() == null || d.title().isBlank() ? d.kind() : d.title());
            }
        }
        return out;
    }

    @Override
    public boolean serves(String source) {
        return data.datasets().stream().anyMatch(d -> d.kind().equals(source));
    }

    static String type(DataViewService.Column c) {
        String t = c.type() == null ? "" : c.type().toLowerCase(Locale.ROOT);
        if (NUMBER.contains(t)) {
            return "number";
        }
        if (DATE.contains(t) || t.isEmpty() && c.name().toUpperCase(Locale.ROOT).endsWith("_DATE")) {
            return "date";
        }
        return "string";
    }

    /**
     * What a column's values are (PIVOT_ENGINE.md section 5.4), from its contract type and name: an {@code amount} is money in the row's
     * currency, a {@code rate} or a name ending {@code _PCT} or {@code PERCENT} is never summed, a text column named {@code CURRENCY},
     * {@code CCY} or ending {@code _CCY} or {@code _CURRENCY} holds the row's currency.
     */
    static String unit(DataViewService.Column c) {
        String t = c.type() == null ? "" : c.type().toLowerCase(Locale.ROOT);
        String n = c.name().toUpperCase(Locale.ROOT);
        if ("amount".equals(t)) {
            return "money";
        }
        if ("rate".equals(t)) {
            return "rate";
        }
        if (n.endsWith("_PCT") || n.endsWith("PERCENT")) {
            return "pct";
        }
        if (!NUMBER.contains(t) && (n.equals("CURRENCY") || n.equals("CCY") || n.endsWith("_CCY") || n.endsWith("_CURRENCY"))) {
            return "currency";
        }
        return "";
    }

    @Override
    public TabularSource open(Viewer v, String source, String date) {
        Principal p = principal(v);
        DataViewService.Dataset d = data.dataset(source);
        entitlements.requireOpen(p, source);
        edition.requireKind(source);
        LocalDate day = day(d, date);
        List<PivotProvider.Field> fields = new ArrayList<>();
        List<String> maskedNames = new ArrayList<>();
        for (DataViewService.Column c : d.columns()) {
            if (entitlements.masked(p, c.name())) {
                maskedNames.add(c.name());
                continue;
            }
            fields.add(new PivotProvider.Field(c.name(), type(c), c.name().toLowerCase(Locale.ROOT).replace('_', ' '),
                    c.description() == null ? "" : c.description(), unit(c)));
        }
        String version;
        try {
            version = router.dataVersion(source, day == null ? com.ash.drishti.api.AsOf.LATEST : com.ash.drishti.api.AsOf.of(day)).orElse("unversioned");
        } catch (RuntimeException e) {
            version = "unversioned";
        }
        String masks = entitlements.masks(p) ? "m" + Integer.toHexString(String.join(",", maskedNames).hashCode()) + "-" + p.user() : "raw";
        String fullVersion = version + "|" + (day == null ? "current" : day) + "|" + masks;
        String table = "pv_" + Integer.toHexString((source + "|" + fullVersion).hashCode()).replace('-', 'n');
        List<String> dates = d.dated() ? data.dates(source).stream().map(LocalDate::toString).toList() : List.of();
        boolean memoryOnly = com.ash.drishti.server.memlake.Retention.current().memoryOnly(source);
        com.ash.drishti.api.LakeFiles lake = memoryOnly ? null : lake(d, day, fields, p);
        long estimate = -1;
        if (lake == null) {
            try {
                estimate = router.rowEstimate(source, day).map(com.ash.drishti.api.RowEstimate::rows).orElse(-1L);
            } catch (RuntimeException e) {
                estimate = -1;
            }
        }
        java.util.Set<String> promoted = new java.util.LinkedHashSet<>();
        fields.forEach(f -> promoted.add(f.name()));
        Map<String, DocPath> docs = new LinkedHashMap<>(shape(d, day, p, fullVersion, promoted));
        typed.getOrDefault(source, Map.of()).forEach((k, t) -> docs.putIfAbsent(t.path.id(), t.path));
        Map<String, String> docTypes = new LinkedHashMap<>();
        for (DocPath dp : docs.values()) {
            String type = dp.size() ? "number" : shapeType(source, fullVersion, dp.text());
            docTypes.put(dp.id(), type);
            fields.add(new PivotProvider.Field(dp.id(), type, dp.label(), "path " + dp.text() + "; one value per " + DocView.words(dp.grain())
                    + (dp.size() ? " (a count)" : ""), ""));
        }
        if (!docs.isEmpty()) {
            fields.addAll(DocView.engineFields(docs, docTypes));
        }
        return new Kind(d, day, fields, maskedNames.size(), fullVersion, table, dates, memoryOnly, p, lake, estimate, docs, promoted);
    }

    /**
     * The direct lake route (PIVOT_ENGINE.md section 6.2): the date's Parquet files when the kind's one source can hand them over and every
     * field the person may use is a column of them; masks are then applied as columns not read (a masked column is not in the relation),
     * unless masking also changes values inside other fields ({@link Entitlements#masksValues}), when the rows go through the Data view's
     * read one by one. Null: the staged route.
     */
    private com.ash.drishti.api.LakeFiles lake(DataViewService.Dataset d, LocalDate day, List<PivotProvider.Field> fields, Principal p) {
        if (entitlements.masksValues(p)) {
            return null;
        }
        java.util.Optional<com.ash.drishti.api.LakeFiles> f;
        try {
            f = router.lakeFiles(d.kind(), day == null ? com.ash.drishti.api.AsOf.LATEST : com.ash.drishti.api.AsOf.of(day));
        } catch (RuntimeException e) {
            return null;
        }
        if (f.isEmpty() || f.get().files().isEmpty()) {
            return null;
        }
        for (PivotProvider.Field field : fields) {
            if (!f.get().columns().containsKey(field.name()) && !field.name().equals(d.key())) {
                return null;                                   // a field that is not a column: the connector's read builds it
            }
        }
        return f.get();
    }

    /** The day to read: the one asked (one the dataset holds), else its latest; null for an undated dataset. */
    private LocalDate day(DataViewService.Dataset d, String date) {
        if (!d.dated()) {
            return null;
        }
        List<LocalDate> held = data.dates(d.kind());
        if (date == null || date.isBlank()) {
            return held.isEmpty() ? null : held.get(held.size() - 1);
        }
        LocalDate day;
        try {
            day = LocalDate.parse(date.strip());
        } catch (DateTimeParseException e) {
            throw new DrishtiException(ErrorCode.BAD_BUSINESS_DATE, "not a date: '" + date + "' (write 2026-09-30)");
        }
        if (!held.isEmpty() && !held.contains(day)) {
            throw new DrishtiException(ErrorCode.BAD_BUSINESS_DATE, d.kind() + " holds no data on " + day + ": its dates run from " + held.get(0)
                    + " to " + held.get(held.size() - 1));
        }
        return day;
    }

    /** One table opened for one person on one date. */
    private final class Kind implements TabularSource {
        final DataViewService.Dataset d;
        final LocalDate day;
        final List<PivotProvider.Field> fields;
        final int masked;
        final String version;
        final String table;
        final List<String> dates;
        final boolean memoryOnly;
        final Principal p;
        final com.ash.drishti.api.LakeFiles lake;
        final long estimate;
        final Map<String, DocPath> docs;
        final java.util.Set<String> promoted;

        Kind(DataViewService.Dataset d, LocalDate day, List<PivotProvider.Field> fields, int masked, String version, String table,
                List<String> dates, boolean memoryOnly, Principal p, com.ash.drishti.api.LakeFiles lake, long estimate, Map<String, DocPath> docs,
                java.util.Set<String> promoted) {
            this.docs = docs;
            this.promoted = promoted;
            this.lake = lake;
            this.estimate = estimate;
            this.d = d;
            this.day = day;
            this.fields = fields;
            this.masked = masked;
            this.version = version;
            this.table = table;
            this.dates = dates;
            this.memoryOnly = memoryOnly;
            this.p = p;
        }

        @Override
        public String id() {
            return d.kind();
        }

        @Override
        public String title() {
            return d.title() == null || d.title().isBlank() ? d.kind() : d.title();
        }

        @Override
        public List<PivotProvider.Field> fields() {
            return fields;
        }

        @Override
        public int masked() {
            return masked;
        }

        @Override
        public String date() {
            return day == null ? null : day.toString();
        }

        @Override
        public List<String> dates() {
            return dates;
        }

        @Override
        public String version() {
            return version;
        }

        @Override
        public long rows() {
            if (lake != null) {
                return lake.rows();
            }
            Long n = staged.get(table);
            return n == null ? estimate : n;
        }

        @Override
        public long bytes() {
            if (lake != null) {
                return lake.bytes();
            }
            long r = rows();
            return r < 0 ? -1 : r * Math.max(1, fields.size()) * 12L;
        }

        @Override
        public Map<String, Object> notes() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("route", lake != null ? "read directly from the " + lake.format() + " table's " + lake.files().size() + " Parquet file(s) of "
                    + lake.businessDate() + " by the pivot executor's DuckDB: only the columns the view needs, row groups skipped by their statistics"
                    : "read through the " + d.format() + " connector (" + d.connector() + ") for you, staged in the pivot executor's DuckDB "
                    + "in memory" + (memoryOnly ? "; memory-only data, never written to a disk" : ""));
            m.put("pack", d.pack());
            if (sampled.containsKey(d.kind())) {
                m.put("documentFields", sampled.get(d.kind()));
            }
            if (lastRoute.containsKey(d.kind())) {
                m.put("documents", "document fields read through " + lastRoute.get(d.kind()));
            }
            m.put("masks", masked == 0 ? "none" : masked + " column(s) masked for you: not read, not usable");
            m.put("rowRules", "the Data view's: you see the rows of " + d.kind() + " your role may open");
            Map<String, Object> dv = defaultView(d.kind(), date());
            if (dv != null) {
                m.put("defaultView", dv);
            }
            return m;
        }

        private DocView docView() {
            return new DocView(docs, promoted, text -> typed(d.kind(), text));
        }

        @Override
        public com.ash.drishti.bicompute.core.PivotView prepare(com.ash.drishti.bicompute.core.PivotView view) {
            return docView().prepare(view);
        }

        /** True when the plan reads a document path (or a parent key): the documents are staged and unnested for it. */
        private boolean readsDocuments(com.ash.drishti.bicompute.core.AggregationPlan plan) {
            java.util.function.Predicate<String> doc = id -> docs.containsKey(id) || id.endsWith(DocView.ALL) || id.startsWith("_p")
                    || DocView.GRAIN.equals(id);
            return plan.dims().values().stream().anyMatch(x -> doc.test(x.field()))
                    || plan.functions().stream().anyMatch(f -> f.inputs().stream().anyMatch(doc));
        }

        @Override
        public Relation relation(DuckDbExecutor e, com.ash.drishti.bicompute.core.AggregationPlan plan) {
            if (!readsDocuments(plan)) {
                return relation(e);
            }
            exec = e;
            String docTable = table + "_d";
            Object lock = locks.computeIfAbsent(docTable, k -> new Object());
            synchronized (lock) {
                if (!staged.containsKey(docTable) || !e.exists(docTable)) {
                    stageDocuments(e, docTable);
                }
                staged.get(docTable);
            }
            Map<String, String> types = new LinkedHashMap<>();
            fields.forEach(f -> types.put(f.name(), f.type()));
            List<String> names = new ArrayList<>(fields.stream().map(PivotProvider.Field::name).toList());
            plan.dims().values().stream().map(com.ash.drishti.bicompute.core.Dim::field).filter(f -> f.endsWith(DocView.ALL) && !names.contains(f))
                    .forEach(names::add);
            DatasetLayers dl = layers;
            // the dataset route only for a reader without masks: the cached layer holds the values as stored
            java.util.function.Function<String, DocView.Layer> fromLayers = dl == null || entitlements.masks(p) ? g -> null : g -> dl.layer(d.kind(), g, day);
            DocView v = docView();
            String sql = v.relation(DuckDbExecutor.quote(docTable), plan, names, types, fromLayers);
            lastRoute.put(d.kind(), v.route);
            return new Relation(sql, List.of(), null, List.of());
        }

        /** The promoted columns, the entity's id and its whole document (JSON, masks applied), staged once per version and mask signature. */
        private void stageDocuments(DuckDbExecutor e, String docTable) {
            List<String> cols = new ArrayList<>(promoted);
            List<DuckDbExecutor.Column> dc = new ArrayList<>();
            fields.stream().filter(f -> promoted.contains(f.name())).forEach(f -> dc.add(new DuckDbExecutor.Column(f.name(), "number".equals(f.type())
                    ? "number" : "text")));
            dc.add(new DuckDbExecutor.Column("_id", "text"));
            dc.add(new DuckDbExecutor.Column("_doc", "text"));
            List<String> out = new ArrayList<>(cols);
            if (d.key() != null && !out.contains(d.key())) {
                out.add(d.key());
            }
            out.add(DataViewService.DOCUMENT);
            long max = props.stageMaxRows();
            long n = e.stage(docTable, dc, sink -> {
                long[] seen = {0};
                data.walk(d, day, DataSelection.parse(List.of(), List.of()), out, entitlements.masks(p) ? entitlements.redactor(p) : null, false, 0,
                        row -> {
                            if (++seen[0] > max) {
                                throw new DrishtiException(ErrorCode.PIVOT_TOO_MANY_CELLS, d.kind() + " has more than " + max + " documents on "
                                        + (day == null ? "its current data" : day) + " (drishti.pivot.stage-max-rows): a path read from every "
                                        + "document is too large here; promote the field (the connector's layout." + d.kind() + ".columns) or add "
                                        + "it to a BI dataset (path:), so it is read as a column");
                            }
                            Object[] vals = new Object[cols.size() + 2];
                            for (int i = 0; i < cols.size(); i++) {
                                Object o = row.get(cols.get(i));
                                vals[i] = o == null || o instanceof Number || o instanceof String ? o : String.valueOf(o);
                            }
                            Object id = d.key() == null ? null : row.get(d.key());
                            vals[cols.size()] = id == null ? null : String.valueOf(id);
                            try {
                                vals[cols.size() + 1] = JSON.writeValueAsString(row.get(DataViewService.DOCUMENT));
                                sink.row(vals);
                            } catch (Exception ex) {
                                throw new java.io.IOException(ex);
                            }
                            return true;
                        });
                return seen[0];
            });
            staged.put(docTable, n);
        }

        @Override
        public Relation relation(DuckDbExecutor e) {
            exec = e;
            if (lake != null) {
                return lakeRelation();
            }
            Object lock = locks.computeIfAbsent(table, k -> new Object());
            synchronized (lock) {
                if (!staged.containsKey(table) || !e.exists(table)) {
                    stage(e);
                }
                staged.get(table);
            }
            return new Relation(DuckDbExecutor.quote(table), List.of(), null, List.of());
        }

        /**
         * The date's Parquet files as the relation: each field the person may use under its own name, masked columns never named (so never
         * read); DuckDB reads only the columns a statement uses and skips row groups by their statistics.
         */
        private Relation lakeRelation() {
            List<String> cols = new ArrayList<>();
            for (PivotProvider.Field f : fields) {
                String phys = f.name().equals(d.key()) && !lake.columns().containsKey(f.name()) ? lake.idColumn() : lake.columns().get(f.name());
                cols.add(DuckDbExecutor.quote(phys) + " AS " + DuckDbExecutor.quote(f.name()));
            }
            List<String> files = lake.files().stream().map(x -> "'" + x.replace("'", "''") + "'").toList();
            return new Relation("(SELECT " + (cols.isEmpty() ? "1 AS \"_\"" : String.join(", ", cols)) + " FROM read_parquet([" + String.join(", ", files)
                    + "], union_by_name = true))", List.of(), null, List.of());
        }

        private void stage(DuckDbExecutor e) {
            List<String> cols = fields.stream().map(PivotProvider.Field::name).toList();
            List<DuckDbExecutor.Column> dc = fields.stream().map(f -> new DuckDbExecutor.Column(f.name(), "number".equals(f.type()) ? "number"
                    : "text")).toList();
            long max = props.stageMaxRows();
            long n = e.stage(table, dc, sink -> {
                long[] seen = {0};
                data.walk(d, day, DataSelection.parse(List.of(), List.of()), cols, entitlements.masks(p) ? entitlements.redactor(p) : null, true, 0,
                        row -> {
                            if (++seen[0] > max) {
                                throw new DrishtiException(ErrorCode.PIVOT_TOO_MANY_CELLS, d.kind() + " has more than " + max + " rows on "
                                        + (day == null ? "its current data" : day) + " (drishti.pivot.stage-max-rows): filter it in a BI "
                                        + "dataset or raise the limit; a table is never cut short");
                            }
                            Object[] vals = new Object[cols.size()];
                            for (int i = 0; i < vals.length; i++) {
                                Object o = row.get(cols.get(i));
                                vals[i] = o == null || o instanceof Number || o instanceof String ? o : String.valueOf(o);
                            }
                            try {
                                sink.row(vals);
                            } catch (Exception ex) {
                                throw new java.io.IOException(ex);
                            }
                            return true;
                        });
                return seen[0];
            });
            staged.put(table, n);
            synchronized (staged) {
                while (staged.size() > props.stageKeep()) {
                    String eldest = staged.keySet().iterator().next();
                    staged.remove(eldest);
                    try {
                        e.drop(eldest);
                    } catch (RuntimeException ex) {
                        // dropped later
                    }
                }
            }
        }
    }

    // ---- documents (PIVOT_ENGINE.md section 6.7) ------------------------------------------------------------------------------

    /** Per source: what the field list's sample of documents found (the provenance's words). */
    private final Map<String, String> sampled = new ConcurrentHashMap<>();
    /** The types the sample saw, per source version and path. */
    private final Map<String, String> sampledTypes = new ConcurrentHashMap<>();

    private String shapeType(String source, String version, String path) {
        return sampledTypes.getOrDefault(source + "|" + version + "|" + path, "string");
    }

    /** The document fields of a kind on a date for a person: sampled once per data version and mask signature, then kept. */
    private Map<String, DocPath> shape(DataViewService.Dataset d, LocalDate day, Principal p, String version, java.util.Set<String> promoted) {
        String key = d.kind() + "|" + version;
        Map<String, DocPath> have = shapes.get(key);
        if (have != null) {
            return have;
        }
        List<Map<?, ?>> docs = new ArrayList<>();
        try {
            data.walk(d, day, DataSelection.parse(List.of(), List.of()), List.of(DataViewService.DOCUMENT), entitlements.masks(p) ? entitlements.redactor(p)
                    : null, false, 0, row -> {
                        if (docs.size() < SAMPLE && row.get(DataViewService.DOCUMENT) instanceof Map<?, ?> m) {
                            docs.add(m);
                        }
                        return true;                              // never stopped early: a source of several connectors takes a stop as a failure
                    });
        } catch (java.io.IOException | RuntimeException e) {
            sampled.put(d.kind(), "no sample of its documents: " + e.getMessage());
            return Map.of();                                      // no sample: the promoted columns alone, typed paths still work
        }
        Map<String, DocPath> out = new LinkedHashMap<>();
        int[] refused = {0};
        DocFields.of(docs, promoted, n -> entitlements.masked(p, n)).forEach((path, seen) -> {
            try {
                DocPath dp = DocPath.parse(path);
                out.putIfAbsent(dp.id(), dp);
                sampledTypes.put(key + "|" + dp.text(), seen.type());
            } catch (RuntimeException e) {
                refused[0]++;                                     // a name the parser does not take is left out of the list
            }
        });
        sampled.put(d.kind(), docs.size() + " documents sampled: " + out.size() + " path fields" + (refused[0] > 0 ? ", " + refused[0] + " names left out" : ""));
        if (shapes.size() > 64) {
            shapes.clear();
        }
        shapes.put(key, out);
        return out;
    }

    /** A path a person typed, as a field of the source (kept and counted for the administrator's Promote and Add to dataset). */
    DocPath typed(String source, String text) {
        DocPath dp = DocPath.parse(text);
        Typed t = typed.computeIfAbsent(source, k -> new ConcurrentHashMap<>()).computeIfAbsent(dp.text(), k -> new Typed(dp));
        t.uses.incrementAndGet();
        t.last = java.time.Instant.now().toString();
        return t.path;
    }

    /**
     * The paths people typed on a source, most used first (an administrator's suggestions: promote a path the connector's
     * {@code layout.<kind>.columns} can hold, or add it to a BI dataset as a {@code path:} column; both make it a column read).
     */
    public List<Map<String, Object>> typedPaths(String source) {
        List<Map<String, Object>> out = new ArrayList<>();
        typed.getOrDefault(source, Map.of()).values().stream().sorted((a, b) -> Long.compare(b.uses.get(), a.uses.get())).forEach(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", t.path.text());
            m.put("id", t.path.id());
            m.put("grain", DocView.words(t.path.grain()));
            m.put("uses", t.uses.get());
            m.put("lastUsed", t.last);
            m.put("promote", t.path.grainLevels().isEmpty() ? "add " + t.path.id() + " to layout." + source + ".columns of its connector"
                    : "a list's field cannot be a promoted column: add it to a BI dataset (each: " + t.path.grainLevels().get(0).name() + ")");
            m.put("dataset", "a BI dataset column: path: " + t.path.text());
            try {
                m.put("connector", data.dataset(source).connector());
            } catch (RuntimeException e) {
                m.put("connector", "");
            }
            out.add(m);
        });
        return out;
    }

    /** Drops every staged table (the engine switched off). */
    public void release(DuckDbExecutor e) {
        List<String> names;
        synchronized (staged) {
            names = new ArrayList<>(staged.keySet());
            staged.clear();
        }
        for (String t : names) {
            try {
                e.drop(t);
            } catch (RuntimeException ex) {
                // already gone
            }
        }
    }

    /** The view a kind opens with: its pack's {@code pivot:} arrangement (what the search results' Pivot offered), or null. */
    Map<String, Object> defaultView(String kind, String date) {
        if (search == null) {
            return null;
        }
        try {
            return search.pivotOf(kind).map(spec -> SpecViews.view(kind, date, spec.arrangementMap())).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The staged tables (Admin, tests). */
    public Map<String, Long> staged() {
        return Map.copyOf(staged);
    }

    /** A value that is no coordinate. */
    static boolean blank(String v) {
        return v == null || CellKey.BLANK.equals(v);
    }
}
