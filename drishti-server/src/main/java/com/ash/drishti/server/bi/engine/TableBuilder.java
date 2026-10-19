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
package com.ash.drishti.server.bi.engine;

import com.ash.drishti.api.ArrowScan;
import com.ash.drishti.api.ColumnBatch;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.ScanRequest;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.dataset.Dataset;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

/**
 * Writes one business date of one table as a typed Parquet file (the columns layer, RUPAKA.md section 7.1): the source is read once, the declared
 * columns are cast to their types, and the file is written beside the others of the table. The read goes the fastest way the connector allows:
 * <ol>
 *   <li>{@code scan}: the connector's column batches (Delta's promoted columns, the DuckDB and PostgreSQL stores, JSON-lines files);</li>
 *   <li>otherwise record by record through the router, parsing each document once, with a warning that a large table will be slow, and at most
 *       {@code record-read-max} entities.</li>
 * </ol>
 * A table over an array in the document ({@code each}) always reads records: the array's elements become rows. The file is written under a
 * temporary name and renamed, so a reader never sees half a partition.
 */
final class TableBuilder {

    /** Documents read at a time when a table is built record by record. */
    private static final int READ_BATCH = 500;

    /** What was built. */
    record Built(LocalDate date, long rows, Path file, List<String> warnings, String how) {}

    private static final AtomicLong STAGE = new AtomicLong();
    private final AnalyticEngine engine;
    private final SourceGateway sources;
    private final QueryProperties props;

    TableBuilder(AnalyticEngine engine, SourceGateway sources, QueryProperties props) {
        this.engine = engine;
        this.sources = sources;
        this.props = props;
    }

    /**
     * @param date the business date, or null for the source's latest
     * @param partition the directory the file goes in when the date is known; for a null date {@code partitionFor} gives it once the date is
     */
    Built build(Dataset.Table t, LocalDate date, java.util.function.Function<LocalDate, Path> partitionFor) {
        List<SourcePlugin> plugins = sources.plugins(t.connector(), t.kind());
        if (plugins.isEmpty()) {
            throw new DrishtiException(ErrorCode.QUERY_SOURCE, "no running connector serves kind '" + t.kind() + "'"
                    + (t.connector().isBlank() ? "" : " under the name '" + t.connector() + "'") + ": check Admin, Connectors");
        }
        SourcePlugin plugin = plugins.get(0);
        List<Dataset.Column> cols = t.physical();
        List<String> warnings = new ArrayList<>();
        String stage = "bi_stage_" + STAGE.incrementAndGet();
        return engine.with(c -> {
            DuckDBConnection duck = c.unwrap(DuckDBConnection.class);
            boolean each = t.each() != null;
            LocalDate resolved = date;
            long rows;
            String how;
            List<ArrowScan> scans = each ? null : tryScans(plugins, t, cols, date);
            if (scans != null) {
                how = plugins.size() > 1 ? "scan of " + plugins.size() + " connectors" : "scan";
                List<ColumnBatch.Type> types = null;
                DuckDBAppender app = null;
                rows = 0;
                try {
                    for (ArrowScan scan : scans) {            // a union's source systems one after the other, into one table
                        for (ColumnBatch b = scan.next(); b != null; b = scan.next()) {
                            if (app == null) {
                                types = plugins.size() > 1 ? declared(cols) : List.of(b.types());
                                create(c, stage, cols, types, false);
                                app = duck.createAppender(DuckDBConnection.DEFAULT_SCHEMA, stage);
                            }
                            if (resolved == null && b.businessDate() != null) {
                                resolved = b.businessDate();
                            }
                            append(app, b, types);
                            rows += b.rows();
                        }
                        warnings.addAll(scan.warnings());
                    }
                } finally {
                    if (app != null) {
                        app.close();
                    }
                    for (ArrowScan scan : scans) {
                        scan.close();
                    }
                }
                if (types == null) {
                    create(c, stage, cols, cols.stream().map(x -> ColumnBatch.Type.TEXT).toList(), false);       // no rows: an empty partition
                }
            } else {
                how = "records";
                warnings.add(each ? t.name() + " is over the array '" + t.each() + "' of " + t.kind() + ": the documents of connector '" + plugin.manifest().name()
                        + "' are read once per business date and cut into rows (slow for a large table)"
                        : "connector '" + plugin.manifest().name() + "' cannot scan " + t.kind() + " as columns for table " + t.name()
                        + ": reading documents one by one (slow for a large table; promote the columns in the connector's layout, or fewer columns)");
                create(c, stage, cols, cols.stream().map(x -> numeric(x) ? ColumnBatch.Type.NUMBER : ColumnBatch.Type.TEXT).toList(), each);
                java.util.concurrent.atomic.AtomicReference<LocalDate> seen = new java.util.concurrent.atomic.AtomicReference<>();
                // a table that names its connector reads that connector's documents only (never another's of the same kind), for its newest date when none is asked
                List<SourcePlugin> named = t.connector().isBlank() ? List.of() : plugins;
                rows = records(duck, stage, t, named, cols, date == null && !named.isEmpty() ? newestDate(plugin, t.kind()) : date, warnings, seen);
                if (resolved == null) {
                    resolved = seen.get();                        // the business date the source says the documents are for
                }
                if (resolved == null) {
                    resolved = newestDate(plugin, t.kind());      // else the newest date the connector holds
                }
            }
            if (resolved == null) {
                resolved = sources.currentDate();
            }
            Path dir = partitionFor.apply(resolved);
            Files.createDirectories(dir);
            Path tmp = dir.resolve("part.parquet.tmp");
            Path file = dir.resolve("part.parquet");
            try (Statement s = c.createStatement()) {
                s.execute("COPY (" + select(stage, cols, resolved, each) + ") TO '" + literal(tmp) + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
                s.execute("DROP TABLE IF EXISTS " + stage);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return new Built(resolved, rows, file, warnings, how);
        });
    }

    /** The newest business date the connector says it holds for the kind, or the current business date when it cannot say. */
    private LocalDate newestDate(SourcePlugin plugin, String kind) {
        try {
            return plugin.inventory(kind, 1).keySet().stream().max(java.util.Comparator.naturalOrder()).orElse(sources.currentDate());
        } catch (RuntimeException e) {
            return sources.currentDate();
        }
    }

    /** A scan of every connector, or null (documents are read) when one of them cannot scan the kind as columns. */
    private List<ArrowScan> tryScans(List<SourcePlugin> plugins, Dataset.Table t, List<Dataset.Column> cols, LocalDate date) {
        List<ArrowScan> out = new ArrayList<>();
        for (SourcePlugin plugin : plugins) {
            ArrowScan s;
            try {
                s = plugin.scan(new ScanRequest(t.kind(), cols.stream().map(Dataset.Column::path).toList(), List.of(), date, date, props.scanBatchRows())).orElse(null);
            } catch (Exception e) {
                out.forEach(ArrowScan::close);
                throw new DrishtiException(ErrorCode.QUERY_SOURCE, "connector '" + plugin.manifest().name() + "' could not scan " + t.kind() + ": " + e.getMessage());
            }
            if (s == null) {
                out.forEach(ArrowScan::close);
                return null;
            }
            out.add(s);
        }
        return out;
    }

    /**
     * The column types a union's table is made with: the dataset's (a number column is a number, the others text), not the first
     * source system's, since another may write the same field differently (a trade id that is a number in one system, text in another).
     */
    private static List<ColumnBatch.Type> declared(List<Dataset.Column> cols) {
        return cols.stream().map(x -> numeric(x) ? ColumnBatch.Type.NUMBER : ColumnBatch.Type.TEXT).toList();
    }

    // ---- staging -----------------------------------------------------------------------------------------------------

    private static void create(Connection c, String stage, List<Dataset.Column> cols, List<ColumnBatch.Type> types, boolean each) throws java.sql.SQLException {
        StringBuilder sql = new StringBuilder("CREATE TABLE " + stage + " (\"id\" VARCHAR");
        if (each) {
            sql.append(", \"idx\" BIGINT");
        }
        for (int i = 0; i < cols.size(); i++) {
            sql.append(", c").append(i).append(types.get(i) == ColumnBatch.Type.NUMBER ? " DOUBLE" : types.get(i) == ColumnBatch.Type.BOOLEAN ? " BOOLEAN" : " VARCHAR");
        }
        try (Statement s = c.createStatement()) {
            s.execute(sql.append(')').toString());
        }
    }

    /** One batch into a table whose column types are {@code into}: a value of another type is converted ({@link #appendAs}). */
    private static void append(DuckDBAppender app, ColumnBatch b, List<ColumnBatch.Type> into) throws java.sql.SQLException {
        for (int r = 0; r < b.rows(); r++) {
            app.beginRow();
            app.append(b.ids()[r]);
            for (int c = 0; c < b.types().length; c++) {
                if (b.types()[c] != into.get(c)) {
                    appendAs(app, b, c, r, into.get(c));
                    continue;
                }
                switch (b.types()[c]) {
                    case NUMBER -> {
                        double d = ((double[]) b.data()[c])[r];
                        if (Double.isNaN(d)) {
                            app.append((Double) null);
                        } else {
                            app.append(d);
                        }
                    }
                    case BOOLEAN -> app.append(((boolean[]) b.data()[c])[r]);
                    case TEXT -> app.append(((String[]) b.data()[c])[r]);
                }
            }
            app.endRow();
        }
    }

    /** A value written as another type: a number as text (a whole number without ".0"), text as a number (null when it is not one). */
    private static void appendAs(DuckDBAppender app, ColumnBatch b, int c, int r, ColumnBatch.Type into) throws java.sql.SQLException {
        Object v = switch (b.types()[c]) {
            case NUMBER -> {
                double d = ((double[]) b.data()[c])[r];
                yield Double.isNaN(d) ? null : d;
            }
            case BOOLEAN -> ((boolean[]) b.data()[c])[r];
            case TEXT -> ((String[]) b.data()[c])[r];
        };
        switch (into) {
            case TEXT -> app.append(v == null ? null
                    : v instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d) ? String.valueOf(d.longValue()) : String.valueOf(v));
            case NUMBER -> {
                Double d = null;
                if (v instanceof Double x) {
                    d = x;
                } else if (v instanceof String t && !t.isBlank()) {
                    try {
                        d = Double.valueOf(t.trim());
                    } catch (NumberFormatException e) {
                        d = null;                         // text that is not a number: no value
                    }
                }
                app.append(d);
            }
            case BOOLEAN -> app.append((boolean) (v instanceof Boolean x ? x : Boolean.parseBoolean(String.valueOf(v))));
        }
    }

    private long records(DuckDBConnection duck, String stage, Dataset.Table t, List<SourcePlugin> named, List<Dataset.Column> cols, LocalDate date, List<String> warnings,
            java.util.concurrent.atomic.AtomicReference<LocalDate> seen) throws java.sql.SQLException {
        List<EntityRef> ids = sources.ids(named, t.kind(), date, props.recordReadMax() + 1);
        if (ids.size() > props.recordReadMax()) {
            ids = ids.subList(0, props.recordReadMax());
            warnings.add("only the first " + props.recordReadMax() + " " + t.kind() + " entities were read (drishti.bi.query.record-read-max)");
        }
        long rows = 0;
        boolean each = t.each() != null;
        Map<EntityRef, EntityDocument> docs = Map.of();
        int at = 0;
        try (DuckDBAppender app = duck.createAppender(DuckDBConnection.DEFAULT_SCHEMA, stage)) {
            for (EntityRef ref : ids) {
                if (at % READ_BATCH == 0) {                // documents a batch at a time: memory holds one batch, never the kind
                    docs = sources.read(named, ids.subList(at, Math.min(ids.size(), at + READ_BATCH)), date);
                }
                at++;
                EntityDocument d = docs.get(ref);
                if (d == null || d.deleted()) {
                    continue;
                }
                if (seen.get() == null && d.provenance() != null && d.provenance().businessDate() != null) {
                    seen.set(d.provenance().businessDate());
                }
                if (!each) {
                    row(app, ref.id(), -1, List.of(d.data()), cols);
                    rows++;
                } else {
                    rows += nested(app, ref.id(), new java.util.ArrayList<>(List.of(d.data())), t.each().split("\\[\\]\\."), 0, cols);
                }
            }
        }
        return rows;
    }

    /**
     * The rows of an array at any depth ({@code each: LEGS[].CASH_FLOWS[].RESETS}): every element of the last array, with its ancestors
     * kept so a column reads the document ({@code $.TRADE_ID}), the element one level up ({@code ^.LEG_ID}), two up ({@code ^^.FLOW_ID})
     * or the element itself (a plain path). {@code index} is the element's position in its own array.
     */
    private static long nested(DuckDBAppender app, String id, java.util.List<DataNode> chain, String[] steps, int level, List<Dataset.Column> cols)
            throws java.sql.SQLException {
        DataNode arr = chain.get(chain.size() - 1).at(steps[level]);
        long n = 0;
        for (int i = 0; i < arr.size(); i++) {
            DataNode e = arr.get(i);
            if (e.type() != com.ash.drishti.api.NodeType.OBJECT && level < steps.length - 1) {
                continue;
            }
            chain.add(e);
            if (level == steps.length - 1) {
                row(app, id, i, chain, cols);
                n++;
            } else {
                n += nested(app, id, chain, steps, level + 1, cols);
            }
            chain.remove(chain.size() - 1);
        }
        return n;
    }

    /** A column's value: {@code $.} reads the document, each leading {@code ^} one level up from the element, a plain path the element. */
    static DataNode resolve(java.util.List<DataNode> chain, String path) {
        if (path.startsWith("$.")) {
            return chain.get(0).at(path.substring(2));
        }
        int up = 0;
        while (up < path.length() && path.charAt(up) == '^') {
            up++;
        }
        if (up > 0) {
            int at = chain.size() - 1 - up;
            String rest = path.substring(up).startsWith(".") ? path.substring(up + 1) : path.substring(up);
            return at < 0 ? DataNode.missing() : chain.get(at).at(rest);
        }
        return chain.get(chain.size() - 1).at(path);
    }

    private static void row(DuckDBAppender app, String id, int index, java.util.List<DataNode> chain, List<Dataset.Column> cols) throws java.sql.SQLException {
        app.beginRow();
        app.append(id);
        if (index >= 0) {
            app.append((long) index);
        }
        for (Dataset.Column col : cols) {
            DataNode v = resolve(chain, col.path());
            if (numeric(col)) {
                double d = v.isNull() || v.isMasked() ? Double.NaN : v.asDouble();
                if (Double.isNaN(d)) {
                    app.append((Double) null);
                } else {
                    app.append(d);
                }
            } else {
                app.append(v.isNull() || v.isMasked() || v.type() == com.ash.drishti.api.NodeType.OBJECT || v.type() == com.ash.drishti.api.NodeType.ARRAY ? null : v.asText());
            }
        }
        app.endRow();
    }

    private static boolean numeric(Dataset.Column c) {
        return "integer".equals(c.type()) || "decimal".equals(c.type());
    }

    private static String select(String stage, List<Dataset.Column> cols, LocalDate date, boolean each) {
        StringBuilder sql = new StringBuilder("SELECT \"id\" AS \"id\", CAST('" + date + "' AS DATE) AS \"businessDate\"");
        if (each) {
            sql.append(", \"idx\" AS \"index\"");
        }
        for (int i = 0; i < cols.size(); i++) {
            Dataset.Column c = cols.get(i);
            String x = "c" + i;
            String cast = switch (c.type()) {
                case "integer" -> "TRY_CAST(" + x + " AS BIGINT)";
                case "decimal" -> "TRY_CAST(" + x + " AS DOUBLE)";
                case "boolean" -> "TRY_CAST(" + x + " AS BOOLEAN)";
                case "date" -> "TRY_CAST(substr(CAST(" + x + " AS VARCHAR), 1, 10) AS DATE)";
                case "timestamp" -> "TRY_CAST(" + x + " AS TIMESTAMP)";
                default -> "CAST(" + x + " AS VARCHAR)";
            };
            sql.append(", ").append(cast).append(" AS ").append(Dialect.quote(c.name()));
        }
        return sql.append(" FROM ").append(stage).toString();
    }

    /** A file path as a SQL string literal; paths are the server's own (dataset keys are sanitised), a quote is doubled all the same. */
    static String literal(Path p) {
        return p.toAbsolutePath().toString().replace("'", "''");
    }

    static void delete(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }
}
