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
package com.ash.drishti.plugin.duckdb;

import com.ash.drishti.api.LoadGuard;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

/**
 * Writes a DuckDB file in {@link DuckDbLayout}: {@code java -cp <plugin classpath>
 * com.ash.drishti.plugin.duckdb.DuckDbLoader FILE|- DATABASE [--recreate] [--keep-days N] [--as-of yyyy-MM-dd]
 * [--future-days N] [--zone Z] [--max-drop-share F] [--force-drop] [--parsers N] [--memory-limit 2GB] [--threads N]}. Each line is {@code {"domain", "kind", "id", "date", "doc", "columns": {path:
 * value}}}, as {@code make_data.py --jsonl} and {@code bulk_trades.py --jsonl} write it; {@code -} reads a stream.
 *
 * <p>DuckDB lets one process write a file or several read it, never both, and the Drishti server holds the file open
 * read-only. So the loader never writes {@code DATABASE} itself:
 *
 * <ol>
 *   <li>The stream is parsed on {@code --parsers} threads (batches in order, a bounded number in flight) and appended
 *       with DuckDB's appender, as it comes, to {@code DATABASE.stage}, a scratch file with a table per data domain.
 *       A row dated after tomorrow in the business zone is not loaded; the load then ends with an error naming it.</li>
 *   <li>A new file, {@code DATABASE.loading}, is built: for each domain, the rows of the current file that stay (the
 *       file is attached read-only, which a running server allows) and then each staged (kind, business date), sorted
 *       by id. A day in the stream replaces that day whole; a row given twice keeps the last. {@code --recreate} drops
 *       the old rows of each domain the stream reaches; {@code --keep-days N} drops the days more than N calendar days
 *       before {@code --as-of} (today), never counted from the newest date loaded, and refuses to drop more than
 *       {@code --max-drop-share} (0.5) of a domain's rows without {@code --force-drop} ({@link LoadGuard}). The dates
 *       table records each day, its rows and when it was loaded.</li>
 *   <li>{@code DATABASE.loading} is checkpointed, closed and renamed over {@code DATABASE} in one atomic step; a running
 *       connector sees the new file at its next refresh and reopens it. The stage file is deleted.</li>
 * </ol>
 *
 * <p>The new file is written compactly from scratch, so retention and replaced days never leave free space behind;
 * the price is that every load rewrites the whole file (see the document for the production pattern). A lock file
 * ({@code DATABASE.lock}) keeps two loads of one file apart. {@code tools/load-duckdb.sh} runs it.
 */
public final class DuckDbLoader {

    private static final int BATCH = 1_000;

    /**
     * The storage format the file is written in. DuckDB writes new files in an old format by default, for older readers;
     * this one (DuckDB 1.5 and later can read it) compresses the documents with ZSTD, about ten times smaller than the
     * default format, which keeps strings of a few kilobytes uncompressed.
     */
    static final String STORAGE_VERSION = "v1.5.0";

    private record Row(String schema, String kind, String id, LocalDate date, String doc, Map<String, Object> columns) {}

    /** A domain's stage table: its promoted columns (number or not, in table order) and the days the stream reached. */
    private static final class Stage {
        final Map<String, Boolean> columns = new LinkedHashMap<>();
        final Map<String, String> paths = new LinkedHashMap<>();          // column -> the path it holds
        final Map<String, NavigableSet<LocalDate>> days = new TreeMap<>();
        DuckDBAppender appender;
    }

    private final Path database;
    private final boolean recreate;
    private final int keepDays;
    private final LoadGuard guard;
    private final int parsers;
    private final Properties properties = new Properties();
    private final Map<String, Stage> stages = new TreeMap<>();
    private long seq;

    private DuckDbLoader(Path database, boolean recreate, int keepDays, LoadGuard guard, int parsers, String memoryLimit, int threads) {
        this.database = database.toAbsolutePath();
        this.recreate = recreate;
        this.keepDays = keepDays;
        this.guard = guard;
        this.parsers = Math.max(1, parsers);
        properties.setProperty("memory_limit", memoryLimit);
        properties.setProperty("jdbc_instance_cache", "false");
        if (threads > 0) {
            properties.setProperty("threads", String.valueOf(threads));
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: DuckDbLoader FILE|- DATABASE [--recreate] [--keep-days N] [--as-of D] [--future-days N] [--zone Z] "
                    + "[--max-drop-share F] [--force-drop] [--parsers N] [--memory-limit 2GB] [--threads N]");
            System.exit(2);
        }
        List<String> a = List.of(args);
        new DuckDbLoader(Path.of(args[1]), a.contains("--recreate"), Integer.parseInt(option(args, "--keep-days", "0")), LoadGuard.fromArgs(args),
                Integer.parseInt(option(args, "--parsers", String.valueOf(Math.min(8, Runtime.getRuntime().availableProcessors())))),
                option(args, "--memory-limit", "2GB"), Integer.parseInt(option(args, "--threads", "0"))).load(args[0]);
    }

    private static String option(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    private Path sibling(String suffix) {
        return database.resolveSibling(database.getFileName() + suffix);
    }

    private static void deleteWithWal(Path p) throws IOException {
        Files.deleteIfExists(p);
        Files.deleteIfExists(p.resolveSibling(p.getFileName() + ".wal"));
    }

    private void load(String input) throws Exception {
        long t0 = System.nanoTime();
        if (database.getParent() != null) {
            Files.createDirectories(database.getParent());
        }
        Path stage = sibling(".stage");
        Path loading = sibling(".loading");
        try (FileChannel lockFile = FileChannel.open(sibling(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = lockFile.tryLock()) {
            if (lock == null) {
                throw new IllegalStateException("another load of " + database + " is running");
            }
            deleteWithWal(stage);
            deleteWithWal(loading);
            long rows;
            // an in-memory database with the three files attached by names no data domain uses: three-part names are never ambiguous
            Properties p = new Properties();
            p.putAll(properties);
            p.setProperty("temp_directory", sibling(".tmp").toString());
            try (DuckDBConnection c = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:", p)) {
                try (Statement st = c.createStatement()) {
                    st.execute("ATTACH '" + stage.toString().replace("'", "''") + "' AS drishti_stage");
                }
                rows = stageAll(c, input);
                System.err.printf("duckdb: %,d rows staged in %,.0f s%n", rows, (System.nanoTime() - t0) / 1e9);
                build(c, loading);
            } catch (Exception e) {
                deleteWithWal(loading);                                    // a load that failed: the current file stays as it was
                throw e;
            } finally {
                deleteWithWal(stage);
                if (Files.isDirectory(sibling(".tmp"))) {
                    try (var spilled = Files.walk(sibling(".tmp"))) {
                        spilled.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
                    }
                }
            }
            Files.move(loading, database, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            System.out.printf("duckdb: loaded %,d rows into %s (%,d MB) in %,.0f s%n", rows, database, Files.size(database) >> 20,
                    (System.nanoTime() - t0) / 1e9);
        }
        guard.finish();
    }

    /** Parses the stream on {@code parsers} threads, in order, and appends every row to its domain's stage table. */
    private long stageAll(DuckDBConnection c, String input) throws Exception {
        BlockingQueue<Future<List<Row>>> parsed = new ArrayBlockingQueue<>(parsers * 2);   // bounded: the reader waits
        List<Row> end = List.of();
        CompletableFuture<List<Row>> poison = CompletableFuture.completedFuture(end);
        ExecutorService pool = Executors.newFixedThreadPool(parsers);
        Thread reader = Thread.ofPlatform().name("duckdb-loader-reader").start(() -> {
            JsonFactory json = new JsonFactory();
            try (BufferedReader in = input.equals("-") ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8), 1 << 20)
                    : Files.newBufferedReader(Path.of(input), StandardCharsets.UTF_8)) {
                List<String> batch = new ArrayList<>(BATCH);
                String line;
                while ((line = in.readLine()) != null) {
                    if (!line.isBlank()) {
                        batch.add(line);
                    }
                    if (batch.size() == BATCH) {
                        List<String> b = batch;
                        parsed.put(pool.submit(() -> parse(json, b)));
                        batch = new ArrayList<>(BATCH);
                    }
                }
                if (!batch.isEmpty()) {
                    List<String> b = batch;
                    parsed.put(pool.submit(() -> parse(json, b)));
                }
                parsed.put(poison);
            } catch (Exception e) {
                try {
                    parsed.put(CompletableFuture.failedFuture(e));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        long rows = 0;
        try {
            while (true) {
                List<Row> batch = parsed.take().get();
                if (batch == end) {
                    break;
                }
                for (Row r : batch) {
                    if (!guard.accept(r.date(), r.schema() + " " + r.kind() + " " + r.id())) {
                        continue;
                    }
                    append(c, r);
                    if (++rows % 500_000 == 0) {
                        System.err.printf("duckdb: %,d rows%n", rows);
                    }
                }
            }
        } finally {
            reader.interrupt();
            pool.shutdownNow();
            for (Stage s : stages.values()) {
                if (s.appender != null) {
                    s.appender.close();
                }
            }
        }
        return rows;
    }

    private static List<Row> parse(JsonFactory json, List<String> lines) throws IOException {
        List<Row> out = new ArrayList<>(lines.size());
        for (String l : lines) {
            out.add(parse(json, l));
        }
        return out;
    }

    /** One row to its domain's stage table; a promoted path seen with a value for the first time becomes a column. */
    private void append(DuckDBConnection c, Row r) throws SQLException {
        Stage s = stages.get(r.schema());
        if (s == null) {
            s = new Stage();
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE drishti_stage.main.\"" + r.schema() + "\" (seq BIGINT, kind VARCHAR, id VARCHAR, business_date DATE, doc VARCHAR)");
            }
            stages.put(r.schema(), s);
        }
        for (Map.Entry<String, Object> e : r.columns().entrySet()) {
            String column = DuckDbLayout.column(e.getKey());
            if (e.getValue() != null && !s.columns.containsKey(column)) {
                boolean number = e.getValue() instanceof Double;
                if (s.appender != null) {
                    s.appender.close();                                     // the appender knows the columns it was opened with
                    s.appender = null;
                }
                try (Statement st = c.createStatement()) {
                    st.execute("ALTER TABLE drishti_stage.main.\"" + r.schema() + "\" ADD COLUMN \"" + column + "\" " + (number ? "DOUBLE" : "VARCHAR"));
                }
                s.columns.put(column, number);
                s.paths.put(column, e.getKey());
            }
        }
        if (s.appender == null) {
            s.appender = c.createAppender("drishti_stage", "main", r.schema());
        }
        s.days.computeIfAbsent(r.kind(), k -> new TreeSet<>()).add(r.date());
        DuckDBAppender a = s.appender;
        a.beginRow().append(seq++).append(r.kind()).append(r.id()).append(r.date()).append(r.doc());
        for (Map.Entry<String, Boolean> col : s.columns.entrySet()) {
            Object v = r.columns().get(s.paths.get(col.getKey()));
            if (v == null || col.getValue() && !(v instanceof Double)) {
                a.appendNull();
            } else if (v instanceof Double d) {
                if (col.getValue()) {
                    a.append(d.doubleValue());
                } else {
                    a.append(d == Math.rint(d) && !Double.isInfinite(d) ? String.valueOf(d.longValue()) : String.valueOf(d));
                }
            } else {
                a.append(v.toString());
            }
        }
        a.endRow();
    }

    /** The new file: each domain's rows that stay, then the staged days sorted by id, and the dates table. */
    private void build(DuckDBConnection c, Path loading) throws SQLException, IOException {
        boolean hasOld = Files.exists(database);
        try (Statement st = c.createStatement()) {
            if (hasOld) {
                st.execute("ATTACH '" + database.toString().replace("'", "''") + "' AS drishti_old (READ_ONLY)");
            }
            st.execute("ATTACH '" + loading.toString().replace("'", "''") + "' AS drishti_new (STORAGE_VERSION '" + STORAGE_VERSION + "')");
        }
        Set<String> schemas = new TreeSet<>(stages.keySet());
        if (hasOld) {
            schemas.addAll(DuckDbLayout.schemas(c, "drishti_old"));
        }
        // one transaction: committed and checkpointed once, the file holds no blocks that a later write freed
        c.setAutoCommit(false);
        for (String schema : schemas) {
            long t0 = System.nanoTime();
            Stage s = stages.get(schema);
            boolean oldTable = hasOld && DuckDbLayout.schemas(c, "drishti_old").contains(schema) && !(recreate && s != null);
            Map<String, Boolean> columns = new LinkedHashMap<>(oldTable ? DuckDbLayout.columns(c, "drishti_old", schema, DuckDbLayout.TABLE) : Map.of());
            if (s != null) {
                s.columns.forEach(columns::putIfAbsent);
            }
            // each kind's days and where they come from: the stream replaces a day the old file has
            Map<String, NavigableSet<LocalDate>> oldDays = oldTable ? DuckDbLayout.dates(c, "drishti_old", schema) : new TreeMap<>();
            Map<String, NavigableSet<LocalDate>> newDays = s == null ? Map.of() : s.days;
            // retention counts back from --as-of (today), never from the newest date of the file or the load
            LocalDate keepFrom = keepDays > 0 ? guard.keepFromDays(keepDays) : LocalDate.MIN;
            if (oldTable && keepDays > 0) {
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COALESCE(SUM(rows) FILTER (WHERE business_date < CAST('" + keepFrom + "' AS DATE)), 0), "
                             + "COALESCE(SUM(rows), 0) FROM " + DuckDbLayout.datesSource(c, "drishti_old", schema))) {
                    rs.next();
                    guard.checkDrop(schema, rs.getLong(1), rs.getLong(2), "rows");     // nothing written yet: the file stays
                }
            }
            try (Statement st = c.createStatement()) {
                st.execute("CREATE SCHEMA drishti_new." + schema);
                st.execute(DuckDbLayout.createTable("drishti_new." + schema, columns));
                st.execute(DuckDbLayout.createDates("drishti_new." + schema));
                if (oldTable) {
                    // the rows that stay, in the order they were written (each day already sorted by id)
                    StringBuilder keep = new StringBuilder("business_date >= CAST('").append(keepFrom.equals(LocalDate.MIN) ? "0001-01-01" : keepFrom.toString()).append("' AS DATE)");
                    newDays.forEach((kind, ds) -> ds.forEach(d -> keep.append(" AND NOT (kind = '").append(kind.replace("'", "''"))
                            .append("' AND business_date = DATE '").append(d).append("')")));
                    long kept = st.executeUpdate("INSERT INTO drishti_new." + schema + "." + DuckDbLayout.TABLE + " BY NAME SELECT * FROM drishti_old." + schema + "."
                            + DuckDbLayout.TABLE + " WHERE " + keep);
                    st.execute("INSERT INTO drishti_new." + schema + "." + DuckDbLayout.DATES + " SELECT * FROM " + DuckDbLayout.datesSource(c, "drishti_old", schema) + " WHERE " + keep);
                    long dropped = oldDays.values().stream().flatMap(Set::stream).filter(d -> d.isBefore(keepFrom)).count();
                    if (dropped > 0) {
                        System.err.printf("duckdb: %s: dropped %,d business dates before %s%n", schema, dropped, keepFrom);
                    }
                    System.err.printf("duckdb: %s: kept %,d rows of the current file%n", schema, kept);
                }
            }
            for (var e : newDays.entrySet()) {
                for (LocalDate day : e.getValue()) {
                    if (day.isBefore(keepFrom)) {
                        continue;
                    }
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO drishti_new." + schema + "." + DuckDbLayout.TABLE + " BY NAME SELECT * EXCLUDE (seq) FROM drishti_stage.main.\""
                            + schema + "\" WHERE kind = ? AND business_date = CAST(? AS DATE) QUALIFY row_number() OVER (PARTITION BY id ORDER BY seq DESC) = 1 ORDER BY id");
                         PreparedStatement dates = c.prepareStatement("INSERT INTO drishti_new." + schema + "." + DuckDbLayout.DATES
                                 + " VALUES (?, CAST(? AS DATE), ?, now())")) {
                        ps.setString(1, e.getKey());
                        ps.setString(2, day.toString());
                        long n = ps.executeUpdate();
                        dates.setString(1, e.getKey());
                        dates.setString(2, day.toString());
                        dates.setLong(3, n);
                        dates.executeUpdate();
                    }
                }
            }
            System.err.printf("duckdb: %s written in %,.1f s%n", schema, (System.nanoTime() - t0) / 1e9);
        }
        c.commit();
        c.setAutoCommit(true);
        try (Statement st = c.createStatement()) {
            st.execute("CHECKPOINT drishti_new");
            st.execute("DETACH drishti_new");
            if (hasOld) {
                st.execute("DETACH drishti_old");
            }
        }
    }

    private static Row parse(JsonFactory json, String line) throws IOException {
        String domain = null;
        String kind = null;
        String id = null;
        String date = null;
        String doc = null;
        Map<String, Object> columns = new LinkedHashMap<>();
        try (JsonParser p = json.createParser(line)) {
            p.nextToken();
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken t = p.nextToken();
                switch (name) {
                    case "domain" -> domain = p.getText();
                    case "kind" -> kind = p.getText();
                    case "id" -> id = p.getText();
                    case "date" -> date = p.getText();
                    case "doc" -> doc = p.getText();
                    case "columns" -> {
                        if (t == JsonToken.START_OBJECT) {
                            while (p.nextToken() == JsonToken.FIELD_NAME) {
                                String path = p.currentName();
                                JsonToken v = p.nextToken();
                                columns.put(path, v == JsonToken.VALUE_NUMBER_INT || v == JsonToken.VALUE_NUMBER_FLOAT ? (Object) p.getDoubleValue()
                                        : v == JsonToken.VALUE_NULL ? null : v == JsonToken.START_OBJECT || v == JsonToken.START_ARRAY ? skip(p) : p.getText());
                            }
                        }
                    }
                    default -> p.skipChildren();
                }
            }
        }
        if (domain == null || kind == null || id == null || date == null || doc == null) {
            throw new IOException("a row needs domain, kind, id, date and doc: " + (line.length() > 200 ? line.substring(0, 200) + "…" : line));
        }
        return new Row(DuckDbLayout.schema(domain), kind, id, LocalDate.parse(date), doc, columns);
    }

    private static Object skip(JsonParser p) throws IOException {
        p.skipChildren();
        return null;                                                       // a promoted path is a value, not an object
    }
}
