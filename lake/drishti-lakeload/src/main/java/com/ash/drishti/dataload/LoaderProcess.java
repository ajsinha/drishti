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
package com.ash.drishti.dataload;

import com.ash.drishti.common.TempFiles;
import com.ash.drishti.lakeload.LakeLoad;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The load itself (DATA_LAKE_GUIDE.md): the parallel lake loader, {@code com.ash.drishti.lakeload.LakeLoad}, started as a child
 * Java process of the server with its own heap ({@code loader-heap}), at a lower priority ({@code nice}) and the server's temp folder, on a
 * load file this class writes (the loader's own keys: CLI_REFERENCE.md section 4.13). A large or malformed file can never take the server's heap;
 * the loader's exit code says what happened (0 loaded, 2 loaded with rejects, 1 failed, 3 the table locked); a load past
 * {@code load-timeout} is stopped. There is no second loader: everything that reads or writes data is the loader's.
 */
public final class LoaderProcess implements LoadRunner {

    private static final Logger LOG = LoggerFactory.getLogger(LoaderProcess.class);
    private static final String MAIN = LakeLoad.class.getName();

    private final DataLoadSettings settings;
    private final List<String> launch;
    private volatile Process running;

    public LoaderProcess(DataLoadSettings settings) {
        this.settings = settings;
        this.launch = launch(System.getProperty("java.class.path", ""));
    }

    /**
     * How to start the loader from this process's class path: an executable server jar ({@code -jar}, its launcher told to start the
     * loader: {@code -Dloader.main}), or the plain class path (a development run, the tests).
     */
    static List<String> launch(String classPath) {
        String[] parts = classPath.split(File.pathSeparator);
        if (parts.length == 1 && parts[0].endsWith(".jar") && bootJar(Path.of(parts[0]))) {
            return List.of("-Dloader.main=" + MAIN, "-jar", parts[0]);
        }
        return List.of("-cp", classPath, MAIN);
    }

    private static boolean bootJar(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            return z.getEntry("BOOT-INF/") != null || z.getEntry("BOOT-INF/classes/") != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The load file for {@code files} going where {@code route} says; written to {@code config}. When the route names a lake of
     * {@code config/lakes/} (docs/guides/DATA_LAKE_GUIDE.md) and that lake has no entry for the kind yet, the pack's recommended entry is copied
     * into the lake file first and said so (decision L2): the table is then created exactly as the lake file says, and from then on the
     * lake file is the truth. The load's batch id (the files' names and sizes) is always written: a table whose lake entry says
     * {@code load-mode: append} records it in the commit, so a repeated or retried file is never appended twice.
     */
    public static void writeConfig(Path config, Routes.Route r, List<Path> files, Path summary, Path rejects, double rejectMaxPercent, Path spill)
            throws IOException {
        lakeEntry(r);
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode s = root.putObject("source");
        ArrayNode list = s.putArray("files");
        files.forEach(f -> list.add(f.toAbsolutePath().toString()));
        if (r.newDomain() == null) {
            s.put("pack", r.packDir().toAbsolutePath().toString());
        }
        put(s, "connector", r.connector());
        put(s, "date", r.dateField());
        put(s, "as-of", r.asOf() == null ? null : r.asOf().toString());
        put(s, "date-from", r.asOf() == null ? r.dateFrom() : null);
        put(s, "dataset", r.dataset());                         // a tar's one table folder this load is for
        put(s, "id", r.key());
        ObjectNode t = root.putObject("target");
        t.put("format", r.format());
        t.put("lake", r.lake());
        t.put("kind", r.kind());
        if (r.newDomain() != null) {
            t.put("domain", r.newDomain());                     // a new table: the pack's domain, the generic layout
        }
        // the table: the route's, else the pack connector's (trade-endur: trade_endur), which picks among several tables of one kind
        boolean named = r.lake() != null && r.lake().matches("[a-z0-9][a-z0-9_-]*");      // a lake of config/lakes/ (else a folder: the old form)
        put(t, "table", r.table() != null || !named || r.connector() == null || r.newDomain() != null ? r.table()
                : com.ash.drishti.lakeconfig.PackEntry.read(r.packDir(), r.kind(), r.connector()).table());
        // the mode (DATA_LAKE_GUIDE.md section 5): the .ready file's, else the lake file's entry for the table, else the inbox's default
        t.put(r.modeGiven() ? "mode" : "default-mode", "append".equals(r.mode()) ? "append" : "replace-date");
        t.put("batch-id", batchId(files));
        ObjectNode rj = root.putObject("rejects");
        rj.put("max-pct", rejectMaxPercent);
        rj.put("file", rejects.toAbsolutePath().toString());
        root.putObject("report").put("summary", summary.toAbsolutePath().toString());
        if (spill != null) {
            root.putObject("memory").put("spill-dir", spill.toAbsolutePath().toString());
        }
        Files.writeString(config, new YAMLMapper().writeValueAsString(root));
    }

    /**
     * A lake named by the route that lacks the kind gets the pack's entry, once; a lake with no file yet (a {@code .ready} file's or a rule's
     * {@code lake: risklake}) is created first: {@code config/lakes/<lake>.yaml}, a Delta lake in its own folder {@code <data>/delta/<lake>},
     * with the pack's recommended entry for the kind (DATA_LAKE_GUIDE.md section 4). A lake the shipped files describe (the demo lake on a
     * server without its file) is never shadowed by a new file.
     */
    static void lakeEntry(Routes.Route r) throws IOException {
        if (r.lake() == null || !com.ash.drishti.lakeconfig.Lakes.validName(r.lake())) {
            return;                                         // a folder (the old form): the loader reads it with its one warning
        }
        Path dir = com.ash.drishti.lakeconfig.Lakes.dir();
        Path file = dir.resolve(r.lake() + ".yaml");
        if (!Files.isRegularFile(file)) {
            if (com.ash.drishti.lakeconfig.Lakes.shipped().contains(r.lake())) {
                return;                                     // a shipped lake (demo) read from the jar: its entries are the truth
            }
            List<com.ash.drishti.lakeconfig.LakeTable> tables = r.newDomain() != null ? List.of() : List.of(packEntry(r));
            if (com.ash.drishti.lakeconfig.LakeFiles.create(dir, r.lake(), tables, "the data inbox for the pack " + r.pack() + "'s " + r.kind())) {
                LOG.info("lake {} did not exist: {} was created (its tables under <data>/delta/{}/), with the pack {}'s recommended entry for {}; "
                        + "point the pack's connectors at it (lake: {}) and restart to read it", r.lake(), file, r.lake(), r.pack(), r.kind(), r.lake());
                return;
            }
        }
        com.ash.drishti.lakeconfig.Lake lake = com.ash.drishti.lakeconfig.Lakes.readFile(file, System::getenv);
        com.ash.drishti.lakeconfig.LakeTable entry = packEntry(r);
        // the table's entry already there; or, with no connector naming one table, any table of the kind (the lake file is the truth)
        if (lake.tables().containsKey(entry.name())
                || (r.connector() == null && lake.tables().values().stream().anyMatch(x -> x.kind().equals(r.kind())))) {
            return;
        }
        if (com.ash.drishti.lakeconfig.LakeFiles.addTable(file, entry)) {
            LOG.info("lake {} had no table for {}: the pack {}'s recommended entry {} was copied into {}; edit it there from now on", r.lake(),
                    r.kind(), r.pack(), entry.name(), file);
        }
    }

    /**
     * The pack's recommended entry for the route's table (its connector's, else the kind's), its {@code id:} the {@code .ready} file's or
     * rule's {@code key:}, else the kind's key (the pack's ingest key, else its data contract's): a table created by its first load takes
     * the key, so its rows are never all rejected for want of an id.
     */
    static com.ash.drishti.lakeconfig.LakeTable packEntry(Routes.Route r) throws IOException {
        com.ash.drishti.lakeconfig.LakeTable t = com.ash.drishti.lakeconfig.PackEntry.read(r.packDir(), r.kind(), r.connector()).toTable(r.kind());
        return r.key() != null ? t.withId(r.key()) : t;
    }

    /** The batch id of a load: its files' names and sizes, so the same file dropped again is the same batch. */
    static String batchId(List<Path> files) throws IOException {
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        List<Path> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(f -> f.getFileName().toString()));
        for (Path f : sorted) {
            md.update((f.getFileName() + ":" + Files.size(f) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return "inbox-" + java.util.HexFormat.of().formatHex(md.digest()).substring(0, 16);
    }

    private static void put(ObjectNode n, String key, String value) {
        if (value != null) {
            n.put(key, value);
        }
    }

    @Override
    public Result run(Path config, List<String> extra, Path log) {
        Path tmp = null;
        try {
            tmp = TempFiles.createDir("dataload-");
            List<String> cmd = new ArrayList<>();
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
            Path nice = Path.of("/usr/bin/nice");
            if (settings.nice() && !windows && Files.isExecutable(nice)) {
                cmd.addAll(List.of(nice.toString(), "-n", "10"));
            }
            cmd.add(Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString());
            cmd.add("-Xmx" + settings.loaderHeap());
            cmd.add("-Djava.io.tmpdir=" + tmp.toAbsolutePath());
            cmd.add("-Ddrishti.tmp-dir=" + tmp.toAbsolutePath());
            cmd.add("-D" + com.ash.drishti.lakeconfig.Lakes.PROPERTY + "=" + com.ash.drishti.lakeconfig.Lakes.dir().toAbsolutePath());
            cmd.addAll(launch);
            cmd.add("--config");
            cmd.add(config.toAbsolutePath().toString());
            cmd.addAll(extra);
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            // the settings the lake files refer to, as this server resolves them (a DRISHTI_DELTA_ROOT given as a --setting included):
            // the loader reads the same lake files through the same reader and lands where this server's connectors read
            pb.environment().putAll(com.ash.drishti.lakeconfig.Lakes.environment());
            Process p = pb.start();
            running = p;
            boolean done = p.waitFor(settings.loadTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!done) {
                stop(p);
                return new Result(LakeLoad.FAILED, true, "the load took longer than load-timeout (" + human(settings.loadTimeout()) + ") and was stopped");
            }
            return new Result(p.exitValue(), false, tail(log));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Process p = running;
            if (p != null) {
                stop(p);
            }
            return new Result(LakeLoad.FAILED, true, "the server stopped while the load ran");
        } catch (IOException e) {
            return new Result(LakeLoad.FAILED, false, "the loader could not be started: " + e.getMessage());
        } finally {
            running = null;
            delete(tmp);
        }
    }

    /** Stops a load that runs now (the server stopping, the engine turned off). */
    public void stopRunning() {
        Process p = running;
        if (p != null) {
            stop(p);
        }
    }

    private static void stop(Process p) {
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
        try {
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /** The log's last lines (what the loader said last: the reason of a failure). */
    static String tail(Path log) {
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 12), lines.size())).strip();
        } catch (IOException e) {
            return "";
        }
    }

    private static String human(Duration d) {
        return d.toMinutes() >= 1 ? d.toMinutes() + " min" : d.toSeconds() + " s";
    }

    private static void delete(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.debug("data inbox: {} could not be removed: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            LOG.debug("data inbox: {} could not be removed: {}", dir, e.getMessage());
        }
    }
}
