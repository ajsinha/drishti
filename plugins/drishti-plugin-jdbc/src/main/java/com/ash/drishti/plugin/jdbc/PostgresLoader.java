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

import com.ash.drishti.api.LoadGuard;
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
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.postgresql.PGConnection;

/**
 * Loads rows into PostgreSQL in {@link PostgresLayout}: {@code java -cp <plugin classpath>
 * com.ash.drishti.plugin.jdbc.PostgresLoader FILE|- [jdbc-url] [--user U] [--password P] [--writers N] [--batch N]
 * [--recreate] [--keep-months N] [--as-of yyyy-MM-dd] [--future-days N] [--zone Z] [--max-drop-share F]
 * [--force-drop]}. Each line is {@code {"domain", "kind", "id", "date", "doc", "columns": {path: value}}}, as
 * {@code make_data.py --jsonl} and {@code bulk_trades.py --jsonl} write it; {@code -} reads a stream.
 *
 * <p>Each data domain goes to {@code <domain>.entities}. A kind's business date is replaced whole, and readers never
 * see it half done ({@link PostgresStage}): rows are written with {@code COPY} in batches of {@code --batch} (2,000) on
 * {@code --writers} connections at once (8) into a stage nobody reads; once the stream has ended, each (kind, date)
 * is published in one transaction (the day's rows replaced, the day recorded in {@code entity_dates}), up to
 * {@code --writers} days at once. A load that fails or is killed leaves every day as it was. {@code --recreate}
 * builds each domain the stream reaches anew in a shadow schema and swaps it in in one transaction. A promoted path
 * seen with a value for the first time becomes a column ({@code double precision} for a number). At the end the
 * partitions written are vacuumed and analysed (so a day's ids come from the index alone).
 *
 * <p>Dates are guarded ({@link LoadGuard}): a row dated after tomorrow in the business zone is not loaded (the load
 * then ends with an error naming it), and {@code --keep-months N} drops the partitions older than the N newest months
 * counted back from {@code --as-of} (today), never from the newest date loaded, and refuses to drop more than
 * {@code --max-drop-share} of a domain's rows without {@code --force-drop}. {@code tools/load-postgres.sh} runs it.
 */
public final class PostgresLoader {

    private record Row(String schema, String kind, String id, LocalDate date, String doc, Map<String, Object> columns) {}

    private final String url;
    private final String user;
    private final String password;
    private final int writers;
    private final int batchSize;
    private final boolean recreate;
    private final LoadGuard guard;
    private final Map<String, PostgresStage> stages = new LinkedHashMap<>();
    private final AtomicReference<Exception> failed = new AtomicReference<>();
    private final AtomicLong written = new AtomicLong();

    private PostgresLoader(String url, String user, String password, int writers, int batchSize, boolean recreate, LoadGuard guard) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.writers = Math.max(1, writers);
        this.batchSize = Math.max(1, batchSize);
        this.recreate = recreate;
        this.guard = guard;
    }

    public static void main(String[] args) throws Exception {
        String file = args[0];
        String url = args.length > 1 && !args[1].startsWith("--") ? args[1] : env("DRISHTI_PG_URL", "jdbc:postgresql://localhost:5432/drishti");
        String user = option(args, "--user", env("DRISHTI_PG_USER", "drishti"));
        String password = option(args, "--password", env("DRISHTI_PG_PASSWORD", "drishti"));
        int writers = Integer.parseInt(option(args, "--writers", "8"));
        int batch = Integer.parseInt(option(args, "--batch", "2000"));
        int keepMonths = Integer.parseInt(option(args, "--keep-months", "0"));
        boolean recreate = List.of(args).contains("--recreate");
        new PostgresLoader(url, user, password, writers, batch, recreate, LoadGuard.fromArgs(args)).load(file, keepMonths);
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
        try (Connection admin = DriverManager.getConnection(url, user, password)) {
            try {
                for (int i = 0; i < writers; i++) {
                    pool.add(DriverManager.getConnection(url, user, password));
                }
                stage(admin, pool, file);
                System.err.printf("postgres: %,d rows staged in %,.1f s%n", written.get(), (System.nanoTime() - t0) / 1e9);
                publish(admin, pool);
            } finally {
                for (Connection c : pool) {
                    c.close();
                }
                for (PostgresStage s : stages.values()) {
                    try {
                        s.close(admin);                                // after a failure: the stage goes, the domain is as it was
                    } catch (Exception e) {
                        System.err.println("postgres: could not drop the stage of " + s + ": " + e.getMessage());
                    }
                }
            }
            vacuum(admin);
            retain(admin, keepMonths);
        }
        System.out.printf("postgres: loaded %,d rows in %,.1f s%n", written.get(), (System.nanoTime() - t0) / 1e9);
        guard.finish();
    }

    /** Reads the stream and COPYs every row into its domain's stage, on {@code writers} connections at once. */
    private void stage(Connection admin, BlockingQueue<Connection> pool, String file) throws Exception {
        Semaphore inFlight = new Semaphore(writers * 2);
        JsonFactory json = new JsonFactory();
        try (BufferedReader in = file.equals("-") ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8), 1 << 20)
                     : Files.newBufferedReader(Path.of(file), StandardCharsets.UTF_8);
             ExecutorService copy = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Row> batch = new ArrayList<>(batchSize);
            String line;
            while ((line = in.readLine()) != null && failed.get() == null) {
                if (line.isBlank()) {
                    continue;
                }
                Row row = parse(json, line);
                if (!guard.accept(row.date(), row.schema() + " " + row.kind() + " " + row.id())) {
                    continue;
                }
                if (!batch.isEmpty() && !batch.get(0).schema().equals(row.schema())) {
                    submit(copy, pool, inFlight, batch);
                    batch = new ArrayList<>(batchSize);
                }
                prepare(admin, row);
                batch.add(row);
                if (batch.size() == batchSize) {
                    submit(copy, pool, inFlight, batch);
                    batch = new ArrayList<>(batchSize);
                }
            }
            if (!batch.isEmpty() && failed.get() == null) {
                submit(copy, pool, inFlight, batch);
            }
            inFlight.acquire(writers * 2);                                 // every batch written
        }
        if (failed.get() != null) {
            throw failed.get();
        }
    }

    /** Before a row is staged: its domain's stage, its month's partition, its new columns, and its day counted. */
    private void prepare(Connection admin, Row row) throws Exception {
        PostgresStage s = stages.get(row.schema());
        if (s == null) {
            s = PostgresStage.open(admin, row.schema(), recreate);
            stages.put(row.schema(), s);
        }
        s.add(admin, row.kind(), row.date());
        for (Map.Entry<String, Object> e : row.columns().entrySet()) {
            if (e.getValue() != null && !s.columns().containsKey(PostgresLayout.column(e.getKey()))) {
                s.addColumn(admin, e.getKey(), e.getValue() instanceof Double);
            }
        }
    }

    private void submit(ExecutorService copy, BlockingQueue<Connection> pool, Semaphore inFlight, List<Row> batch) throws InterruptedException {
        PostgresStage s = stages.get(batch.get(0).schema());
        Map<String, Boolean> columns = new LinkedHashMap<>();                // the promoted columns these rows carry
        for (Row r : batch) {
            r.columns().forEach((path, v) -> {
                String c = PostgresLayout.column(path);
                if (s.columns().containsKey(c)) {
                    columns.put(c, s.columns().get(c));
                }
            });
        }
        String into = s.into();
        inFlight.acquire();
        copy.execute(() -> {
            Connection c = null;
            try {
                c = pool.take();
                write(c, into, batch, columns);
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
    private static void write(Connection c, String into, List<Row> batch, Map<String, Boolean> columns) throws Exception {
        StringBuilder sql = new StringBuilder("COPY ").append(into).append(" (kind, id, business_date, doc");
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

    /**
     * Publishes every staged day: a recreated domain swapped in whole; otherwise each (kind, date) in a transaction
     * of its own, up to {@code writers} at once. A day that fails to publish (a duplicate id) stays as it was.
     */
    private void publish(Connection admin, BlockingQueue<Connection> pool) throws Exception {
        long t0 = System.nanoTime();
        int published = 0;
        List<Future<Long>> done = new ArrayList<>();
        try (ExecutorService swaps = Executors.newVirtualThreadPerTaskExecutor()) {
            for (PostgresStage s : stages.values()) {
                if (s.recreate()) {
                    s.publishRecreated(admin);
                    published += s.days().values().stream().mapToInt(Map::size).sum();
                    continue;
                }
                s.indexStage(admin);
                for (var k : s.days().entrySet()) {
                    for (LocalDate day : k.getValue().keySet()) {
                        done.add(swaps.submit(() -> {
                            Connection c = pool.take();
                            try {
                                return s.publishDay(c, k.getKey(), day);
                            } finally {
                                pool.add(c);
                            }
                        }));
                    }
                }
            }
            Exception first = null;
            for (Future<Long> f : done) {
                try {
                    f.get();
                    published++;
                } catch (ExecutionException e) {
                    first = first == null && e.getCause() instanceof Exception x ? x : first;
                }
            }
            if (first != null) {
                throw first;
            }
        }
        System.err.printf("postgres: %d business days published in %,.1f s%n", published, (System.nanoTime() - t0) / 1e9);
    }

    /** The partitions written, vacuumed and analysed: the visibility map gives a day's ids from the index alone. */
    private void vacuum(Connection admin) throws Exception {
        try (Statement st = admin.createStatement()) {
            for (PostgresStage s : stages.values()) {
                for (String p : s.partitions()) {
                    st.execute("VACUUM (ANALYZE) " + p);
                }
            }
        }
    }

    /**
     * {@code --keep-months N}: in each domain loaded, the partitions of the months before the N newest counted back
     * from {@code --as-of} dropped with their dates in one transaction; refused when that is more than
     * {@code --max-drop-share} of the domain's rows.
     */
    private void retain(Connection admin, int keepMonths) throws Exception {
        if (keepMonths <= 0) {
            return;
        }
        LocalDate keepFrom = guard.keepFromMonths(keepMonths);
        for (String schema : stages.keySet()) {
            List<String> old = PostgresLayout.partitionsBefore(admin, schema, keepFrom);
            if (old.isEmpty()) {
                continue;
            }
            long[] rows = PostgresLayout.rowsBefore(admin, schema, keepFrom);
            guard.checkDrop(schema, rows[0], rows[1], "rows");
            boolean auto = admin.getAutoCommit();
            admin.setAutoCommit(false);
            try (Statement st = admin.createStatement()) {
                for (String p : old) {
                    st.execute("DROP TABLE " + schema + "." + p);
                }
                st.execute("DELETE FROM " + schema + "." + PostgresLayout.DATES + " WHERE business_date < '" + keepFrom + "'");
                admin.commit();
            } catch (Exception e) {
                admin.rollback();
                throw e;
            } finally {
                admin.setAutoCommit(auto);
            }
            old.forEach(p -> System.out.println("postgres: dropped " + schema + "." + p + " (keeping from " + keepFrom + ")"));
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
        if (domain == null || kind == null || id == null || date == null || doc == null) {
            throw new java.io.IOException("a row needs domain, kind, id, date and doc: " + (line.length() > 200 ? line.substring(0, 200) + "…" : line));
        }
        return new Row(PostgresLayout.schema(domain), kind, id, LocalDate.parse(date), doc, columns);
    }

    private static Object skip(JsonParser p) throws java.io.IOException {
        p.skipChildren();
        return null;                                                       // a promoted path is a value, not an object
    }
}
