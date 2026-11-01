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
package com.ash.drishti.server.data;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ArrowScan;
import com.ash.drishti.api.ColumnBatch;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.ScanRequest;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.source.SourcesProperties;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Reads a dataset for the Data view ({@code DATA}, docs/guides/DATA_GUIDE.md "The Data view"): every stored kind of the loaded packs with
 * its source, format and the dates it holds; one kind's facts and columns; and a day's rows through a {@link DataSelection}, streamed row
 * by row to a sink and never held whole. Where the kind's single source keeps every needed field as a column (the Delta Lake and Iceberg
 * connectors, among others), the selection's comparisons go to the source's column read ({@link ScanRequest} filters) and only the chosen
 * columns are read; otherwise the documents are streamed and filtered on the way. Every row is checked against every line either way.
 * The caller decides who may read what (the controller): this class reads as the server, with the caller's masks applied to documents.
 */
@Service
public class DataViewService {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> NUMERIC = Set.of("amount", "integer", "rate", "number", "decimal");
    private static final Map<String, String> FORMATS = Map.of("delta", "Delta", "iceberg", "Iceberg", "file", "JSON Lines", "jdbc", "Database",
            "duckdb", "DuckDB", "demo", "Demo", "cassandra", "Cassandra", "rest", "REST", "kafka", "Kafka");

    /** A column of a dataset: its name, the contract's type and description. */
    public record Column(String name, String type, String description) {
        boolean numeric() {
            return NUMERIC.contains(type);
        }
    }

    /** A stored kind as the Data view lists it. */
    public record Dataset(String kind, String pack, String code, String title, String description, String dataClass, String grain, String key,
            String connector, String format, String table, boolean dated, boolean internalOnly, List<Column> columns) {}

    /** What one pass over a day read: rows seen, rows kept, whether the source applied the comparisons, and whether it stopped early. */
    public record Walk(long seen, long matched, boolean pushed, boolean stopped, String how) {}

    /** Gets each kept row, its chosen columns in order; false stops the pass. */
    public interface RowSink {
        boolean row(Map<String, Object> row) throws IOException;
    }

    private final PackRegistry registry;
    private final SourceRouter router;
    private final SourceRegistry sources;
    private final Set<String> internalOnly;
    private final int batchRows;
    private final int listLimit;
    private final Duration readBudget;
    private volatile Map<String, Dataset> cache = Map.of();
    private volatile long cacheVersion = -1;
    /** Entities fetched at once from a source that cannot read a day in one pass. */
    static final int CHUNK = 500;

    public DataViewService(PackRegistry registry, SourceRouter router, SourceRegistry sources,
            @Value("${drishti.data-view.internal-only:}") List<String> internalOnly, @Value("${drishti.data-view.batch-rows:8192}") int batchRows,
            @Value("${drishti.data-view.list-limit:200000}") int listLimit, @Value("${drishti.data-view.read-budget:PT30S}") Duration readBudget) {
        this.registry = registry;
        this.router = router;
        this.sources = sources;
        this.internalOnly = Set.copyOf(internalOnly.stream().map(String::strip).filter(s -> !s.isEmpty()).toList());
        this.batchRows = Math.max(256, batchRows);
        this.listLimit = Math.max(1, listLimit);
        this.readBudget = readBudget;
    }

    // ---- the datasets --------------------------------------------------------------------------------------------------------------

    /** Every stored kind of the loaded packs a connector serves, in pack order (computed kinds hold no rows and are left out). */
    public List<Dataset> datasets() {
        long v = registry.version();
        if (v != cacheVersion) {
            Map<String, Dataset> out = new LinkedHashMap<>();
            for (Pack p : registry.packs()) {
                for (String kind : p.kinds()) {
                    JsonNode c = contract(p.dir(), kind);
                    if ("computed".equals(c.path("class").asText()) || out.containsKey(kind)) {
                        continue;
                    }
                    out.put(kind, describe(p, kind, c));
                }
            }
            cache = out;
            cacheVersion = v;
        }
        List<Dataset> list = new ArrayList<>();
        for (Dataset d : cache.values()) {
            Dataset live = withSource(d);
            if (live.connector() != null) {
                list.add(live);
            }
        }
        return list;
    }

    /**
     * One dataset, or a {@code 404 DRS-1002} when no loaded pack stores it. A kind with no data contract and no column source gets its columns
     * from one of its entities (its top-level fields), so a selection can still be checked.
     */
    public Dataset dataset(String kind) {
        Dataset d = datasets().stream().filter(x -> x.kind().equals(kind)).findFirst().orElseThrow(() -> new DrishtiException(
                ErrorCode.NO_SOURCE_FOR_KIND, "no stored dataset '" + kind + "': DATA lists the datasets you can download"));
        if (!d.columns().isEmpty()) {
            return d;
        }
        List<Column> cols = sampled.computeIfAbsent(kind + "@" + registry.version(), k -> sample(kind));
        return new Dataset(d.kind(), d.pack(), d.code(), d.title(), d.description(), d.dataClass(), d.grain(), d.key(), d.connector(), d.format(),
                d.table(), d.dated(), d.internalOnly(), cols);
    }

    private final Map<String, List<Column>> sampled = new java.util.concurrent.ConcurrentHashMap<>();

    private List<Column> sample(String kind) {
        try {
            List<com.ash.drishti.api.EntityHit> hits = router.list(kind, "", 1, readBudget, AsOf.LATEST,
                    new com.ash.drishti.engine.source.SourceFailures()).hits();
            if (hits.isEmpty()) {
                return List.of();
            }
            com.ash.drishti.api.EntityDocument doc = router.fetchAll(List.of(hits.get(0).ref()), readBudget, AsOf.LATEST).get(hits.get(0).ref());
            if (doc != null && doc.data() instanceof DataNode.Obj o) {
                return o.fields().entrySet().stream().map(e -> new Column(e.getKey(), switch (e.getValue()) {
                    case DataNode.Obj x -> "record";
                    case DataNode.Arr x -> "list";
                    case DataNode.Val v when v.value() instanceof Number -> "amount";
                    default -> "text";
                }, "")).toList();
            }
        } catch (RuntimeException e) {
            // no sample: the columns stay unknown and any name is taken
        }
        return List.of();
    }

    private Dataset describe(Pack p, String kind, JsonNode c) {
        String code = "";
        if (p.manifest().get("mnemonics") instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> d && kind.equals(String.valueOf(d.get("kind")))) {
                    code = String.valueOf(e.getKey()).toUpperCase(Locale.ROOT);
                }
            }
        }
        List<Column> cols = new ArrayList<>();
        c.path("fields").forEach(f -> cols.add(new Column(f.path("name").asText(), f.path("type").asText("text"), oneLine(f.path("description").asText("")))));
        String key = c.path("key").size() == 1 ? c.path("key").get(0).asText() : null;
        JsonNode st = c.path("storage");
        return new Dataset(kind, p.name(), code.isEmpty() ? c.path("mnemonic").asText("") : code, c.path("title").asText(kind),
                oneLine(c.path("description").asText("")), c.path("class").asText(""), oneLine(c.path("grain").asText("")), key, null,
                st.path("plugin").asText(""), st.path("table").asText(""), true, internalOnly.contains(kind), List.copyOf(cols));
    }

    /** The dataset with the connector that serves it now, its format (the connector's plugin) and whether it is dated. */
    private Dataset withSource(Dataset d) {
        SourcePlugin first = null;
        for (SourcePlugin s : sources.plugins()) {
            if (s.manifest().serves(d.kind())) {
                first = s;
                break;
            }
        }
        if (first == null) {
            return d;
        }
        String name = first.manifest().name();
        String plugin = sources.applied(name).map(SourcesProperties.ConnectorSettings::plugin).filter(x -> !x.isBlank()).orElse(d.format());
        List<Column> cols = d.columns();
        if (cols.isEmpty()) {                                    // no contract: the columns the source keeps
            cols = router.columnar(d.kind()).stream().sorted().map(n -> new Column(n, "text", "")).toList();
        }
        return new Dataset(d.kind(), d.pack(), d.code(), d.title(), d.description(), d.dataClass(), d.grain(), d.key(), name, format(plugin),
                d.table(), router.undatedSources(d.kind()).size() < count(d.kind()), d.internalOnly(), cols);
    }

    private int count(String kind) {
        int n = 0;
        for (SourcePlugin s : sources.plugins()) {
            n += s.manifest().serves(kind) ? 1 : 0;
        }
        return n;
    }

    /** The dates the dataset holds, oldest first (empty when its sources cannot say without reading data, or it is not dated). */
    public List<LocalDate> dates(String kind) {
        try {
            return router.heldDates(kind).map(NavigableSet::stream).map(s -> s.toList()).orElse(List.of());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    static String format(String plugin) {
        if (plugin == null || plugin.isBlank()) {
            return "";
        }
        return FORMATS.getOrDefault(plugin.toLowerCase(Locale.ROOT), Character.toUpperCase(plugin.charAt(0)) + plugin.substring(1));
    }

    // ---- reading a day -------------------------------------------------------------------------------------------------------------

    /**
     * One pass over the day: every row of the dataset on {@code date} (null: undated, the source's current rows), each checked against the
     * selection; the kept ones, projected to {@code out}, go to {@code sink}. {@code push}: give the comparisons to the source when it can
     * apply them (a download); without it every row is seen, so {@link Walk#seen} is the day's total (a count). Stops at {@code deadline}
     * (nanoTime; 0 for none) with {@link Walk#stopped}.
     */
    public Walk walk(Dataset ds, LocalDate date, DataSelection sel, List<String> out, UnaryOperator<DataNode> redactor, boolean push,
            long deadline, RowSink sink) throws IOException {
        List<String> paths = columnPaths(ds, sel, out);
        if (redactor == null && paths != null) {
            Optional<Walk> w = scan(ds, date, sel, out, paths, push, deadline, sink);
            if (w.isPresent()) {
                return w.get();
            }
        }
        return documents(ds, date, sel, out, redactor, deadline, sink);
    }

    /**
     * True when the selection is read as columns (the kind's one source keeps every column read as a column, and nothing is masked for the
     * caller): its comparisons then go to the source.
     */
    public boolean columnRead(Dataset ds, DataSelection sel, List<String> out, boolean masked) {
        return !masked && columnPaths(ds, sel, out) != null;
    }

    /** The paths a column read takes (the key comes with every row), or null when some needed field is not a column. */
    private List<String> columnPaths(Dataset ds, DataSelection sel, List<String> out) {
        Set<String> needed = new LinkedHashSet<>(out);
        needed.addAll(sel.filterFields());
        String key = ds.key();
        Set<String> columnar = router.unionOf(ds.kind()).isEmpty() ? router.columnar(ds.kind()) : Set.of();
        List<String> paths = needed.stream().filter(n -> !n.equals(key) || columnar.contains(n)).toList();
        return !paths.isEmpty() && columnar.containsAll(paths) ? paths : null;
    }

    private Optional<Walk> scan(Dataset ds, LocalDate date, DataSelection sel, List<String> out, List<String> paths, boolean push, long deadline,
            RowSink sink) throws IOException {
        Set<String> numeric = new LinkedHashSet<>();
        ds.columns().stream().filter(Column::numeric).forEach(c -> numeric.add(c.name()));
        // a line on the key when the key is not a column is checked on each row only (the read gives the key as the row's id)
        List<ScanRequest.Filter> filters = push ? sel.pushable(numeric).stream().filter(f -> paths.contains(f.path())).toList() : List.of();
        ScanRequest req = new ScanRequest(ds.kind(), paths, filters, date, date, batchRows);
        Optional<ArrowScan> opened;
        try {
            opened = router.scan(req);
        } catch (Exception e) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, ds.connector() + " could not read " + ds.kind() + ": " + e.getMessage());
        }
        if (opened.isEmpty()) {
            return Optional.empty();
        }
        long seen = 0;
        long kept = 0;
        boolean stopped = false;
        try (ArrowScan s = opened.get()) {
            ColumnBatch b;
            Map<String, Object> row = new LinkedHashMap<>();
            outer:
            while ((b = s.next()) != null) {
                int[] at = new int[paths.size()];
                for (int i = 0; i < at.length; i++) {
                    at[i] = indexOf(b.names(), paths.get(i));
                }
                for (int r = 0; r < b.rows(); r++) {
                    seen++;
                    row.clear();
                    for (int i = 0; i < at.length; i++) {
                        row.put(paths.get(i), at[i] < 0 ? null : tidy(b.value(at[i], r)));
                    }
                    if (ds.key() != null && !row.containsKey(ds.key())) {
                        row.put(ds.key(), b.ids()[r]);
                    }
                    if (!sel.matches(row::get)) {
                        continue;
                    }
                    kept++;
                    if (!sink.row(project(row, out))) {
                        stopped = true;
                        break outer;
                    }
                    if (deadline != 0 && (r & 1023) == 0 && System.nanoTime() > deadline) {
                        stopped = true;
                        break outer;
                    }
                }
            }
        } catch (IOException | DrishtiException e) {
            throw e;
        } catch (Exception e) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, ds.connector() + " failed reading " + ds.kind() + ": " + e.getMessage());
        }
        return Optional.of(new Walk(seen, kept, push && !filters.isEmpty(), stopped, "column read of " + ds.connector()));
    }

    private Walk documents(Dataset ds, LocalDate date, DataSelection sel, List<String> out, UnaryOperator<DataNode> redactor, long deadline,
            RowSink sink) throws IOException {
        final class Enough extends RuntimeException {
            Enough() {
                super(null, null, false, false);
            }
        }
        final class Failed extends RuntimeException {
            final IOException io;

            Failed(IOException io) {
                super(null, null, false, false);
                this.io = io;
            }
        }
        long[] n = new long[2];
        boolean[] stopped = {false};
        boolean read;
        AsOf asOf = date == null ? AsOf.LATEST : AsOf.of(date);
        java.util.function.Consumer<com.ash.drishti.api.EntityDocument> each = d -> {
                if (d.deleted()) {
                    return;
                }
                n[0]++;
                Object doc = (redactor == null ? d.data() : redactor.apply(d.data())).unwrap();
                Map<?, ?> m = doc instanceof Map<?, ?> mm ? mm : Map.of();
                java.util.function.Function<String, Object> value = f -> {
                    Object v = path(m, f);
                    return v == null && f.equals(ds.key()) ? d.ref().id() : tidy(v);
                };
                if (!sel.matches(value)) {
                    return;
                }
                n[1]++;
                Map<String, Object> row = new LinkedHashMap<>();
                out.forEach(c -> row.put(c, value.apply(c)));
                try {
                    if (!sink.row(row) || deadline != 0 && System.nanoTime() > deadline) {
                        stopped[0] = true;
                        throw new Enough();
                    }
                } catch (IOException e) {
                    throw new Failed(e);
                }
            };
        String how = "documents of " + ds.connector() + ", filtered while streaming";
        try {
            read = router.documents(ds.kind(), asOf, each);
            if (!read) {                                         // a source that cannot read a day in one pass: its listing, read in chunks
                how = "entities of " + ds.connector() + " one chunk at a time, filtered while streaming";
                listed(ds.kind(), asOf, each);
                read = true;
            }
        } catch (Enough e) {
            read = true;
        } catch (Failed f) {
            throw f.io;
        }
        return new Walk(n[0], n[1], false, stopped[0], how);
    }

    /** Every entity the kind's listing names on the date (at most {@code drishti.data-view.list-limit}), fetched {@link #CHUNK} at a time. */
    private void listed(String kind, AsOf asOf, java.util.function.Consumer<com.ash.drishti.api.EntityDocument> each) {
        com.ash.drishti.engine.source.SourceFailures failures = new com.ash.drishti.engine.source.SourceFailures();
        List<com.ash.drishti.api.EntityHit> hits = router.list(kind, "", listLimit, readBudget, asOf, failures).hits();
        if (hits.isEmpty() && !failures.asMap().isEmpty()) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "could not list " + kind + ": " + failures.asMap());
        }
        for (int i = 0; i < hits.size(); i += CHUNK) {
            List<com.ash.drishti.api.EntityRef> refs = hits.subList(i, Math.min(hits.size(), i + CHUNK)).stream().map(h -> h.ref()).toList();
            Map<com.ash.drishti.api.EntityRef, com.ash.drishti.api.EntityDocument> docs = router.fetchAll(refs, readBudget, asOf);
            for (com.ash.drishti.api.EntityRef r : refs) {
                com.ash.drishti.api.EntityDocument d = docs.get(r);
                if (d != null) {
                    each.accept(d);
                }
            }
        }
    }

    private static Object path(Map<?, ?> m, String field) {
        Object v = m.get(field);
        if (v != null || field.indexOf('.') < 0) {
            return v;
        }
        Object at = m;
        for (String part : field.split("\\.")) {
            if (!(at instanceof Map<?, ?> mm)) {
                return null;
            }
            at = mm.get(part);
        }
        return at;
    }

    private static int indexOf(String[] names, String name) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, Object> project(Map<String, Object> row, List<String> out) {
        Map<String, Object> m = new LinkedHashMap<>();
        out.forEach(c -> m.put(c, row.get(c)));
        return m;
    }

    /** A whole number read as a double written as a whole number (5000000, not 5000000.0). */
    static Object tidy(Object v) {
        if (v instanceof Double d && !d.isNaN() && !d.isInfinite() && d == Math.rint(d) && Math.abs(d) < 1e15) {
            return d.longValue();
        }
        return v;
    }

    /** One row as a JSON line, with its newline. */
    static byte[] line(Map<String, Object> row) throws IOException {
        byte[] json = JSON.writeValueAsBytes(row);
        byte[] out = java.util.Arrays.copyOf(json, json.length + 1);
        out[json.length] = '\n';
        return out;
    }

    /** Counts what goes through it and compresses the first few megabytes, for the size a download will have. */
    static final class Sizer {
        private static final long SAMPLE = 4L << 20;
        private long raw;
        private long sampled;
        private long packed;
        private final OutputStream counter = new OutputStream() {
            @Override
            public void write(int b) {
                packed++;
            }

            @Override
            public void write(byte[] b, int off, int len) {
                packed += len;
            }
        };
        private final DeflaterOutputStream z = new DeflaterOutputStream(counter, new Deflater(Deflater.DEFAULT_COMPRESSION, true), 8192);

        void add(byte[] line) throws IOException {
            raw += line.length;
            if (sampled < SAMPLE) {
                z.write(line);
                sampled += line.length;
            }
        }

        long raw() {
            return raw;
        }

        /** The estimated .jsonl.gz size: the sample's ratio applied to everything, plus the gzip header. */
        long gzip() throws IOException {
            if (raw == 0) {
                return 20;
            }
            z.finish();
            return 20 + Math.round(packed * ((double) raw / Math.max(1, sampled)));
        }
    }

    // ---- contracts -----------------------------------------------------------------------------------------------------------------

    private static JsonNode contract(Path dir, String kind) {
        Path f = dir == null ? null : dir.resolve("contracts").resolve(kind + ".contract.yaml");
        if (f == null || !Files.isRegularFile(f)) {
            return YAML.createObjectNode();
        }
        try {
            JsonNode n = YAML.readTree(f.toFile());
            return n == null ? YAML.createObjectNode() : n;
        } catch (IOException e) {
            return YAML.createObjectNode();
        }
    }

    private static String oneLine(String text) {
        return String.join(" ", text.trim().split("\\s+")).strip();
    }

    /** A duration in nanoseconds from now, as {@link #walk}'s deadline. */
    static long deadline(Duration budget) {
        return budget == null || budget.isZero() || budget.isNegative() ? 0 : System.nanoTime() + budget.toNanos();
    }
}
