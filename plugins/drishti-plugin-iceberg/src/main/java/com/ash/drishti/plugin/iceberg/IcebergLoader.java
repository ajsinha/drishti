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
package com.ash.drishti.plugin.iceberg;

import com.ash.drishti.api.LoadGuard;
import com.ash.drishti.plugin.iceberg.IcebergLayout.Row;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Loads JSON lines into Iceberg tables in {@link IcebergLayout}: {@code java -cp <plugin classpath>
 * com.ash.drishti.plugin.iceberg.IcebergLoader FILE|- [root] [options]}. Each line is {@code {"domain", "kind", "id",
 * "date", "doc", "columns": {path: value}}} ({@code columns}, the fields the pack promotes, is optional), as
 * {@code make_data.py --jsonl} and {@code bulk_trades.py --jsonl} write it; {@code -} reads a stream, so a book of
 * millions loads without a file of tens of gigabytes.
 *
 * <p>Rows may come in any order (the generator interleaves days and ids). Each business day of each kind is sorted by
 * id with an external sort ({@link SortedRuns}): at most {@code --buffer-mb} (1024) of rows are held, the largest
 * day's buffer is spilled to a sorted run file in {@code --spill-dir} when the budget is reached. Then each day is
 * merged back in id order, written into files of {@code --file-rows} (250000) rows with row groups of
 * {@code --row-group-mb} (1) compressed, and committed as one overwrite of that day: loading a day again replaces it (the small days of a table, under 100,000
 * rows each, are committed together in one overwrite of those days).
 * Up to {@code --threads} days are written at once; commits to one table are serialised.
 *
 * <p>Options: {@code --engine native|library|auto} (the engine that writes: as the connector's {@code engine}, else
 * {@code DRISHTI_ICEBERG_ENGINE}, else {@code native}; {@code native} needs no Hadoop, so it runs on Windows), {@code --catalog rest --uri U
 * --warehouse W --credential C --token T} for a REST catalog (the domain is
 * the namespace), {@code --set key=value} for any connector setting ({@code s3.endpoint}, {@code hadoop.…}),
 * {@code --keep-days N} to remove each loaded table's business days older than its newest N on or before
 * {@code --as-of} (today) afterwards and expire the snapshots that referenced them ({@link IcebergMaintenance}; more
 * than {@code --max-drop-share} of a table's rows needs {@code --force-drop}). A row dated after tomorrow in the
 * business zone ({@code --future-days}, {@code --zone}) is not loaded, and the load ends with an error naming it
 * ({@link LoadGuard}). {@code tools/load-iceberg.sh} runs it.
 */
public final class IcebergLoader {

    private IcebergLoader() {
    }

    /** A day with fewer rows is committed with the table's other small days: reference data loads in a few commits. */
    static final long SMALL_DAY = 100_000;

    private record Bucket(String domain, String kind, LocalDate date) {}

    private record Line(String domain, String kind, LocalDate date, Row row) {}

    /**
     * Runs a load; a failure ends it with one line on standard error and exit status 1, never a Java stack trace (QA3B-12: a REST
     * catalog whose bucket is missing ended in {@code Exception in thread "main" java.io.UncheckedIOException …} and eight frames).
     * {@code -Ddrishti.loader.trace=true} prints the stack trace as well.
     */
    public static void main(String[] args) {
        try {
            load(args);
        } catch (Exception | Error e) {
            System.err.println("iceberg: " + oneLine(e, settingsOf(args)));
            if (Boolean.getBoolean("drishti.loader.trace")) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    /** The settings the arguments give (for the words of a failure). */
    private static Map<String, String> settingsOf(String[] args) {
        Map<String, String> s = new HashMap<>();
        if (args.length > 1 && !args[1].startsWith("--")) {
            s.put("root", args[1]);
        }
        for (int i = 1; i + 1 < args.length; i++) {
            switch (args[i]) {
                case "--catalog", "--uri", "--warehouse" -> s.put(args[i].substring(2), args[i + 1]);
                case "--set" -> {
                    int eq = args[i + 1].indexOf('=');
                    if (eq > 0) {
                        s.put(args[i + 1].substring(0, eq), args[i + 1].substring(eq + 1));
                    }
                }
                default -> {
                    continue;
                }
            }
            i++;
        }
        return s;
    }

    /** A failure in one line: a missing bucket names the bucket, the endpoint and what to check; else the innermost message. */
    static String oneLine(Throwable e, Map<String, String> settings) {
        StringBuilder chain = new StringBuilder();
        Throwable root = e;
        for (Throwable c = e; c != null; c = c.getCause() == c ? null : c.getCause()) {
            root = c;
            String m = c.getMessage();
            if (m != null && !m.isBlank() && chain.indexOf(m) < 0) {
                chain.append(chain.length() == 0 ? "" : " / ").append(m);
            }
        }
        String all = chain.toString();
        String endpoint = settings.getOrDefault("s3.endpoint", "").isBlank() ? "AWS S3 (no s3.endpoint set)" : settings.get("s3.endpoint");
        String catalog = settings.getOrDefault("uri", "");
        if (all.contains("NoSuchBucket") || all.contains("bucket does not exist")) {
            String bucket = bucket(settings.getOrDefault("warehouse", "")).or(() -> bucket(settings.getOrDefault("root", "")))
                    .map(b -> "'" + b + "'").orElse("of the catalog's warehouse");
            return "the bucket " + bucket + " does not exist at " + endpoint + (catalog.isBlank() ? "" : " (said by the REST catalog at " + catalog + ")")
                    + ": create it, or check --warehouse, --set s3.endpoint and the catalog's own warehouse setting [" + first(all, 300) + "]";
        }
        if (!catalog.isBlank() && root instanceof java.net.ConnectException) {
            return "cannot reach the REST catalog at " + catalog + " (" + root.getClass().getSimpleName() + ": " + root.getMessage() + "): check --uri and that "
                    + "the catalog runs";
        }
        if (root instanceof java.net.ConnectException && !settings.getOrDefault("s3.endpoint", "").isBlank()) {
            return "cannot reach the object store at " + endpoint + " (" + root.getMessage() + "): check --set s3.endpoint and that the store runs";
        }
        return "the load failed: " + first(all.isEmpty() ? root.getClass().getName() : all, 600) + " (" + root.getClass().getSimpleName()
                + "; -Ddrishti.loader.trace=true prints the stack trace)";
    }

    private static java.util.Optional<String> bucket(String uri) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^s3[an]?://([^/]+)").matcher(uri.trim());
        return m.find() ? java.util.Optional.of(m.group(1)) : java.util.Optional.empty();
    }

    private static String first(String s, int n) {
        String one = s.replace('\n', ' ');
        return one.length() <= n ? one : one.substring(0, n - 1) + "…";
    }

    /** The load itself (tests call it, and see its exceptions). */
    static void load(String[] args) throws Exception {
        String file = args[0];
        Map<String, String> settings = new HashMap<>();
        settings.put("root", args.length > 1 && !args[1].startsWith("--") ? args[1] : com.ash.drishti.api.DataDir.under("iceberg"));
        int fileRows = IcebergLayout.DEFAULT_FILE_ROWS;
        long rowGroupBytes = IcebergLayout.DEFAULT_ROW_GROUP_BYTES;
        long budget = 1024L * 1024 * 1024;
        int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
        int keepDays = 0;
        Path spill = Path.of(System.getProperty("java.io.tmpdir"));
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            String v = i + 1 < args.length ? args[i + 1] : "";
            switch (a) {
                case "--catalog", "--uri", "--warehouse", "--credential", "--token", "--engine" -> settings.put(a.substring(2), v);
                case "--set" -> settings.put(v.substring(0, v.indexOf('=')), v.substring(v.indexOf('=') + 1));
                case "--file-rows" -> fileRows = Integer.parseInt(v);
                case "--row-group-mb" -> rowGroupBytes = (long) (Double.parseDouble(v) * 1024 * 1024);
                case "--buffer-mb" -> budget = Long.parseLong(v) * 1024 * 1024;
                case "--threads" -> threads = Integer.parseInt(v);
                case "--spill-dir" -> spill = Path.of(v);
                case "--keep-days" -> keepDays = Integer.parseInt(v);
                default -> {
                    continue;
                }
            }
            i++;
        }
        new IcebergLoader.Run(settings, fileRows, rowGroupBytes, budget, threads, spill.resolve("drishti-iceberg-" + ProcessHandle.current().pid()),
                LoadGuard.fromArgs(args)).load(file, keepDays);
    }

    /** One load: the read and sort phase, then the write phase. */
    static final class Run {
        private final Map<String, String> settings;
        private final int fileRows;
        private final long rowGroupBytes;
        private final long budget;
        private final int threads;
        private final Path spillDir;
        private final LoadGuard guard;
        private final Map<Bucket, SortedRuns> buckets = new LinkedHashMap<>();
        /** Per domain and kind, per promoted path: 1 when a number was seen, 2 when text was. */
        private final Map<String, Map<String, Integer>> seen = new HashMap<>();
        private final Map<String, LayoutLake> lakes = new ConcurrentHashMap<>();
        private final Map<String, ReentrantLock> commitLocks = new ConcurrentHashMap<>();
        /** Per table, its days of fewer than {@link #SMALL_DAY} rows, committed together once all are written. */
        private final Map<String, Map<LocalDate, List<?>>> smallDays = new ConcurrentHashMap<>();

        Run(Map<String, String> settings, int fileRows, long rowGroupBytes, long budget, int threads, Path spillDir, LoadGuard guard) {
            this.settings = settings;
            this.guard = guard;
            this.fileRows = fileRows;
            this.rowGroupBytes = rowGroupBytes;
            this.budget = budget;
            this.threads = threads;
            this.spillDir = spillDir;
        }

        void load(String file, int keepDays) throws Exception {
            long t0 = System.nanoTime();
            long n = read(file, t0);
            List<LayoutLake.LayoutTable> loaded = write(t0);
            if (keepDays > 0) {
                for (LayoutLake.LayoutTable t : loaded) {
                    t.keepDays(keepDays, guard);
                    t.expireSnapshots(168);                       // time travel keeps a week; older files go
                }
            }
            for (LayoutLake l : lakes.values()) {
                l.close();
            }
            System.out.printf("iceberg: loaded %,d rows into %d tables in %,.0f s%n", n, loaded.size(), (System.nanoTime() - t0) / 1e9);
            guard.finish();
        }

        /** Reads every line into its day's sorter, spilling the largest buffers beyond the budget. */
        private long read(String file, long t0) throws Exception {
            JsonFactory json = new JsonFactory();
            long n = 0;
            long buffered = 0;
            Semaphore spilling = new Semaphore(2);                   // spills in flight, each about a third of the budget
            try (BufferedReader in = file.equals("-") ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8), 1 << 20)
                    : Files.newBufferedReader(Path.of(file), StandardCharsets.UTF_8);
                 ExecutorService spills = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> written = new ArrayList<>();
                String text;
                while ((text = in.readLine()) != null) {
                    if (text.isBlank()) {
                        continue;
                    }
                    Line line = parse(json, text);
                    if (!guard.accept(line.date(), line.domain() + " " + line.kind() + " " + line.row().id())) {
                        continue;
                    }
                    Bucket b = new Bucket(line.domain(), line.kind(), line.date());
                    buffered += buckets.computeIfAbsent(b, k -> new SortedRuns(spillDir, k.kind() + "-" + k.date())).add(line.row());
                    Map<String, Integer> types = seen.computeIfAbsent(line.domain() + "/" + line.kind(), k -> new LinkedHashMap<>());
                    line.row().columns().forEach((path, v) -> types.merge(path, v == null ? 0 : v instanceof Number ? 1 : 2, (x, y) -> x | y));
                    if (++n % 100_000 == 0) {
                        System.err.printf("iceberg: %,d rows read (%,.0f s)%n", n, (System.nanoTime() - t0) / 1e9);
                    }
                    if (buffered > budget) {
                        SortedRuns largest = buckets.values().stream().max(Comparator.comparingLong(SortedRuns::bufferedBytes)).orElseThrow();
                        spilling.acquire();
                        SortedRuns.Taken taken = largest.take();
                        buffered -= taken.bytes();
                        written.add(spills.submit(() -> {
                            try {
                                largest.write(taken);
                            } finally {
                                spilling.release();
                            }
                        }));
                    }
                }
                for (Future<?> f : written) {
                    f.get();                                         // every run on disk, or the failure
                }
            }
            System.err.printf("iceberg: %,d rows read in %,.0f s; %d business days of %d tables to write%n", n, (System.nanoTime() - t0) / 1e9,
                    buckets.size(), seen.size());
            return n;
        }

        /** Writes each day (up to {@code threads} at once) and commits it; returns the tables written. */
        private List<LayoutLake.LayoutTable> write(long t0) throws Exception {
            Map<String, LayoutLake.LayoutTable> tables = new TreeMap<>();
            for (Bucket b : buckets.keySet()) {
                String key = b.domain() + "/" + b.kind();
                if (!tables.containsKey(key)) {
                    Map<String, Boolean> promoted = new LinkedHashMap<>();
                    seen.getOrDefault(key, Map.of()).forEach((path, t) -> promoted.put(path, t == 1));   // numbers only: a number column
                    tables.put(key, lake(b.domain()).open(b.kind(), promoted, rowGroupBytes, fileRows));
                }
            }
            Semaphore slots = new Semaphore(threads);
            try (ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> done = new ArrayList<>();
                for (Map.Entry<Bucket, SortedRuns> e : buckets.entrySet()) {
                    Bucket b = e.getKey();
                    String key = b.domain() + "/" + b.kind();
                    LayoutLake.LayoutTable table = tables.get(key);
                    slots.acquire();
                    done.add(writers.submit(() -> {
                        try (SortedRuns rows = e.getValue()) {
                            long d0 = System.nanoTime();
                            List<?> files = table.writeDay(b.date(), rows.merged(), fileRows);
                            long count = table.rows(files);
                            ReentrantLock lock = commitLocks.computeIfAbsent(key, k -> new ReentrantLock());
                            lock.lock();
                            try {
                                if (count >= SMALL_DAY) {
                                    table.commitDays(Map.of(b.date(), files));
                                } else {                         // small days of a table: one commit for all of them, below
                                    smallDays.computeIfAbsent(key, k -> new TreeMap<>()).put(b.date(), files);
                                }
                            } finally {
                                lock.unlock();
                            }
                            if (count >= SMALL_DAY) {
                                System.err.printf("iceberg: %s %s: %,d rows, %d files, %,.2f GB, %,.0f s%n", key, b.date(), count, files.size(),
                                        table.bytes(files) / 1e9, (System.nanoTime() - d0) / 1e9);
                            }
                        } finally {
                            slots.release();
                        }
                        return null;
                    }));
                }
                for (Future<?> f : done) {
                    f.get();
                }
                smallDays.forEach((key, days) -> tables.get(key).commitDays(days));
            } finally {
                buckets.values().forEach(SortedRuns::close);
                try (var files = Files.exists(spillDir) ? Files.list(spillDir) : java.util.stream.Stream.<Path>empty()) {
                    if (files.findAny().isEmpty()) {
                        Files.deleteIfExists(spillDir);
                    }
                }
            }
            System.err.printf("iceberg: written in %,.0f s%n", (System.nanoTime() - t0) / 1e9);
            return tables.values().stream().map(t -> {
                t.refresh();
                return t;
            }).toList();
        }

        private LayoutLake lake(String domain) {
            return lakes.computeIfAbsent(domain, d -> {
                Map<String, String> s = new HashMap<>(settings);
                s.put("domain", d);
                return LayoutLake.of(s);
            });
        }
    }

    private static Line parse(JsonFactory json, String line) throws IOException {
        String domain = "";
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
                                        : v == JsonToken.VALUE_NULL ? null : v.isStructStart() ? skip(p) : p.getText());
                            }
                        }
                    }
                    default -> p.skipChildren();
                }
            }
        }
        if (kind == null || id == null || date == null) {
            throw new IOException("a line needs kind, id and date: " + line.substring(0, Math.min(120, line.length())));
        }
        return new Line(domain, kind, LocalDate.parse(date), new Row(id, doc, columns));
    }

    private static Object skip(JsonParser p) throws IOException {
        p.skipChildren();
        return null;                                                  // a promoted path holding an object or list: no value
    }
}
