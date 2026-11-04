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
 * The load itself (DATALOAD_INBOX.md section 6): the parallel lake loader, {@code com.ash.drishti.lakeload.LakeLoad}, started as a child
 * Java process of the server with its own heap ({@code loader-heap}), at a lower priority ({@code nice}) and the server's temp folder, on a
 * load file this class writes (the loader's own keys: TOOLS.md section 4.13). A large or malformed file can never take the server's heap;
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

    /** The load file for {@code files} going where {@code route} says; written to {@code config}. */
    public static void writeConfig(Path config, Routes.Route r, List<Path> files, Path summary, Path rejects, double rejectMaxPercent, Path spill)
            throws IOException {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode s = root.putObject("source");
        ArrayNode list = s.putArray("files");
        files.forEach(f -> list.add(f.toAbsolutePath().toString()));
        s.put("pack", r.packDir().toAbsolutePath().toString());
        put(s, "connector", r.connector());
        put(s, "date", r.dateField());
        put(s, "as-of", r.asOf() == null ? null : r.asOf().toString());
        put(s, "date-from", r.asOf() == null ? r.dateFrom() : null);
        put(s, "id", r.key());
        ObjectNode t = root.putObject("target");
        t.put("format", r.format());
        t.put("lake", r.lake());
        t.put("kind", r.kind());
        put(t, "table", r.table());
        ObjectNode rj = root.putObject("rejects");
        rj.put("max-pct", rejectMaxPercent);
        rj.put("file", rejects.toAbsolutePath().toString());
        root.putObject("report").put("summary", summary.toAbsolutePath().toString());
        if (spill != null) {
            root.putObject("memory").put("spill-dir", spill.toAbsolutePath().toString());
        }
        Files.writeString(config, new YAMLMapper().writeValueAsString(root));
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
            cmd.addAll(launch);
            cmd.add("--config");
            cmd.add(config.toAbsolutePath().toString());
            cmd.addAll(extra);
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
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
