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
package com.ash.drishti.server.bi.poc;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.duckdb.DuckDBConnection;

/**
 * RUPAKA PHASE 0 PROOF OF CONCEPT. The server's analytical engine in miniature: an embedded DuckDB over a small sample lake
 * of Parquet files (a JSON document column plus typed columns, and a pre-aggregated rollup), answering one fixed,
 * parameterised aggregation (group by up to {@code max-group-by} of the dimensions: sum notional, sum mtm, count) and
 * returning it as Arrow IPC bytes. Field masks are applied by column: a masked dimension reads the mask in every row (and is
 * not grouped on, so it cannot be probed), a masked measure reads null. No text from a request ever reaches the SQL: names
 * come from a fixed list, values are bound parameters.
 *
 * <p>The sample lake is written on first use under {@code drishti.bi.poc.lake-dir} from DuckDB's own {@code range()}, so a
 * test or a developer machine needs no data to be loaded. DuckDB's {@code delta} extension is a run-time download
 * ({@code INSTALL delta}) and is not bundled with the JDBC jar, so the lake is plain Parquet, which DuckDB reads natively
 * (Delta's data files are Parquet).
 */
public final class PocQueryService implements AutoCloseable {

    /** How the same rows are stored: a JSON document column, typed columns, or a pre-aggregated rollup. */
    public enum Layout { JSON, TYPED, ROLLUP }

    /** A query: what to group by, over which layout, optionally one day and one equality filter. */
    public record Query(List<String> groupBy, Layout layout, String date, String filterColumn, String filterValue) {}

    /** The answer: Arrow bytes, how many rows, how long the engine took, and which columns were masked. */
    public record Answer(byte[] arrow, int rows, double millis, List<String> masked, Layout layout) {}

    public static final List<String> DIMENSIONS = List.of("desk", "currency", "productType", "side", "trader", "book");
    public static final List<String> MEASURES = List.of("notional", "mtm");

    private static final List<String> DESKS = List.of("DESK-FI", "DESK-FX", "DESK-EQ", "DESK-CMD", "DESK-RATES", "DESK-CREDIT");
    private static final List<String> CURRENCIES = List.of("USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "SGD");
    private static final List<String> PRODUCTS = List.of("IRS_FIXFLOAT", "FX_FORWARD", "FX_OPTION", "GOVT_BOND", "CORP_BOND", "CDS_SINGLE",
            "EQ_OPTION_LISTED", "STIR_FUTURE", "REPO", "CMS_SWAP");
    private static final List<String> SIDES = List.of("BUY", "SELL");
    private static final List<String> TRADERS = List.of("TRDR-ASHAH", "TRDR-RCASTILLO", "TRDR-MLEE", "TRDR-JOKAFOR", "TRDR-PVERMA", "TRDR-SGREEN",
            "TRDR-TNAKAMURA", "TRDR-LFERRARI", "TRDR-KBROWN", "TRDR-DOKAFOR", "TRDR-HSATO", "TRDR-EWALSH");
    private static final List<String> BOOKS = List.of("BOOK-FI-1", "BOOK-FI-2", "BOOK-FI-3", "BOOK-FX-1", "BOOK-FX-2", "BOOK-EQ-1",
            "BOOK-EQ-2", "BOOK-CMD-1", "BOOK-RT-1", "BOOK-CR-1");
    private static final int LAKE_VERSION = 1;

    private final PocProperties props;
    private final Path lake;
    private DuckDBConnection root;

    public PocQueryService(PocProperties props) {
        this.props = props;
        this.lake = Path.of(props.lakeDir()).toAbsolutePath().normalize();
    }

    // ---- the query ---------------------------------------------------------------------------------------------------

    /**
     * Runs the aggregation. {@code masked} says, per field, whether the caller sees it masked (the same rule as everywhere:
     * a role without {@code raw} and a field named in {@code drishti.security.redact}).
     */
    public Answer run(Query q, Predicate<String> masked) {
        validate(q, masked);
        long t0 = System.nanoTime();
        Built built = build(q, masked);
        List<ArrowIpcWriter.Column> columns = new ArrayList<>();
        int rows;
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(built.sql())) {
            for (int i = 0; i < built.params().size(); i++) {
                ps.setString(i + 1, built.params().get(i));
            }
            List<Object[]> data = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Object[] row = new Object[n];
                    for (int i = 0; i < n; i++) {
                        row[i] = rs.getObject(i + 1);
                    }
                    data.add(row);
                }
            }
            rows = data.size();
            for (int i = 0; i < built.names().size(); i++) {
                Object[] values = new Object[rows];
                for (int r = 0; r < rows; r++) {
                    Object v = data.get(r)[i];
                    values[r] = v == null ? null : built.kinds().get(i) == ArrowIpcWriter.Kind.TEXT ? v.toString()
                            : built.kinds().get(i) == ArrowIpcWriter.Kind.INT64 ? (Object) ((Number) v).longValue() : (Object) ((Number) v).doubleValue();
                }
                columns.add(new ArrowIpcWriter.Column(built.names().get(i), built.kinds().get(i), values));
            }
        } catch (SQLException | IOException e) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "the POC query failed: " + e.getMessage());
        }
        byte[] arrow = ArrowIpcWriter.write(columns, rows);
        List<String> hidden = new ArrayList<>();
        Stream.concat(q.groupBy().stream(), MEASURES.stream()).filter(masked).forEach(hidden::add);
        return new Answer(arrow, rows, (System.nanoTime() - t0) / 1e6, hidden, q.layout());
    }

    private record Built(String sql, List<String> params, List<String> names, List<ArrowIpcWriter.Kind> kinds) {}

    private void validate(Query q, Predicate<String> masked) {
        if (q.groupBy().isEmpty() || q.groupBy().size() > props.maxGroupBy()) {
            throw bad("group by 1 to " + props.maxGroupBy() + " of " + DIMENSIONS);
        }
        for (String d : q.groupBy()) {
            if (!DIMENSIONS.contains(d)) {
                throw bad("'" + d + "' is not a dimension of the POC dataset; these are: " + DIMENSIONS);
            }
        }
        if (q.groupBy().stream().distinct().count() != q.groupBy().size()) {
            throw bad("a dimension is named twice");
        }
        if (q.filterColumn() != null) {
            if (!DIMENSIONS.contains(q.filterColumn())) {
                throw bad("'" + q.filterColumn() + "' cannot be filtered on; these can: " + DIMENSIONS);
            }
            if (masked.test(q.filterColumn())) {
                throw new DrishtiException(ErrorCode.FORBIDDEN, "'" + q.filterColumn() + "' is masked for you: it cannot be filtered on");
            }
            if (q.filterValue() == null) {
                throw bad("a filter needs a value");
            }
        }
        if (q.date() != null) {
            try {
                LocalDate.parse(q.date());
            } catch (RuntimeException e) {
                throw bad("date is yyyy-mm-dd");
            }
        }
    }

    private Built build(Query q, Predicate<String> masked) {
        Layout l = q.layout();
        List<String> names = new ArrayList<>();
        List<ArrowIpcWriter.Kind> kinds = new ArrayList<>();
        List<String> select = new ArrayList<>();
        List<String> group = new ArrayList<>();
        for (String d : q.groupBy()) {
            names.add(d);
            kinds.add(ArrowIpcWriter.Kind.TEXT);
            if (masked.test(d)) {
                select.add("'" + DataNode.MASK + "' AS " + d);         // a constant: nothing to group on, nothing to probe
            } else {
                select.add(dim(l, d) + " AS " + d);
                group.add(dim(l, d));
            }
        }
        for (String m : MEASURES) {
            names.add(m);
            kinds.add(ArrowIpcWriter.Kind.FLOAT64);
            select.add(masked.test(m) ? "CAST(NULL AS DOUBLE) AS " + m : "sum(" + measure(l, m) + ") AS " + m);
        }
        names.add("trades");
        kinds.add(ArrowIpcWriter.Kind.INT64);
        select.add((l == Layout.ROLLUP ? "sum(n)" : "count(*)") + " AS trades");
        List<String> where = new ArrayList<>();
        List<String> params = new ArrayList<>();
        if (q.date() != null) {
            where.add("date = CAST(? AS DATE)");
            params.add(q.date());
        }
        if (q.filterColumn() != null) {
            where.add(dim(l, q.filterColumn()) + " = ?");
            params.add(q.filterValue());
        }
        String sql = "SELECT " + String.join(", ", select) + " FROM " + source(l)
                + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
                + (group.isEmpty() ? "" : " GROUP BY " + String.join(", ", group) + " ORDER BY " + String.join(", ", group));
        return new Built(sql, params, names, kinds);
    }

    private String source(Layout l) {
        String file = l == Layout.ROLLUP ? lake.resolve("rollup").resolve("rollup.parquet") + "" : lake.resolve("trades") + "/*/trades.parquet";
        return "read_parquet('" + file.replace("'", "''") + "'" + (l == Layout.ROLLUP ? "" : ", hive_partitioning = true") + ")";
    }

    private static String dim(Layout l, String d) {
        return l == Layout.JSON ? "json_extract_string(doc, '$." + d + "')" : d;
    }

    private static String measure(Layout l, String m) {
        return l == Layout.JSON ? "CAST(json_extract_string(doc, '$." + m + "') AS DOUBLE)" : m;
    }

    // ---- the layout comparison ---------------------------------------------------------------------------------------

    /**
     * The same aggregation (group by desk and productType: sum notional, sum mtm, count) answered from each layout, {@code runs}
     * times after a warm-up: p50 and p95 of the engine time (rows read and shaped, Arrow bytes not included) and the
     * compressed bytes of the Parquet column chunks the query has to read (what DuckDB's projection pushdown reads).
     */
    public Map<String, Object> compareLayouts(int runs) {
        Map<String, Object> out = new LinkedHashMap<>();
        Runtime rt = Runtime.getRuntime();
        long heap0 = rt.totalMemory() - rt.freeMemory();
        List<String> groupBy = List.of("desk", "productType");
        for (Layout l : Layout.values()) {
            Query q = new Query(groupBy, l, null, null, null);
            Built b = build(q, f -> false);
            for (int i = 0; i < 3; i++) {
                timed(b);
            }
            double[] ms = new double[runs];
            int rows = 0;
            for (int i = 0; i < runs; i++) {
                long t0 = System.nanoTime();
                rows = timed(b);
                ms[i] = (System.nanoTime() - t0) / 1e6;
            }
            Arrays.sort(ms);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("p50Ms", round(ms[runs / 2]));
            m.put("p95Ms", round(ms[Math.min(runs - 1, (int) Math.ceil(runs * 0.95) - 1)]));
            m.put("resultRows", rows);
            m.put("bytesRead", bytesRead(l, columnsRead(l, groupBy)));
            m.put("bytesOnDisk", bytesOnDisk(l));
            out.put(l.name().toLowerCase(), m);
        }
        out.put("runs", runs);
        out.put("tradesPerDay", props.rows());
        out.put("days", props.days());
        out.put("heapDeltaBytes", rt.totalMemory() - rt.freeMemory() - heap0);
        return out;
    }

    private int timed(Built b) {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(b.sql()); ResultSet rs = ps.executeQuery()) {
            int rows = 0;
            while (rs.next()) {
                rows++;
            }
            return rows;
        } catch (SQLException | IOException e) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "the POC comparison failed: " + e.getMessage());
        }
    }

    private static List<String> columnsRead(Layout l, List<String> groupBy) {
        List<String> cols = new ArrayList<>();
        if (l == Layout.JSON) {
            cols.add("doc");
        } else {
            cols.addAll(groupBy);
            cols.add("notional");
            cols.add("mtm");
            if (l == Layout.ROLLUP) {
                cols.add("n");
            }
        }
        return cols;
    }

    private long bytesRead(Layout l, List<String> cols) {
        String glob = l == Layout.ROLLUP ? lake.resolve("rollup").resolve("rollup.parquet").toString() : lake + "/trades/*/trades.parquet";
        String in = String.join(", ", cols.stream().map(c -> "'" + c + "'").toList());
        return scalar("SELECT coalesce(sum(total_compressed_size), 0) FROM parquet_metadata('" + glob.replace("'", "''")
                + "') WHERE path_in_schema IN (" + in + ")");
    }

    private long bytesOnDisk(Layout l) {
        try (Stream<Path> files = Files.walk(l == Layout.ROLLUP ? lake.resolve("rollup") : lake.resolve("trades"))) {
            return files.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        } catch (IOException e) {
            return -1;
        }
    }

    private long scalar(String sql) {
        try (Connection c = connection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException | IOException e) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "the POC query failed: " + e.getMessage());
        }
    }

    // ---- the sample lake -----------------------------------------------------------------------------------------------

    private synchronized Connection connection() throws SQLException, IOException {
        if (root == null) {
            root = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
            try (Statement s = root.createStatement()) {
                s.execute("SET memory_limit = '768MB'");
                s.execute("SET threads = 2");
                s.execute("SET autoinstall_known_extensions = false");      // never download an extension at run time (delta is not bundled: see the class comment)
                s.execute("SET autoload_known_extensions = false");
            }
            ensureLake();
        }
        return root.duplicate();
    }

    private void ensureLake() throws SQLException, IOException {
        String stamp = "version=" + LAKE_VERSION + " rows=" + props.rows() + " days=" + props.days();
        Path manifest = lake.resolve("MANIFEST");
        if (Files.exists(manifest) && Files.readString(manifest).startsWith(stamp)) {
            return;
        }
        if (Files.exists(lake)) {
            try (Stream<Path> walk = Files.walk(lake)) {
                walk.sorted(java.util.Comparator.reverseOrder()).filter(p -> !p.equals(lake)).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(lake.resolve("rollup"));
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        try (Statement s = root.createStatement()) {
            for (int d = 0; d < props.days(); d++) {
                LocalDate day = today.minusDays(props.days() - 1L - d);
                Path dir = Files.createDirectories(lake.resolve("trades").resolve("date=" + day));
                s.execute(generateSql(day, d, dir.resolve("trades.parquet")));
            }
            s.execute("COPY (SELECT date, desk, book, currency, productType, side, trader, sum(notional) AS notional, sum(mtm) AS mtm, "
                    + "count(*) AS n FROM read_parquet('" + lake + "/trades/*/trades.parquet', hive_partitioning = true) GROUP BY ALL) TO '"
                    + lake.resolve("rollup").resolve("rollup.parquet") + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
        }
        Files.writeString(manifest, stamp + " from=" + today.minusDays(props.days() - 1L) + " to=" + today + "\n");
    }

    private String generateSql(LocalDate day, int dayIndex, Path file) {
        long seed = 1000L * (dayIndex + 1);
        return "COPY (SELECT tradeId, desk, book, currency, productType, side, trader, notional, mtm, json_object("
                + "'tradeId', tradeId, 'desk', desk, 'book', book, 'currency', currency, 'productType', productType, 'side', side, "
                + "'trader', trader, 'notional', notional, 'mtm', mtm, 'status', 'Live', 'tradeDate', '" + day + "', "
                + "'terms', json_object('coupon', round(coupon, 5), 'tenorYears', tenor, 'dayCount', 'ACT/360', 'paymentFrequency', 'Quarterly', "
                + "'calendar', 'USNY+GBLO', 'documentation', 'ISDA 2002 master agreement with credit support annex'), "
                + "'risk', json_object('dv01', dv01, 'vega', vega, 'cs01', cs01), "
                + "'sensitivities', json_array(dv01 / 10, dv01 / 5, dv01 / 3, dv01 / 2, dv01, dv01 * 2), "
                + "'lifecycle', json_object('events', json_array('New', 'Confirmed', 'Cleared'), 'note', 'Captured in the front office system and booked')"
                + ")::VARCHAR AS doc FROM (SELECT 'POC-' || lpad(i::VARCHAR, 6, '0') AS tradeId, "
                + pick(DESKS, "h1") + " AS desk, " + pick(BOOKS, "h2") + " AS book, " + pick(CURRENCIES, "h3") + " AS currency, "
                + pick(PRODUCTS, "h4") + " AS productType, " + pick(SIDES, "h5") + " AS side, " + pick(TRADERS, "h6") + " AS trader, "
                + "((1 + (h7 % 500)) * 100000)::DOUBLE AS notional, (h8 % 2000001)::BIGINT - 1000000 AS mtm, "
                + "(h9 % 5000)::DOUBLE / 100000 AS coupon, (1 + h9 % 30)::INT AS tenor, (h8 % 40001)::BIGINT - 20000 AS dv01, "
                + "(h7 % 9001)::BIGINT - 4500 AS vega, (h6 % 3001)::BIGINT - 1500 AS cs01 FROM (SELECT i, "
                + "hash(i * 11 + " + seed + ") AS h1, hash(i * 13 + " + seed + ") AS h2, hash(i * 17 + " + seed + ") AS h3, hash(i * 19 + " + seed
                + ") AS h4, hash(i * 23 + " + seed + ") AS h5, hash(i * 29 + " + seed + ") AS h6, hash(i * 31 + " + seed + ") AS h7, hash(i * 37 + "
                + seed + ") AS h8, hash(i * 41 + " + seed + ") AS h9 FROM range(" + props.rows() + ") t(i)) g)) TO '" + file.toString().replace("'", "''")
                + "' (FORMAT PARQUET, COMPRESSION ZSTD)";
    }

    private static String pick(List<String> values, String hash) {
        return "([" + String.join(", ", values.stream().map(v -> "'" + v + "'").toList()) + "])[1 + (" + hash + " % " + values.size() + ")::INT]";
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static DrishtiException bad(String message) {
        return new DrishtiException(ErrorCode.BAD_REQUEST, message);
    }

    @Override
    public synchronized void close() {
        if (root != null) {
            try {
                root.close();
            } catch (SQLException ignored) {
                // closing at shutdown
            }
        }
    }
}
