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
package com.ash.drishti.lakeload;

import com.ash.drishti.lakeio.LocalPaths;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The parallel lake loader's command line (docs/guides/TOOLS.md section 4.13):
 *
 * <pre>
 * java -Xmx8g -Djava.io.tmpdir=/data/tmp -cp &lt;class path&gt; com.ash.drishti.lakeload.LakeLoad --config load.yaml [--set key.path=value ...]
 *     [--summary FILE] [--no-lock] [--no-run-log] [--dry-run] [--json] [-q]
 * java -Xmx8g -Dloader.main=com.ash.drishti.lakeload.LakeLoad -jar drishti-server-&lt;v&gt;-exec.jar --config load.yaml
 * </pre>
 *
 * Exit codes as {@code drishti.py lake build}'s (section 4.6): 0 loaded, 2 loaded with rejects, 1 failed (no date that was not committed
 * changed), 3 another run holds the table's lock. A local lake gets lake build's run log and summary under {@code <lake>/_drishti-lake};
 * the load manifest is written beside the summary ({@code <run>.manifest.json}). As a library: {@link #run(String[], PrintStream)}.
 */
public final class LakeLoad {

    public static final int OK = 0;
    public static final int FAILED = 1;
    public static final int PARTIAL = 2;
    public static final int LOCKED = 3;

    private LakeLoad() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    /**
     * Whether the loader reads a file of this name: JSON Lines ({@code .jsonl}, {@code .ndjson}, {@code .json}; plain, {@code .gz} or
     * {@code .zst}) or an archive ({@code .zip}, {@code .tar}, {@code .tar.gz}, {@code .tgz}) by the archive rule. The data inbox
     * (drishti-dataload) asks before it claims a file, so its names are the loader's own.
     */
    public static boolean reads(String fileName) {
        return fileName != null && (Archives.isJsonl(fileName) || Archives.isArchive(fileName));
    }

    /** Runs a load as the command line would; returns the exit code. */
    /** The lake domain of a pack's tables (its first Delta or Iceberg connector's {@code domain}, else the pack's name). */
    public static String packDomain(java.nio.file.Path pack) throws java.io.IOException {
        return com.ash.drishti.lakeconfig.PackEntry.read(pack, "_", null).domain();   // no table of the kind: the pack's first Delta or Iceberg connector's domain, else its name
    }

    /**
     * The tables and dates a tar feed holds ({@code <table>/<YYYY-MM-DD>/}; DATA_ARCHITECTURE.md section 10.3.1), checked as the loader checks
     * it (every problem refuses it, an {@link java.io.IOException} saying them all); a local file.
     */
    public static Map<String, java.util.SortedSet<java.time.LocalDate>> tarTables(String path) throws java.io.IOException {
        Map<String, java.util.SortedSet<java.time.LocalDate>> out = new java.util.TreeMap<>();
        try (com.ash.drishti.iceberg.io.FileAccess io = new com.ash.drishti.iceberg.io.FileAccess(com.ash.drishti.lakeio.S3Settings.from(Map.of()),
                com.ash.drishti.iceberg.io.Relocation.none(), null)) {
            Archives.Plan p = Archives.plan(io, path, java.nio.file.Files.size(java.nio.file.Path.of(path)), null,
                    Archives.Limits.defaults(System::getenv));
            p.datasets().forEach((table, members) -> members.forEach(m -> out.computeIfAbsent(table, k -> new java.util.TreeSet<>()).add(m.date())));
        }
        return out;
    }

    /** The run of this thread's {@link #run}: one id for every dataset it loads, so an archive's commits share their load ids. */
    private static final ThreadLocal<String> RUN = new ThreadLocal<>();
    private static final java.util.concurrent.atomic.AtomicInteger RUNS = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * The current run's id, else a new one: {@code yyyyMMdd'T'HHmmss.SSS-<pid>[-<n>]}, in time order, so the newest load's rows sort last
     * ({@code _load_id} is {@code <run>.<file>}).
     */
    static String runId() {
        String r = RUN.get();
        if (r != null) {
            return r;
        }
        int n = RUNS.incrementAndGet();
        return java.time.OffsetDateTime.now(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS"))
                + "-" + ProcessHandle.current().pid() + (n > 1 ? "-" + n : "");
    }

    public static int run(String[] args, PrintStream out) {
        RUN.set(null);
        String given = null;
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].equals("--run-id")) {
                given = args[i + 1];                    // one id for every table of an archive the inbox loads table by table
            }
        }
        RUN.set(given != null ? given : runId());
        try {
            return runOnce(args, out);
        } finally {
            RUN.remove();
        }
    }

    private static int runOnce(String[] args, PrintStream out) {
        long t0 = System.nanoTime();
        String config = null;
        List<String> sets = new ArrayList<>();
        boolean json = false;
        boolean quiet = false;
        try {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--config" -> config = next(args, ++i, a);
                    case "--set" -> sets.add(next(args, ++i, a));
                    case "--summary" -> sets.add("report.summary=" + next(args, ++i, a));
                    case "--progress" -> sets.add("report.progress=" + next(args, ++i, a));
                    case "--no-lock" -> sets.add("lock=false");
                    case "--no-run-log" -> sets.add("report.run-log=false");
                    case "--dry-run" -> sets.add("report.dry-run=true");
                    case "--dataset" -> sets.add("source.dataset=" + next(args, ++i, a));
                    // the options of every loader (docs/architecture/DATA_ARCHITECTURE.md section 10.8), over the configuration file
                    case "--as-of" -> sets.add("source.as-of=" + next(args, ++i, a));
                    case "--table" -> sets.add("target.table=" + next(args, ++i, a).toLowerCase(java.util.Locale.ROOT));
                    case "--pack" -> sets.add("source.pack=" + next(args, ++i, a));
                    case "--kind" -> sets.add("target.kind=" + next(args, ++i, a));
                    case "--mode" -> sets.add("target.mode=" + next(args, ++i, a));
                    case "--key" -> sets.add("source.id=" + next(args, ++i, a));
                    case "--date-from" -> sets.add("source.date-from=" + next(args, ++i, a));
                    case "--plan" -> sets.add("report.dry-run=true");
                    case "--run-id" -> i++;                     // read by run(): the run's id (its rows' load ids are <run-id>.<file>)
                    case "--json" -> json = true;
                    case "-q", "--quiet" -> quiet = true;
                    case "-h", "--help" -> {
                        out.println("usage: LakeLoad --config FILE [--set key.path=value ...] [--as-of YYYY-MM-DD] [--table T] [--pack DIR] "
                                + "[--kind K] [--mode append|replace] [--key FIELD] [--date-from auto|name] [--plan] [--summary FILE] [--progress FILE] "
                                + "[--no-lock] [--no-run-log] [--dry-run] [--dataset NAME] [--json] [-q]   (docs/guides/TOOLS.md section 4.13, "
                                + "docs/architecture/DATA_ARCHITECTURE.md section 10)");
                        return OK;
                    }
                    default -> throw new IllegalArgumentException("unknown argument " + a + " (--help)");
                }
            }
            if (config == null && sets.isEmpty()) {
                throw new IllegalArgumentException("give --config FILE (docs/guides/TOOLS.md section 4.13)");
            }
        } catch (IllegalArgumentException e) {
            out.println("lakeload: " + e.getMessage());
            return FAILED;
        }
        LoadConfig cfg;
        try {
            cfg = LoadConfig.read(config == null ? null : Path.of(config), sets);
        } catch (IOException | IllegalArgumentException e) {
            out.println("lakeload: the configuration: " + e.getMessage());
            return FAILED;
        }
        return run(cfg, out, json, quiet, t0);
    }

    private static String next(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args[i];
    }

    static int run(LoadConfig cfg, PrintStream out, boolean json, boolean quiet, long t0) {
        List<DatasetLoads.Load> loads;
        try {
            loads = DatasetLoads.plan(cfg, out, quiet);
        } catch (IOException | IllegalArgumentException e) {
            out.println("lakeload: " + (e instanceof Archives.ArchiveException ? "" : "the input: ") + e.getMessage());
            return FAILED;
        }
        if (loads == null) {
            return runOne(cfg, null, out, json, quiet, t0).code();
        }
        if (cfg.datasets().isEmpty()) {
            return runOne(loads.get(0).cfg(), loads.get(0).files(), out, json, quiet, t0).code();
        }
        return DatasetLoads.runAll(loads, cfg, out, json, quiet, t0);
    }

    /** One table's load: {@code files} its input when resolved already (an archive's members among it), else {@code source.files}. */
    static DatasetLoads.Result runOne(LoadConfig cfg, List<Inputs.InputFile> files, PrintStream out, boolean json, boolean quiet, long t0) {
        Loader loader;
        try {
            loader = new Loader(cfg, files);
        } catch (IllegalArgumentException e) {
            out.println("lakeload: the configuration: " + e.getMessage());
            return new DatasetLoads.Result(FAILED, LoadReport.JSON.createObjectNode().put("status", "failed").put("error", e.getMessage()));
        }
        LoadReport report = loader.report;
        String error = null;
        boolean rest = cfg.target().isIceberg() && "rest".equalsIgnoreCase(cfg.target().iceberg().getOrDefault("catalog", ""));
        boolean local = LocalPaths.isLocal(cfg.lakeRoot()) && !rest;
        Path lake = local ? Path.of(LocalPaths.toLocal(cfg.lakeRoot(), LocalPaths.WINDOWS)) : null;
        String[] dt = cfg.domainAndTable();
        AutoCloseable lock = null;
        try {
            if (lake != null && cfg.lock() && !cfg.report().dryRun()) {
                lock = LakeState.lock(lake, dt[0], dt[1]);
            }
            loader.run();
        } catch (LakeState.Locked e) {
            report.status = "locked";
            error = e.getMessage();
        } catch (Loader.LoadException | IllegalArgumentException | IllegalStateException e) {
            error = e.getMessage();
        } catch (Exception | OutOfMemoryError e) {
            error = oneLine(e);
            if (Boolean.getBoolean("drishti.loader.trace")) {
                e.printStackTrace(out);
            }
        } finally {
            if (lock != null) {
                try {
                    lock.close();
                } catch (Exception e) {
                    // the operating system releases it as the process ends
                }
            }
        }
        if (error != null && !report.status.equals("locked")) {
            report.status = "failed";
        }
        ObjectNode summary = report.summary(error, (System.nanoTime() - t0) / 1e9);
        summary.put("peakMemoryMb", report.peakHeapBytes / 1048576);
        try {
            write(cfg, report, summary, lake);
        } catch (IOException e) {
            out.println("lakeload: the summary could not be written: " + e.getMessage());
        }
        if (json) {
            out.println(summary.toPrettyString());
        } else {
            if (!quiet) {
                print(summary, out);
            }
            out.println(report.logLine(summary));
        }
        return new DatasetLoads.Result(report.exitCode(), summary);
    }

    /** The summary and the load manifest to their files; a local lake's run log. */
    private static void write(LoadConfig cfg, LoadReport report, ObjectNode summary, Path lake) throws IOException {
        Path file = cfg.report().summary() == null ? null : Path.of(cfg.report().summary());
        boolean runLog = lake != null && cfg.report().runLog() && !report.status.equals("locked") && !cfg.report().dryRun();
        Path manifest = cfg.report().manifest() != null ? Path.of(cfg.report().manifest()) : null;
        if (manifest == null && !report.commits.isEmpty()) {
            if (file != null) {
                manifest = file.resolveSibling(file.getFileName().toString().replaceAll("\\.json$", "") + ".manifest.json");
            } else if (runLog) {
                manifest = lake.resolve(LakeState.STATE).resolve("runs").resolve(report.run + ".manifest.json");
            }
        }
        if (manifest != null && !report.commits.isEmpty()) {
            if (manifest.toAbsolutePath().getParent() != null) {
                Files.createDirectories(manifest.toAbsolutePath().getParent());
            }
            Files.writeString(manifest, report.manifest().toPrettyString());
            summary.put("manifestFile", manifest.toAbsolutePath().toString());
        }
        if (runLog) {
            Path mine = LakeState.record(lake, report.run, summary, report.logLine(summary));
            summary.put("summaryFile", (file != null ? file : mine).toAbsolutePath().toString());
        }
        if (file != null) {
            if (file.toAbsolutePath().getParent() != null) {
                Files.createDirectories(file.toAbsolutePath().getParent());
            }
            summary.put("summaryFile", file.toAbsolutePath().toString());
            Files.writeString(file, summary.toPrettyString());
        }
    }

    private static void print(ObjectNode s, PrintStream out) {
        if (s.has("error")) {
            out.println("lakeload: " + s.path("error").asText());
        }
        out.println("loader: " + s.path("format").asText() + " table " + s.path("table").asText());
        s.path("rows").fields().forEachRemaining(e -> out.printf("  %s  %,d rows%s%n", e.getKey(), e.getValue().asLong(),
                s.path("commit").path("dates").has(e.getKey()) ? "  (version " + s.path("commit").path("dates").path(e.getKey()).path("version").asText()
                        + ", " + s.path("commit").path("dates").path(e.getKey()).path("files").asInt() + " file(s))" : ""));
        out.printf("documents read: %,d, rows written: %,d, rejected: %,d%n", s.path("documents").asLong(),
                s.path("status").asText().equals("failed") || s.path("dryRun").asBoolean() ? 0 : s.path("accepted").asLong(), s.path("rejected").asLong());
        s.path("rejects").path("byReason").fields().forEachRemaining(e -> out.printf("  rejected %,d: %s%n", e.getValue().asLong(), e.getKey()));
        int shown = 0;
        for (var r : s.path("rejects").path("first")) {
            if (shown++ == 5) {
                break;
            }
            out.println("    e.g. " + r.path("where").asText() + ": " + r.path("reason").asText());
        }
        s.path("duplicates").fields().forEachRemaining(e -> out.printf("  %,d id(s) appear more than once on %s%n", e.getValue().asLong(), e.getKey()));
        int dated = 0;
        for (var f : s.path("fileDates")) {
            if (dated++ == 10) {
                out.println("  ... and " + (s.path("fileDates").size() - 10) + " more files dated by their names");
                break;
            }
            out.println("  business date " + f.path("date").asText() + " from the name of " + f.path("file").asText());
        }
        s.path("dateNotes").forEach(n -> out.println("  " + n.asText()));
        var tp = s.path("throughput");
        tp.fields().forEachRemaining(e -> {
            if (e.getValue().isObject()) {
                var m = e.getValue();
                out.printf("  %-11s %2d threads  %8.1f s  %s%s%n", e.getKey(), m.path("threads").asInt(), m.path("wallSeconds").asDouble(),
                        m.has("rowsPerSecond") ? String.format("%,d rows/s", m.path("rowsPerSecond").asLong()) : "",
                        m.has("mbInPerSecond") ? String.format("  %,.1f MB/s in", m.path("mbInPerSecond").asDouble())
                                : m.has("mbOutPerSecond") ? String.format("  %,.1f MB/s out", m.path("mbOutPerSecond").asDouble()) : "");
            }
        });
        var mem = s.path("memory");
        out.printf("  memory: budget %,d MB, peak heap used %,d MB of %,d MB, spilled %,d MB%n", mem.path("budgetMb").asLong(),
                mem.path("peakHeapUsedMb").asLong(), mem.path("maxHeapMb").asLong(), mem.path("spilledMb").asLong());
        if (s.has("summaryFile")) {
            out.println("summary: " + s.path("summaryFile").asText());
        }
        if (s.has("manifestFile")) {
            out.println("load manifest: " + s.path("manifestFile").asText());
        }
    }

    /** A failure in one line: the innermost message (never a stack trace; -Ddrishti.loader.trace=true prints one). */
    static String oneLine(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof Archives.ArchiveException) {
                return c.getMessage();                                   // the archive rule's refusal says it all
            }
        }
        StringBuilder chain = new StringBuilder();
        for (Throwable c = e; c != null; c = c.getCause() == c ? null : c.getCause()) {
            String m = c.getMessage();
            if (m != null && !m.isBlank() && chain.indexOf(m) < 0) {
                chain.append(chain.length() == 0 ? "" : " / ").append(m);
            }
        }
        if (e instanceof OutOfMemoryError) {
            return "out of memory: lower memory.budget-mb or raise -Xmx (" + chain + ")";
        }
        return "the load failed: " + (chain.length() == 0 ? e.getClass().getSimpleName() : chain) + " (" + e.getClass().getSimpleName() + ")";
    }
}
