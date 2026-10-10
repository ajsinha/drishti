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
import java.util.Optional;
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
        SourcePlugin plugin = sources.plugin(t.connector(), t.kind()).orElseThrow(() -> new DrishtiException(ErrorCode.QUERY_SOURCE,
                "no running connector serves kind '" + t.kind() + "'" + (t.connector().isBlank() ? "" : " under the name '" + t.connector() + "'")
                        + ": check Admin, Connectors"));
        List<Dataset.Column> cols = t.physical();
        List<String> warnings = new ArrayList<>();
        String stage = "bi_stage_" + STAGE.incrementAndGet();
        return engine.with(c -> {
            DuckDBConnection duck = c.unwrap(DuckDBConnection.class);
            boolean each = t.each() != null;
            LocalDate resolved = date;
            long rows;
            String how;
            ArrowScan scan = each ? null : tryScan(plugin, t, cols, date);
            if (scan != null) {
                how = "scan";
                try (scan) {
                    List<ColumnBatch.Type> types = null;
                    DuckDBAppender app = null;
                    rows = 0;
                    for (ColumnBatch b = scan.next(); b != null; b = scan.next()) {
                        if (app == null) {
                            types = List.of(b.types());
                            create(c, stage, cols, types, false);
                            app = duck.createAppender(DuckDBConnection.DEFAULT_SCHEMA, stage);
                        }
                        if (resolved == null && b.businessDate() != null) {
                            resolved = b.businessDate();
                        }
                        append(app, b);
                        rows += b.rows();
                    }
                    if (app != null) {
                        app.close();
                    }
                    warnings.addAll(scan.warnings());
                    if (types == null) {
                        create(c, stage, cols, cols.stream().map(x -> ColumnBatch.Type.TEXT).toList(), false);       // no rows: an empty partition
                    }
                }
            } else {
                how = "records";
                warnings.add("connector '" + plugin.manifest().name() + "' cannot scan " + t.kind() + (each ? "" : " as columns")
                        + ": reading documents one by one (slow for a large table; promote the columns in the connector's layout)");
                create(c, stage, cols, cols.stream().map(x -> numeric(x) ? ColumnBatch.Type.NUMBER : ColumnBatch.Type.TEXT).toList(), each);
                java.util.concurrent.atomic.AtomicReference<LocalDate> seen = new java.util.concurrent.atomic.AtomicReference<>();
                rows = records(duck, stage, t, cols, date, warnings, seen);
                if (resolved == null) {
                    resolved = seen.get();                        // the business date the source says the documents are for
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

    private ArrowScan tryScan(SourcePlugin plugin, Dataset.Table t, List<Dataset.Column> cols, LocalDate date) {
        try {
            Optional<ArrowScan> s = plugin.scan(new ScanRequest(t.kind(), cols.stream().map(Dataset.Column::path).toList(), List.of(), date, date, props.scanBatchRows()));
            return s.orElse(null);
        } catch (Exception e) {
            throw new DrishtiException(ErrorCode.QUERY_SOURCE, "connector '" + plugin.manifest().name() + "' could not scan " + t.kind() + ": " + e.getMessage());
        }
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

    private static void append(DuckDBAppender app, ColumnBatch b) throws java.sql.SQLException {
        for (int r = 0; r < b.rows(); r++) {
            app.beginRow();
            app.append(b.ids()[r]);
            for (int c = 0; c < b.types().length; c++) {
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

    private long records(DuckDBConnection duck, String stage, Dataset.Table t, List<Dataset.Column> cols, LocalDate date, List<String> warnings,
            java.util.concurrent.atomic.AtomicReference<LocalDate> seen) throws java.sql.SQLException {
        List<EntityRef> ids = sources.ids(t.kind(), date, props.recordReadMax() + 1);
        if (ids.size() > props.recordReadMax()) {
            ids = ids.subList(0, props.recordReadMax());
            warnings.add("only the first " + props.recordReadMax() + " " + t.kind() + " entities were read (drishti.bi.query.record-read-max)");
        }
        Map<EntityRef, EntityDocument> docs = sources.read(ids, date);
        long rows = 0;
        boolean each = t.each() != null;
        try (DuckDBAppender app = duck.createAppender(DuckDBConnection.DEFAULT_SCHEMA, stage)) {
            for (EntityRef ref : ids) {
                EntityDocument d = docs.get(ref);
                if (d == null || d.deleted()) {
                    continue;
                }
                if (seen.get() == null && d.provenance() != null && d.provenance().businessDate() != null) {
                    seen.set(d.provenance().businessDate());
                }
                if (!each) {
                    row(app, ref.id(), -1, d.data(), cols);
                    rows++;
                } else {
                    DataNode arr = d.data().at(t.each());
                    for (int i = 0; i < arr.size(); i++) {
                        row(app, ref.id(), i, arr.get(i), cols);
                        rows++;
                    }
                }
            }
        }
        return rows;
    }

    private static void row(DuckDBAppender app, String id, int index, DataNode node, List<Dataset.Column> cols) throws java.sql.SQLException {
        app.beginRow();
        app.append(id);
        if (index >= 0) {
            app.append((long) index);
        }
        for (Dataset.Column col : cols) {
            DataNode v = node.at(col.path());
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
