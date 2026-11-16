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
package com.ash.drishti.testkit.s3;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;

/**
 * The MinIO server binary, the most faithful S3 check, on Linux only (the Windows QA machine runs no native binaries). The binary
 * comes from, in order: {@code DRISHTI_MINIO_BIN} (a path), the tools cache ({@code DRISHTI_TOOLS_CACHE}, default
 * {@code <repo>/tools/cache}, git-ignored), or a download from {@code DRISHTI_MINIO_URL} (default: the official MinIO release URL)
 * checked against {@code DRISHTI_MINIO_SHA256} or the {@code <url>.sha256sum} published beside it. It runs on a free port in
 * 18750-18759 with its data under {@code target/}, and is stopped by PID. Anything missing (Windows, offline, no binary served)
 * skips the test with the reason.
 */
public final class MinioServer implements AutoCloseable {

    /** The simulator's name in report lines. */
    public static final String NAME = "MinIO (server binary)";
    public static final String USER = "drishti";
    public static final String PASSWORD = "drishti-secret";
    /** The official release: MinIO stopped serving community binaries (dl.min.io answers 410 Gone), so this usually skips. */
    static final String DEFAULT_URL = "https://dl.min.io/server/minio/release/linux-%s/archive/minio.RELEASE.2025-04-22T22-12-26Z";

    private final long pid;
    private final S3Endpoint endpoint;

    private MinioServer(long pid, int port) {
        this.pid = pid;
        this.endpoint = new S3Endpoint(NAME, "http://127.0.0.1:" + port, USER, PASSWORD);
    }

    /** Starts MinIO with its data in {@code dataDir}, or skips the calling test with the reason it cannot. */
    public static MinioServer startOrSkip(Path dataDir) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        skipUnless(os.contains("linux"), "MinIO tests run on Linux only (no native binaries on Windows): " + os);
        Path bin = binary();
        Files.createDirectories(dataDir);
        int port = S3Endpoint.freePort();
        int console = S3Endpoint.freePort();
        ProcessBuilder pb = new ProcessBuilder(bin.toString(), "server", dataDir.toAbsolutePath().toString(), "--address", "127.0.0.1:" + port,
                "--console-address", "127.0.0.1:" + console).redirectErrorStream(true)
                .redirectOutput(dataDir.toAbsolutePath().getParent().resolve(dataDir.getFileName() + "-minio.log").toFile());
        pb.environment().put("MINIO_ROOT_USER", USER);
        pb.environment().put("MINIO_ROOT_PASSWORD", PASSWORD);
        pb.environment().put("MINIO_BROWSER", "off");
        Process p = pb.start();
        MinioServer m = new MinioServer(p.pid(), port);
        Runtime.getRuntime().addShutdownHook(new Thread(m::close));
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        long until = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < until && p.isAlive()) {
            try {
                if (http.send(HttpRequest.newBuilder(URI.create(m.endpoint.endpoint() + "/minio/health/ready")).build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return m;
                }
            } catch (IOException notYet) {
                // still starting
            }
            Thread.sleep(200);
        }
        m.close();
        throw new IllegalStateException("MinIO did not become ready on port " + port + " (see the -minio.log beside " + dataDir + ")");
    }

    /** The binary: the env path, the cached copy, or a verified download into the cache; skips when none can be had. */
    static Path binary() throws Exception {
        String given = System.getenv("DRISHTI_MINIO_BIN");
        if (given != null && !given.isBlank()) {
            Path p = Path.of(given);
            skipUnless(Files.isExecutable(p), "DRISHTI_MINIO_BIN is not an executable file: " + p);
            return p;
        }
        String arch = System.getProperty("os.arch", "").contains("aarch64") ? "arm64" : "amd64";
        String url = env("DRISHTI_MINIO_URL", String.format(DEFAULT_URL, arch));
        Path cached = cacheDir().resolve("minio").resolve(url.substring(url.lastIndexOf('/') + 1));
        if (Files.isExecutable(cached)) {
            return cached;
        }
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();
        String expected = System.getenv("DRISHTI_MINIO_SHA256");
        try {
            if (expected == null || expected.isBlank()) {
                HttpResponse<String> sum = http.send(HttpRequest.newBuilder(URI.create(url + ".sha256sum")).build(), HttpResponse.BodyHandlers.ofString());
                skipUnless(sum.statusCode() == 200, "the MinIO binary is not served at " + url + " (HTTP " + sum.statusCode()
                        + "); set DRISHTI_MINIO_BIN, or DRISHTI_MINIO_URL and DRISHTI_MINIO_SHA256");
                expected = sum.body().trim().split("\\s+")[0];
            }
            Files.createDirectories(cached.getParent());
            Path part = cached.resolveSibling(cached.getFileName() + ".part");
            HttpResponse<InputStream> got = http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofInputStream());
            skipUnless(got.statusCode() == 200, "the MinIO binary is not served at " + url + " (HTTP " + got.statusCode() + ")");
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream in = got.body(); var out = Files.newOutputStream(part)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) {
                    sha.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            String actual = HexFormat.of().formatHex(sha.digest());
            if (!actual.equalsIgnoreCase(expected)) {
                Files.deleteIfExists(part);
                throw new IllegalStateException("the MinIO download from " + url + " has SHA-256 " + actual + ", expected " + expected);
            }
            part.toFile().setExecutable(true);
            Files.move(part, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return cached;
        } catch (IOException offline) {
            skipUnless(false, "cannot download MinIO (offline?): " + offline);
            return null;
        }
    }

    /** {@code DRISHTI_TOOLS_CACHE}, or {@code tools/cache} in the repository (the folder holding {@code mvnw}). */
    static Path cacheDir() {
        String c = System.getenv("DRISHTI_TOOLS_CACHE");
        if (c != null && !c.isBlank()) {
            return Path.of(c);
        }
        for (Path d = Path.of("").toAbsolutePath(); d != null; d = d.getParent()) {
            if (Files.exists(d.resolve("mvnw")) && Files.isDirectory(d.resolve("tools"))) {
                return d.resolve("tools").resolve("cache");
            }
        }
        return Path.of("target", "tools-cache").toAbsolutePath();
    }

    /** Skips the calling test, printing the reason so the suite's output says why MinIO did not run. */
    private static void skipUnless(boolean ok, String reason) {
        if (!ok) {
            System.out.println("[S3 conditional writes] " + NAME + ": skipped: " + reason);
            Assumptions.abort(reason);
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    /** Where it listens and how to sign in. */
    public S3Endpoint endpoint() {
        return endpoint;
    }

    /** Stops the server by its PID (a no-op when it has stopped). */
    @Override
    public void close() {
        ProcessHandle.of(pid).ifPresent(h -> {
            h.destroy();
            try {
                h.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception slow) {
                h.destroyForcibly();
            }
        });
    }
}
