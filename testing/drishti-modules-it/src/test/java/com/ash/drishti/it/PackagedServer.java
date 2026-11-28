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
package com.ash.drishti.it;

import com.ash.lake.tck.TestRoots;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A real server started the way an install starts it (G14): the exec jar of this reactor, the modules as the {@code .dmod} packages beside it,
 * the site's {@code config/} linked in, a Delta lake of the committed test data, its own data folder. Nothing of the kernel's or of a module's
 * class path is shared with the test: the answers come over HTTP, as a client reads them. The server is the test's own, on a free port; it is
 * stopped by the process this class started, never by a name.
 */
final class PackagedServer implements AutoCloseable {

    static final Path REPO = Path.of(TestRoots.repo()).toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();

    private final Path work;
    private final Process process;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    final String base;

    private PackagedServer(Path work, Process process, int port) {
        this.work = work;
        this.process = process;
        this.base = "http://127.0.0.1:" + port;
    }

    /** The server's process id (the speed check reads its resident memory). */
    long pid() {
        return process.pid();
    }

    /** The exec jar of this reactor, as the pom names it. */
    static Path execJar() {
        String name = System.getProperty("drishti.exec.jar");
        if (name == null || !Files.isRegularFile(Path.of(name))) {
            throw new IllegalStateException("no exec jar at " + name + ": the server jar is built by drishti-app (./mvnw package -pl testing/drishti-modules-it -am)");
        }
        return Path.of(name).toAbsolutePath().normalize();
    }

    /** The packages of this reactor's build (packaging/dist), the folder the pom copied to target/modules-home. */
    static Path packages() {
        Path home = Path.of(System.getProperty("drishti.modules.home"));
        try (Stream<Path> s = Files.list(home)) {
            if (s.noneMatch(p -> p.getFileName().toString().endsWith(".dmod"))) {
                throw new IllegalStateException(home + " holds no .dmod package: packaging/dist builds them");
            }
        } catch (IOException e) {
            throw new IllegalStateException("no modules folder " + home, e);
        }
        return home.toAbsolutePath().normalize();
    }

    /**
     * The Python that builds the tests' lakes, found the way the drill finds its own: {@code -Ddrishti.python=<python>} when given, else the console
     * environment {@code drishti-console/.venv} of this checkout. It must import {@code drishti_lake} (installed there, or the pinned wheel in
     * {@code lib/wheels}, tools/lake/lakewheel.py); when it cannot, the test fails saying what to put where.
     */
    static String lakePython() throws IOException, InterruptedException {
        String given = System.getProperty("drishti.python", "").trim();
        Path venv = REPO.resolve("drishti-console/.venv");
        String py = !given.isEmpty() ? given : venv.resolve(System.getProperty("os.name").toLowerCase().contains("win") ? "Scripts/python.exe" : "bin/python").toString();
        if (given.isEmpty() && !Files.isRegularFile(Path.of(py))) {
            throw new IllegalStateException("the lake tests need a Python with drishti_lake: " + py + " does not exist. Create the console environment "
                    + "(python3 -m venv drishti-console/.venv; drishti-console/.venv/bin/pip install -r drishti-console/requirements.txt; the pinned wheel goes in lib/wheels, see lib/lake-wheel.pin) "
                    + "or pass -Ddrishti.python=<a python that has it>");
        }
        Process p = new ProcessBuilder(py, "-c", "import sys; sys.path.insert(0, sys.argv[1]); import lakewheel", REPO.resolve("tools/lake").toString())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("the lake tests need a Python with drishti_lake, and " + py + " has none: " + out.strip()
                    + "\nInstall drishti-console/requirements.txt into it, or put the pinned wheel in " + REPO.resolve("lib/wheels") + ", or pass -Ddrishti.python=<a python that has it>");
        }
        return py;
    }

    /**
     * Builds the lake of the committed test data (the dates the golden holds) by the one loader, the server's exec jar, into {@code lake},
     * with the connector files beside it; a lake already built there is kept (a stamp file says it is whole).
     */
    static void buildLake(Path folder, String only) throws IOException, InterruptedException {
        if (Files.exists(folder.resolve("BUILT"))) {
            return;
        }
        // test classes run in parallel JVMs on one lake folder: one builds, the others wait for its stamp
        Files.createDirectories(folder.toAbsolutePath().getParent());
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(folder.resolveSibling(folder.getFileName() + ".build.lock"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE); java.nio.channels.FileLock lock = ch.lock()) {
            if (!Files.exists(folder.resolve("BUILT"))) {
                buildLockedLake(folder, only);
            }
        }
    }

    private static void buildLockedLake(Path folder, String only) throws IOException, InterruptedException {
        Path stamp = folder.resolve("BUILT");
        Files.createDirectories(folder);
        List<String> cmd = new ArrayList<>(List.of(lakePython(), REPO.resolve("tools/drishti.py").toString(), "lake", "demo",
                "--lake", folder.resolve("delta").toString(), "--data-dir", folder.resolve("data").toString(), "--connectors-dir", folder.resolve("connectors").toString(),
                "--as-of", "data", "--only", only, "--jar", execJar().toString(), "--no-signal", "-q"));
        Path log = folder.resolve("lake.log");
        // the tools read where everything is from the server jar in <home>/lib: put the reactor's there when none is (and take it away after)
        Path lib = Files.createDirectories(REPO.resolve("lib"));
        Path placed = lib.resolve(execJar().getFileName());
        boolean put = false;
        try (Stream<Path> jars = Files.list(lib)) {
            if (jars.noneMatch(j -> j.getFileName().toString().startsWith("drishti-server-") && j.toString().endsWith("-exec.jar"))) {
                Files.copy(execJar(), placed);
                put = true;
            }
        }
        try {
            runLake(cmd, log);
        } finally {
            if (put) {
                Files.deleteIfExists(placed);
            }
        }
        Files.writeString(stamp, "built\n");
    }

    private static void runLake(List<String> cmd, Path log) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(REPO.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        // the lake build boots the modules (the resolver): into the lake folder's own data and temp folders, never the checkout's data/
        Path data = log.getParent().resolve("data");
        pb.environment().put("DRISHTI_DATA_DIR", data.toString());
        pb.environment().put("DRISHTI_TMP_DIR", log.getParent().resolve("tmp").toString());
        Process p = pb.start();
        if (!p.waitFor(40, java.util.concurrent.TimeUnit.MINUTES)) {
            p.destroyForcibly().waitFor();
            throw new IllegalStateException("the lake build did not end in 40 minutes: " + log);
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("the lake build failed (" + p.exitValue() + "): " + tail(log));
        }
    }

    /** Starts the server over {@code lake} (a folder buildLake made) in {@code work}; returns when its readiness is UP and its VaR engine answers. */
    static PackagedServer start(Path work, Path lake, String today) throws IOException, InterruptedException {
        return start(work, lake, today, Map.of());
    }

    /** As {@link #start(Path, Path, String)}, with the environment changed by {@code overrides} (an empty value removes the variable). */
    static PackagedServer start(Path work, Path lake, String today, Map<String, String> overrides) throws IOException, InterruptedException {
        Files.createDirectories(work);
        Path config = Files.createDirectories(work.resolve("config"));
        try (Stream<Path> s = Files.list(REPO.resolve("config"))) {
            for (Path c : s.toList()) {
                String n = c.getFileName().toString();
                if (!n.equals("connectors") && !n.equals("net")) {
                    Files.createSymbolicLink(config.resolve(n), c);                // each committed part linked; the site's own parts never
                }
            }
        }
        Path connectors = Files.createDirectories(config.resolve("connectors"));
        try (Stream<Path> s = Files.list(lake.resolve("connectors"))) {
            for (Path c : s.toList()) {
                Files.copy(c, connectors.resolve(c.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path modules = Files.createDirectories(work.resolve("modules"));
        try (Stream<Path> s = Files.list(packages())) {
            for (Path p : s.toList()) {
                Files.copy(p, modules.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);   // the install's packages, as built
            }
        }
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Path jvmTmp = Files.createDirectories(work.resolve("jvm-tmp"));
        List<String> cmd = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx" + System.getProperty("sbs.xmx", "1536m"), "-Djava.io.tmpdir=" + jvmTmp,
                "-Ddrishti.home=" + work, "-Ddrishti.data.dir=" + work.resolve("data"), "-Ddrishti.modules.home=" + modules, "-jar", execJar().toString());
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(work.toFile()).redirectErrorStream(true).redirectOutput(work.resolve("server.log").toFile());
        pb.environment().keySet().removeIf(k -> k.startsWith("DRISHTI_"));
        Map<String, String> env = pb.environment();
        env.put("DRISHTI_PORT", Integer.toString(port));
        env.put("DRISHTI_DELTA_ROOT", lake.resolve("delta").toString());
        env.put("DRISHTI_DEMO_ENABLED", "false");
        env.put("DRISHTI_OFFLINE", "true");
        env.put("DRISHTI_VAR_SYSTEM_BASKETS_SEED", "true");
        env.put("DRISHTI_BUSINESS_TODAY", today);
        env.put("DRISHTI_VAR_SCENARIO_WINDOW", "all");                                  // the whole scenario set (509 in the test data), not the 250-day default
        env.put("DRISHTI_VAR_HOT_DIR", work.resolve("var-hot").toString());
        env.put("DRISHTI_VAR_OFFICIAL_ROOT", work.resolve("var-official").toString());
        env.put("LAKE_VAR_OFFICIAL_ROOT", work.resolve("var-official").toString());     // its reader (var-results: BI, the engine's var-history) reads where it publishes
        env.put("DRISHTI_CCR_EXPOSURE_OFFICIAL_ROOT", work.resolve("ccr-official").toString());
        overrides.forEach((k, v) -> {
            if (v == null || v.isEmpty()) {
                env.remove(k);
            } else {
                env.put(k, v);
            }
        });
        PackagedServer server = new PackagedServer(work, pb.start(), port);
        try {
            server.awaitReady(Duration.ofMinutes(10));
        } catch (RuntimeException | InterruptedException e) {
            server.close();
            throw e;
        }
        return server;
    }

    private void awaitReady(Duration limit) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("the server ended (" + process.exitValue() + "): " + tail(work.resolve("server.log")));
            }
            try {
                if (status("/actuator/health/readiness") == 200 && status("/api/v1/risk/var/status") == 200) {
                    return;
                }
            } catch (IOException e) {
                // still starting
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("the server was not ready in " + limit + ": " + tail(work.resolve("server.log")));
    }

    private int status(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** One answer as JSON; an answer that is not 200 is an error naming the request. */
    JsonNode get(String pathAndQuery) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + pathAndQuery)).timeout(Duration.ofMinutes(5)).build(),
                HttpResponse.BodyHandlers.ofString());
        for (int i = 0; i < 6 && r.statusCode() == 503 && r.body().contains("DRS-9502"); i++) {   // a date that cannot be opened just yet: as asked, again
            Thread.sleep(15_000);
            r = http.send(HttpRequest.newBuilder(URI.create(base + pathAndQuery)).timeout(Duration.ofMinutes(5)).build(), HttpResponse.BodyHandlers.ofString());
        }
        if (r.statusCode() != 200) {
            throw new IllegalStateException(r.statusCode() + " for " + pathAndQuery + ": " + r.body().substring(0, Math.min(300, r.body().length())));
        }
        return JSON.readTree(r.body());
    }

    /** One POST of a JSON body; an answer that is not 200 is an error naming the request. */
    JsonNode post(String pathAndQuery, String json) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + pathAndQuery)).timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IllegalStateException(r.statusCode() + " for POST " + pathAndQuery + ": " + r.body().substring(0, Math.min(300, r.body().length())));
        }
        return JSON.readTree(r.body());
    }

    /** One POST of a JSON body that creates something; an answer that is not 201 is an error naming the request. */
    JsonNode created(String pathAndQuery, String json) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + pathAndQuery)).timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 201) {
            throw new IllegalStateException(r.statusCode() + " for POST " + pathAndQuery + ": " + r.body().substring(0, Math.min(300, r.body().length())));
        }
        return JSON.readTree(r.body());
    }

    /** Any request: the status and the body as JSON (an empty object when the body is not JSON). */
    Answer send(String method, String pathAndQuery) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + pathAndQuery)).timeout(Duration.ofMinutes(5))
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode body;
        try {
            body = JSON.readTree(r.body().isBlank() ? "{}" : r.body());
        } catch (IOException e) {
            body = JSON.createObjectNode();
        }
        return new Answer(r.statusCode(), body, r.body());
    }

    /** A status, the body as JSON and as text. */
    record Answer(int status, JsonNode json, String text) {}

    @Override
    public void close() {
        process.destroy();                                     // this process only, by its handle
        try {
            if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    static String tail(Path log) {
        try {
            String s = Files.readString(log);
            return s.substring(Math.max(0, s.length() - 1500));
        } catch (IOException e) {
            return "(no log " + log + ")";
        }
    }
}
