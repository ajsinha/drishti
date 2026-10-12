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
package com.ash.drishti.server.bi.transform;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs a Transform in the console's own sandboxed runner ({@code drishti-console/core/pyrunner.py}, docs/guides/PYTHON_BI.md section 7) by starting its
 * command-line entry, {@code core/transform_cli.py}, in a scratch folder: no network, a scratch folder only, CPU, memory, time and output limits. The server
 * writes the inputs and the manifest, reads the answer; it never runs the author's code itself and the code never sees anything the reader may not.
 */
public final class ProcessTransformRunner implements TransformRunner {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TransformProperties props;
    private final Path scratch;
    private final List<String> packages;
    private final int maxSeconds;
    private final int maxMemoryMb;

    /**
     * @param scratch where the per-run folders are made
     * @param packages the allow-list of packages (the server's {@code drishti.bi.report.python-packages}), passed to the runner as its own
     */
    public ProcessTransformRunner(TransformProperties props, Path scratch, List<String> packages, int maxSeconds, int maxMemoryMb) {
        this.props = props;
        this.scratch = scratch;
        this.packages = packages;
        this.maxSeconds = maxSeconds;
        this.maxMemoryMb = maxMemoryMb;
    }

    /** The console's runner script: the setting, else {@code drishti-console/core/transform_cli.py} in the working directory or a parent. */
    Path script() {
        if (!props.runner().isBlank()) {
            return Path.of(props.runner()).toAbsolutePath();
        }
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            Path p = dir.resolve("drishti-console/core/transform_cli.py");
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        throw new DrishtiException(ErrorCode.PYTHON_BUSY, "the console's Python runner (drishti-console/core/transform_cli.py) was not found: set drishti.bi.transform.runner");
    }

    /** The Python to start: the setting, else the console's virtual environment next to the script, else {@code python3}. */
    String python(Path script) {
        if (!props.python().isBlank()) {
            return props.python();
        }
        Path venv = script.getParent().getParent().resolve(".venv/bin/python");
        return Files.isExecutable(venv) ? venv.toString() : "python3";
    }

    @Override
    public Result run(Request r) {
        if (!props.enabled()) {
            throw new DrishtiException(ErrorCode.PYTHON_BUSY, "Python Transforms are switched off (drishti.bi.transform.enabled)");
        }
        Path script = script();
        Path dir = null;
        try {
            Files.createDirectories(scratch);
            dir = com.ash.drishti.common.TempFiles.createDir(scratch, "run-");
            List<String> files = new ArrayList<>();
            for (int i = 0; i < r.frames().size(); i++) {
                Files.write(dir.resolve("in_" + i + ".arrow"), r.frames().get(i));
                files.add("in_" + i + ".arrow");
            }
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("manifest", r.manifest());
            req.put("params", r.params());
            req.put("inputs", files);
            req.put("output", r.output().toAbsolutePath().toString());
            req.put("max_rows", r.maxRows());
            req.put("max_bytes", r.maxBytes());
            req.put("settings", Map.of("bi.python.packages", packages, "bi.python.max_timeout_seconds", Math.min(maxSeconds, (int) props.timeout().toSeconds()),
                    "bi.python.max_memory_mb", maxMemoryMb, "bi.python.max_output_bytes", r.maxBytes(), "bi.python.workers", 1, "bi.python.queue_seconds", 1));
            Files.writeString(dir.resolve("req.json"), JSON.writeValueAsString(req));
            Process p = new ProcessBuilder(python(script), script.toString(), dir.toString()).redirectErrorStream(true).redirectOutput(dir.resolve("cli.log").toFile()).start();
            if (!p.waitFor(props.timeout().toSeconds() + 15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new DrishtiException(ErrorCode.PYTHON_LIMIT, "the Transform ran longer than " + props.timeout().toSeconds() + " s and was stopped (drishti.bi.transform.timeout)");
            }
            Path out = dir.resolve("result.json");
            if (!Files.isRegularFile(out)) {
                String log = Files.isRegularFile(dir.resolve("cli.log")) ? Files.readString(dir.resolve("cli.log")) : "";
                throw new DrishtiException(ErrorCode.PYTHON_BUSY, "the Python runner gave no answer (exit " + p.exitValue() + "): " + tail(log));
            }
            JsonNode a = JSON.readTree(out.toFile());
            if (!a.path("ok").asBoolean(false)) {
                throw failure(a);
            }
            List<Column> cols = new ArrayList<>();
            a.path("columns").forEach(c -> cols.add(new Column(c.path("name").asText(), c.path("type").asText("string"))));
            return new Result(r.output(), cols, a.path("rows").asLong(), a.path("ms").asLong());
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.PYTHON_BUSY, "the Python runner could not be started: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DrishtiException(ErrorCode.PYTHON_BUSY, "the Transform was interrupted");
        } finally {
            if (dir != null) {
                delete(dir);
            }
        }
    }

    private static DrishtiException failure(JsonNode a) {
        String code = a.path("code").asText("DRS-9071");
        ErrorCode ec = ErrorCode.PYTHON_FAILED;
        for (ErrorCode c : ErrorCode.values()) {
            if (c.code().equals(code)) {
                ec = c;
            }
        }
        String detail = a.path("detail").asText("the Transform failed");
        return new DrishtiException(ec, detail);
    }

    private static String tail(String s) {
        String t = s.strip();
        return t.length() > 600 ? t.substring(t.length() - 600) : t;
    }

    private static void delete(Path dir) {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
            // scratch
        }
    }
}
