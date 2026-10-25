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
 * Locked Python environments (docs/architecture/PYTHON_COMPUTE.md section 5): one per lock, its id the sha256 of (interpreter version,
 * platform, lock text), built under {@code <data dir>/compute/envs/<id>/} with {@code python -m venv} and
 * {@code pip install --require-hashes --no-deps --only-binary=:all:} from the approved source only (no resolution, no source builds, no
 * index but the one named), then recorded in a manifest of hashes and made read-only. Never changed in place: a new lock is a new
 * environment. A build that fails leaves nothing behind and names the wheel in its message. pip's temporary files go under
 * {@code <data dir>/compute/work/}, never the machine's temporary folder.
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

    /** The interpreter inside an environment. */
    public static Path python(Path envDir) {
        return WINDOWS ? envDir.resolve("Scripts").resolve("python.exe") : envDir.resolve("bin").resolve("python");
    }

    /** True when the environment is built (its manifest exists). */
    public boolean built(String id) {
        return Manifest.read(dir(id)).isPresent();
    }

    /**
     * The interpreter of a built environment, after checking every file against the manifest; refused (with the file named) when the
     * environment is missing or changed.
     */
    public Path verifiedPython(String id) {
        Optional<String> problem = Manifest.verify(dir(id));
        if (problem.isPresent()) {
            throw new ComputeException(ComputeException.Code.REFUSED, "environment " + id + ": " + problem.get());
        }
        return python(dir(id));
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
            Run venv = run(List.of(python, "-m", "venv", dir.toString()), work, log);
            if (venv.exit != 0) {
                throw fail(id, "python -m venv failed: " + venv.last());
            }
            Path lockCopy = work.resolve("requirements.lock");
            Files.writeString(lockCopy, lock.text());
            List<String> pip = new ArrayList<>(List.of(python(dir).toString(), "-m", "pip", "install", "--require-hashes", "--no-deps",
                    "--only-binary=:all:", "--no-cache-dir", "--disable-pip-version-check", "--no-input", "--isolated", "--no-warn-script-location"));
            pip.addAll(source(py.index()));
            pip.add("-r");
            pip.add(lockCopy.toString());
            Run install = run(pip, work, log);
            if (install.exit != 0) {
                throw fail(id, "pip refused the lock: " + pipProblem(install.out));
            }
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
            log.add("built " + id + " (" + lock.pins().size() + " packages) and made it read-only");
            LOG.info("compute: environment {} built by {} ({} packages)", id, builtBy, lock.pins().size());
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

    /** The lines of pip's output that say what was wrong (a hash mismatch, a wheel missing for the platform). */
    static String pipProblem(String out) {
        List<String> keep = new ArrayList<>();
        for (String line : out.split("\n")) {
            String l = line.strip();
            String lower = l.toLowerCase(Locale.ROOT);
            if (lower.contains("hash") && (lower.contains("expected") || lower.contains("got") || lower.contains("do not match"))
                    || lower.contains("no matching distribution") || lower.contains("could not find a version") || lower.startsWith("error:")
                    || l.contains("==") && lower.contains("from")) {
                keep.add(l);
            }
        }
        String s = String.join(" ", keep.isEmpty() ? List.of(out.strip().lines().reduce((a, b) -> b).orElse("")) : keep);
        return s.length() > 800 ? s.substring(0, 800) + "…" : s;
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
