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
package com.ash.drishti.plugin.jdbc;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.postgresql.PGConnection;

/**
 * Loads rows into PostgreSQL in {@link PostgresLayout}: {@code java -cp <plugin classpath>
 * com.ash.drishti.plugin.jdbc.PostgresLoader FILE|- [jdbc-url] [--user U] [--password P] [--writers N] [--recreate]
 * [--keep-months N]}. Each line is {@code {"domain", "kind", "id", "date", "doc", "columns": {path: value}}}, as
 * {@code make_data.py --jsonl} and {@code bulk_trades.py --jsonl} write it; {@code -} reads a stream.
 *
 * <p>Each data domain goes to {@code <domain>.entities}. A kind's business date is replaced whole: the first time the
 * stream reaches a (kind, date) its existing rows are deleted, so loading a day again is safe. Rows are written with
 * {@code COPY} in batches of 2,000 on {@code --writers} connections at once (8). A promoted path seen with a value for
 * the first time becomes a column ({@code double precision} for a number). At the end each kind's dates are recorded,
 * the partitions written are vacuumed and analysed (so a day's ids come from the index alone), and with
 * {@code --keep-months N} partitions older than the newest N months are dropped. {@code --recreate} drops each domain's
 * table first. {@code tools/load-postgres.sh} runs it.
 */
public final class PostgresLoader {

    private static final int BATCH = 2_000;

    private record Row(String schema, String kind, String id, LocalDate date, String doc, Map<String, Object> columns) {}

    /** A domain's table as the loader knows it: its promoted columns, numeric or not. */
    private static final class Target {
        final Map<String, Boolean> columns = new LinkedHashMap<>();
        final Set<LocalDate> months = new HashSet<>();
    }

    private final String url;
    private final String user;
    private final String password;
    private final int writers;
    private final boolean recreate;
    private final Map<String, Target> targets = new HashMap<>();
    private final Map<String, Map<String, Map<LocalDate, AtomicLong>>> counts = new TreeMap<>();   // schema -> kind -> date -> rows
    private final AtomicReference<Exception> failed = new AtomicReference<>();
    private final AtomicLong written = new AtomicLong();

    private PostgresLoader(String url, String user, String password, int writers, boolean recreate) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.writers = writers;
        this.recreate = recreate;
    }

    public static void main(String[] args) throws Exception {
        String file = args[0];
        String url = args.length > 1 && !args[1].startsWith("--") ? args[1] : env("DRISHTI_PG_URL", "jdbc:postgresql://localhost:5432/drishti");
        String user = option(args, "--user", env("DRISHTI_PG_USER", "drishti"));
        String password = option(args, "--password", env("DRISHTI_PG_PASSWORD", "drishti"));
        int writers = Integer.parseInt(option(args, "--writers", "8"));
        int keepMonths = Integer.parseInt(option(args, "--keep-months", "0"));
        boolean recreate = List.of(args).contains("--recreate");
        new PostgresLoader(url, user, password, writers, recreate).load(file, keepMonths);
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String option(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    private void load(String file, int keepMonths) throws Exception {
        long t0 = System.nanoTime();
        BlockingQueue<Connection> pool = new ArrayBlockingQueue<>(writers);
        for (int i = 0; i < writers; i++) {
            pool.add(DriverManager.getConnection(url, user, password));
        }
        Semaphore inFlight = new Semaphore(writers * 2);
        JsonFactory json = new JsonFactory();
        try (Connection admin = DriverManager.getConnection(url, user, password);
             BufferedReader in = file.equals("-") ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8), 1 << 20)
                     : Files.newBufferedReader(Path.of(file), StandardCharsets.UTF_8);
             ExecutorService copy = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Row> batch = new ArrayList<>(BATCH);
            String line;
            while ((line = in.readLine()) != null && failed.get() == null) {
                if (line.isBlank()) {
                    continue;
                }
                Row row = parse(json, line);
                if (!batch.isEmpty() && !batch.get(0).schema().equals(row.schema())) {
                    submit(copy, pool, inFlight, batch);
                    batch = new ArrayList<>(BATCH);
                }
                prepare(admin, row);
                batch.add(row);
                if (batch.size() == BATCH) {
                    submit(copy, pool, inFlight, batch);
                    batch = new ArrayList<>(BATCH);
                }
            }
            if (!batch.isEmpty()) {
                submit(copy, pool, inFlight, batch);
            }
            inFlight.acquire(writers * 2);                                 // every batch written
            if (failed.get() != null) {
                throw failed.get();
            }
            finish(admin, keepMonths);
        } finally {
            for (Connection c : pool) {
                c.close();
            }
        }
        System.out.printf("postgres: loaded %,d rows in %,.0f s%n", written.get(), (System.nanoTime() - t0) / 1e9);
    }

    /** Before a row is queued: its domain's table, its month's partition, its new columns, and its day cleared once. */
    private void prepare(Connection admin, Row row) throws Exception {
        Target t = targets.get(row.schema());
        if (t == null) {
            if (recreate) {
                PostgresLayout.drop(admin, row.schema());
            }
            Boolean partitioned = PostgresLayout.partitioned(admin, row.schema());
            if (Boolean.FALSE.equals(partitioned)) {
                throw new IllegalStateException(row.schema() + "." + PostgresLayout.TABLE + " is a plain table of the old layout: load with --recreate");
            }
            PostgresLayout.create(admin, row.schema());
            t = new Target();
            t.columns.putAll(PostgresLayout.columns(admin, row.schema(), PostgresLayout.TABLE));
            targets.put(row.schema(), t);
        }
        if (t.months.add(row.date().withDayOfMonth(1))) {
            PostgresLayout.ensurePartition(admin, row.schema(), row.date());
        }
        for (Map.Entry<String, Object> e : row.columns().entrySet()) {
            String column = PostgresLayout.column(e.getKey());
            if (e.getValue() != null && !t.columns.containsKey(column)) {
                boolean number = e.getValue() instanceof Double;
                PostgresLayout.addColumn(admin, row.schema(), e.getKey(), number);
                t.columns.put(column, number);
            }
        }
        Map<LocalDate, AtomicLong> days = counts.computeIfAbsent(row.schema(), k -> new TreeMap<>()).computeIfAbsent(row.kind(), k -> new TreeMap<>());
        AtomicLong n = days.get(row.date());
        if (n == null) {                                                    // the first row of this day: the day is replaced
            try (PreparedStatement ps = admin.prepareStatement("DELETE FROM " + row.schema() + "." + PostgresLayout.TABLE
                    + " WHERE kind = ? AND business_date = ?")) {
                ps.setString(1, row.kind());
                ps.setDate(2, java.sql.Date.valueOf(row.date()));
                ps.executeUpdate();
            }
            n = new AtomicLong();
            days.put(row.date(), n);
        }
        n.incrementAndGet();
    }

    private void submit(ExecutorService copy, BlockingQueue<Connection> pool, Semaphore inFlight, List<Row> batch) throws InterruptedException {
        Target t = targets.get(batch.get(0).schema());
        Map<String, Boolean> columns = new LinkedHashMap<>();                // the promoted columns these rows carry
        for (Row r : batch) {
            r.columns().forEach((path, v) -> {
                String c = PostgresLayout.column(path);
                if (t.columns.containsKey(c)) {
                    columns.put(c, t.columns.get(c));
                }
            });
        }
        inFlight.acquire();
        copy.execute(() -> {
            Connection c = null;
            try {
                c = pool.take();
                write(c, batch, columns);
                written.addAndGet(batch.size());
                if (written.get() / 100_000 != (written.get() - batch.size()) / 100_000) {
                    System.err.printf("postgres: %,d rows%n", written.get());
                }
            } catch (Exception e) {
                failed.compareAndSet(null, e);
            } finally {
                if (c != null) {
                    pool.add(c);
                }
                inFlight.release();
            }
        });
    }

    /** One batch with COPY (text format). */
    private static void write(Connection c, List<Row> batch, Map<String, Boolean> columns) throws Exception {
        StringBuilder sql = new StringBuilder("COPY ").append(batch.get(0).schema()).append('.').append(PostgresLayout.TABLE)
                .append(" (kind, id, business_date, doc");
        columns.keySet().forEach(n -> sql.append(", \"").append(n).append('"'));
        sql.append(") FROM STDIN");
        StringBuilder data = new StringBuilder(batch.size() * 8_192);
        Map<String, String> paths = new HashMap<>();
        for (Row r : batch) {
            r.columns().keySet().forEach(p -> paths.putIfAbsent(PostgresLayout.column(p), p));
        }
        for (Row r : batch) {
            escape(data, r.kind()).append('\t');
            escape(data, r.id()).append('\t').append(r.date()).append('\t');
            escape(data, r.doc());
            for (Map.Entry<String, Boolean> col : columns.entrySet()) {
                Object v = r.columns().get(paths.get(col.getKey()));
                data.append('\t');
                if (v == null || col.getValue() && !(v instanceof Double)) {
                    data.append("\\N");
                } else if (v instanceof Double d) {
                    data.append(!col.getValue() && d == Math.rint(d) && !Double.isInfinite(d) ? String.valueOf(d.longValue()) : String.valueOf(d));
                } else {
                    escape(data, v.toString());
                }
            }
            data.append('\n');
        }
        ((PGConnection) c.unwrap(PGConnection.class)).getCopyAPI().copyIn(sql.toString(), new StringReader(data.toString()));
    }

    private static StringBuilder escape(StringBuilder out, String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(ch);
            }
        }
        return out;
    }

    /** Each kind's dates recorded; written partitions vacuumed and analysed; old months dropped. */
    private void finish(Connection admin, int keepMonths) throws Exception {
        Set<String> vacuum = new LinkedHashSet<>();
        for (var s : counts.entrySet()) {
            for (var k : s.getValue().entrySet()) {
                for (var d : k.getValue().entrySet()) {
                    PostgresLayout.recordDate(admin, s.getKey(), k.getKey(), d.getKey(), d.getValue().get());
                    vacuum.add(s.getKey() + "." + PostgresLayout.partition(d.getKey()));
                }
            }
        }
        try (Statement st = admin.createStatement()) {
            for (String p : vacuum) {
                st.execute("VACUUM (ANALYZE) " + p);                    // the visibility map: a day's ids from the index alone
            }
            if (keepMonths > 0) {
                for (String schema : counts.keySet()) {
                    LocalDate newest = counts.get(schema).values().stream().flatMap(m -> m.keySet().stream()).max(LocalDate::compareTo).orElseThrow();
                    LocalDate keepFrom = newest.withDayOfMonth(1).minusMonths(keepMonths - 1L);
                    for (String p : PostgresLayout.partitionsBefore(admin, schema, keepFrom)) {
                        st.execute("DROP TABLE " + schema + "." + p);
                        System.out.println("postgres: dropped " + schema + "." + p);
                    }
                    st.execute("DELETE FROM " + schema + "." + PostgresLayout.DATES + " WHERE business_date < '" + keepFrom + "'");
                }
            }
        }
    }

    private static Row parse(JsonFactory json, String line) throws java.io.IOException {
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
        return new Row(PostgresLayout.schema(domain), kind, id, LocalDate.parse(date), doc, columns);
    }

    private static Object skip(JsonParser p) throws java.io.IOException {
        p.skipChildren();
        return null;                                                       // a promoted path is a value, not an object
    }
}
