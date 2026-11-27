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
package com.ash.drishti.compute.env;

import com.ash.drishti.compute.ComputeException;
import com.ash.drishti.compute.ComputeSettings;
import com.ash.drishti.compute.worker.ReadOnly;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Locked Python environments (docs/guides/bi/README.md section 5): one per lock, its id the sha256 of (interpreter version,
 * platform, lock text). Drishti NEVER installs a Python library (owner rule, 2026-11-23: an automatic install can corrupt an
 * environment): "building" an approved environment CHECKS that the configured interpreter ({@code compute.python}, else
 * {@code drishti.python}) has every pin of the lock at its exact version, and records the check in a read-only manifest under
 * {@code <data dir>/compute/envs/<id>/}. A pin that is missing or at another version fails the build in words, naming each one and the pip
 * command the operator may run himself (from the approved source: the shipped folder, a folder, or the named index); nothing is left
 * behind. Workers run that same interpreter as it is, and the libraries are asked again before a worker starts
 * ({@link #verifyLibraries}). No virtual environment is made; no {@code pip} is ever started.
 */
public final class Environments {

    private static final Logger LOG = LoggerFactory.getLogger(Environments.class);
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    /** A built environment: its id, folder and the build's log. */
    public record Built(String id, Path dir, List<String> log, boolean reused) {}

    private final ComputeSettings settings;
    private final Map<String, ReentrantLock> building = new ConcurrentHashMap<>();
    private final Map<String, String> lastFailure = new ConcurrentHashMap<>();
    private volatile Duration buildTimeout = Duration.ofMinutes(15);

    public Environments(ComputeSettings settings) {
        this.settings = settings;
    }

    public Environments buildTimeout(Duration d) {
        buildTimeout = d;
        return this;
    }

    /** The platform part of an environment's id. */
    public static String platform() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "") + "-"
                + System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    }

    /** The id of the environment a lock gives on this platform. */
    public static String id(String pythonVersion, LockFile lock) {
        return LockFile.sha256(pythonVersion + "|" + platform() + "|" + lock.text()).substring(0, 16);
    }

    public Path dir(String id) {
        return settings.envs().resolve(id);
    }

    /** The folder of an environment's record (no library is ever put there: the interpreter's own libraries are used, checked). */
    public static Path site(Path envDir) {
        return envDir.resolve("site");
    }

    /** True when the environment is built (its manifest exists). */
    public boolean built(String id) {
        return Manifest.read(dir(id)).isPresent();
    }

    /**
     * The library folder of a built environment, after checking every file against the manifest; refused (with the file named) when the
     * environment is missing or changed.
     */
    public Path verifiedSite(String id) {
        Optional<String> problem = Manifest.verify(dir(id));
        if (problem.isPresent()) {
            throw new ComputeException(ComputeException.Code.REFUSED, "environment " + id + ": " + problem.get());
        }
        return site(dir(id));
    }

    /** Why the last build of an environment failed, if it did. */
    public Optional<String> lastFailure(String id) {
        return Optional.ofNullable(lastFailure.get(id));
    }

    /** Builds the environment of a lock (or returns the one already built). Approval is the caller's check. */
    public Built build(Requires.Python py, LockFile lock, String builtBy) {
        String id = id(py.version(), lock);
        ReentrantLock l = building.computeIfAbsent(id, k -> new ReentrantLock());
        l.lock();
        try {
            Path dir = dir(id);
            if (Manifest.read(dir).isPresent()) {
                return new Built(id, dir, List.of("already built"), true);
            }
            try {
                Built b = doBuild(id, dir, py, lock, builtBy);
                lastFailure.remove(id);
                return b;
            } catch (ComputeException e) {
                lastFailure.put(id, e.getMessage());
                throw e;
            }
        } finally {
            l.unlock();
        }
    }

    private Built doBuild(String id, Path dir, Requires.Python py, LockFile lock, String builtBy) {
        List<String> log = new ArrayList<>();
        Path work = settings.work().resolve("build-" + id);
        try {
            ReadOnly.delete(dir);                                           // a build that did not finish
            ReadOnly.delete(work);
            Files.createDirectories(work.resolve("tmp"));
            Files.createDirectories(settings.envs());
            String python = settings.interpreter();
            Run v = run(List.of(python, "-c", "import sys;print('%d.%d'%sys.version_info[:2])"), work, log);
            if (v.exit != 0) {
                throw fail(id, "the interpreter " + python + " cannot be run (compute.python): " + v.last());
            }
            String have = v.out.strip();
            if (!have.equals(py.version())) {
                throw fail(id, "the lock wants Python " + py.version() + ", compute.python is " + have);
            }
            List<String> missing = missing(python, lock, work, log);           // CHECKED, never installed (owner rule, 2026-11-23)
            if (!missing.isEmpty()) {
                throw fail(id, missingWords(python, missing, py.index()));
            }
            Files.createDirectories(dir);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", id);
            info.put("python", py.version());
            info.put("platform", platform());
            info.put("lockSha256", lock.sha256());
            info.put("source", py.index());
            info.put("packages", lock.pins().stream().map(p -> p.name() + "==" + p.version()).toList());
            info.put("builtAt", Instant.now().toString());
            info.put("builtBy", builtBy);
            Manifest.write(dir, info);
            ReadOnly.apply(dir);
            log.add("checked " + id + ": " + python + " has every one of the lock's " + lock.pins().size() + " packages at its version");
            LOG.info("compute: environment {} checked by {} ({} packages in {})", id, builtBy, lock.pins().size(), python);
            return new Built(id, dir, log, false);
        } catch (IOException e) {
            throw fail(id, "the build failed: " + e.getMessage());
        } catch (ComputeException e) {
            try {
                ReadOnly.delete(dir);
            } catch (IOException ignored) {
                LOG.warn("compute: could not remove the failed environment {}", dir);
            }
            throw e;
        } finally {
            try {
                ReadOnly.delete(work);
            } catch (IOException e) {
                LOG.warn("compute: could not remove the build folder {}: {}", work, e.getMessage());
            }
        }
    }

    private ComputeException fail(String id, String why) {
        return new ComputeException(ComputeException.Code.REFUSED, "environment " + id + ": " + why);
    }

    /** Where the operator's pip command takes the wheels from: the shipped folder, a folder, or a named index (printed, never run). */
    private List<String> source(String index) {
        if ("shipped".equals(index)) {
            return List.of("--no-index", "--find-links", settings.shipped().toString());
        }
        if (index.startsWith("path:")) {
            return List.of("--no-index", "--find-links", Path.of(index.substring(5)).toAbsolutePath().toString());
        }
        String url = settings.indexes().get(index);
        if (url == null) {
            throw new ComputeException(ComputeException.Code.REFUSED, "the package source '" + index + "' is not one of compute.indexes "
                    + settings.indexes().keySet() + " (or shipped, or path:<folder>)");
        }
        return List.of("--index-url", url);
    }

    private static final String PROBE = "import importlib.metadata as m, sys\nfor a in sys.argv[1:]:\n    n, v = a.split('==', 1)\n"
            + "    try:\n        h = m.version(n)\n    except Exception:\n        h = None\n    if h != v:\n"
            + "        print('MISSING ' + a + ' (' + ('not installed' if h is None else h + ' is installed') + ')')\n";

    /** The lock's pins the interpreter has not got at their version, each in words; empty: every one is there. Nothing is installed. */
    private List<String> missing(String python, LockFile lock, Path work, List<String> log) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(python, "-c", PROBE));
        lock.pins().forEach(p -> cmd.add(p.name() + "==" + p.version()));
        Run r = run(cmd, work, log);
        if (r.exit != 0) {
            throw new IOException("the interpreter " + python + " could not be asked what it has installed: " + r.last());
        }
        return r.out.lines().filter(l -> l.startsWith("MISSING ")).map(l -> l.substring(8)).toList();
    }

    /** What a check that found libraries missing says: their names and the pip command the operator may run himself. */
    String missingWords(String python, List<String> missing, String index) {
        StringBuilder cmd = new StringBuilder(quote(python)).append(" -m pip install");     // printed for the operator, never run
        source(index).forEach(w -> cmd.append(' ').append(quote(w)));
        missing.forEach(m -> cmd.append(' ').append(quote(m.split(" ", 2)[0])));
        return "the interpreter " + python + " lacks " + String.join(", ", missing) + "; Drishti installs nothing (an automatic install can "
                + "corrupt an environment): install them yourself, e.g. " + cmd + ", then build again";
    }

    private static String quote(String w) {
        return w.matches(".*[\\s&<>|()].*") ? "\"" + w + "\"" : w;
    }

    /**
     * The libraries of an approved, checked environment, asked again before a worker starts (the owner may have changed the interpreter
     * since): refused, with the pip command, when one is missing. Nothing is installed.
     */
    public void verifyLibraries(Requires.Python py, LockFile lock) {
        String id = id(py.version(), lock);
        Path work = settings.work().resolve("check-" + id + "-" + Thread.currentThread().threadId());
        List<String> log = new ArrayList<>();
        try {
            Files.createDirectories(work.resolve("tmp"));
            List<String> missing = missing(settings.interpreter(), lock, work, log);
            if (!missing.isEmpty()) {
                throw fail(id, missingWords(settings.interpreter(), missing, py.index()));
            }
        } catch (IOException e) {
            throw fail(id, e.getMessage());
        } finally {
            try {
                ReadOnly.delete(work);
            } catch (IOException e) {
                LOG.warn("compute: could not remove the check folder {}: {}", work, e.getMessage());
            }
        }
    }

    private record Run(int exit, String out) {
        String last() {
            return out.strip().lines().reduce((a, b) -> b).orElse("(no output)");
        }
    }

    private Run run(List<String> cmd, Path work, List<String> log) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).directory(work.toFile());
        Map<String, String> env = pb.environment();
        Map<String, String> keep = new LinkedHashMap<>();
        for (String k : List.of("PATH", "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "http_proxy", "https_proxy", "no_proxy", "HTTP_PROXY",
                "HTTPS_PROXY", "NO_PROXY")) {
            String v = System.getenv(k);
            if (v != null) {
                keep.put(k, v);
            }
        }
        env.clear();
        env.putAll(keep);
        String tmp = work.resolve("tmp").toString();
        env.put("TMPDIR", tmp);
        env.put("TEMP", tmp);
        env.put("TMP", tmp);
        env.put("HOME", work.toString());
        env.put("USERPROFILE", work.toString());
        env.put("PIP_CONFIG_FILE", WINDOWS ? "NUL" : "/dev/null");
        env.put("PYTHONNOUSERSITE", "1");
        env.put("PYTHONDONTWRITEBYTECODE", "1");
        log.add("$ " + String.join(" ", cmd));
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        try {
            if (!p.waitFor(buildTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return new Run(-1, "timed out after " + buildTimeout.toMinutes() + " min");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("interrupted");
        }
        String text = new String(out, StandardCharsets.UTF_8);
        text.lines().forEach(log::add);
        return new Run(p.exitValue(), text);
    }

    /** Every environment folder: id, built or not, its manifest's facts (packages, built when and by whom) and its size. */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(settings.envs())) {
            return out;
        }
        try (DirectoryStream<Path> s = Files.newDirectoryStream(settings.envs())) {
            for (Path d : s) {
                if (!Files.isDirectory(d)) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", d.getFileName().toString());
                Optional<Map<String, Object>> man = Manifest.read(d);
                m.put("built", man.isPresent());
                man.ifPresent(x -> {
                    for (String k : List.of("python", "platform", "source", "packages", "builtAt", "builtBy", "lockSha256")) {
                        m.put(k, x.get(k));
                    }
                });
                m.put("bytes", size(d));
                lastFailure(m.get("id").toString()).ifPresent(f -> m.put("lastFailure", f));
                out.add(m);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static long size(Path d) {
        try (var s = Files.walk(d)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Removes the environments not in {@code keep} (no approved connector or pool uses them); returns the ids removed. */
    public List<String> gc(java.util.Set<String> keep) {
        List<String> removed = new ArrayList<>();
        for (Map<String, Object> e : list()) {
            String id = String.valueOf(e.get("id"));
            if (!keep.contains(id)) {
                try {
                    ReadOnly.delete(dir(id));
                    removed.add(id);
                } catch (IOException ex) {
                    LOG.warn("compute: could not remove environment {}: {}", id, ex.getMessage());
                }
            }
        }
        return removed;
    }
}
