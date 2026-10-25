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
package com.ash.drishti.compute;

import com.ash.drishti.compute.approval.ApprovalStore;
import com.ash.drishti.compute.approval.Approvals;
import com.ash.drishti.compute.cluster.PeerTransport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/** Test support: a Python interpreter to run workers with (or none: the tests that need one are skipped), and settings from YAML text. */
public final class TestCompute {

    public static final String VECTORS = "drishti_compute.samples.vectors:VectorEngine";
    public static final String FAULTS = "compute_test_engines.faults:FaultEngine";
    public static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    private static volatile String python;

    private TestCompute() {}

    /**
     * A Python 3.10+ interpreter: {@code -Ddrishti.compute.test.python}, {@code DRISHTI_PYTHON}, the console's venv, then
     * {@code python3}/{@code python} on the path. Null when none answers.
     */
    public static synchronized String python() {
        if (python != null) {
            return python.isEmpty() ? null : python;
        }
        List<String> candidates = new java.util.ArrayList<>();
        String p = System.getProperty("drishti.compute.test.python");
        if (p != null && !p.isBlank()) {
            candidates.add(p);
        }
        String e = System.getenv("DRISHTI_PYTHON");
        if (e != null && !e.isBlank()) {
            candidates.add(e);
        }
        Path console = Path.of("..", "drishti-console", ".venv", WINDOWS ? "Scripts" : "bin", WINDOWS ? "python.exe" : "python");
        if (Files.exists(console)) {
            candidates.add(console.toAbsolutePath().toString());
        }
        candidates.add(WINDOWS ? "python" : "python3");
        candidates.add("python");
        for (String c : candidates) {
            try {
                Process pr = new ProcessBuilder(c, "-c", "import sys;print(sys.version_info[:2] >= (3, 10))").redirectErrorStream(true).start();
                String out = new String(pr.getInputStream().readAllBytes()).strip();
                if (pr.waitFor(20, TimeUnit.SECONDS) && pr.exitValue() == 0 && out.equals("True")) {
                    python = c;
                    return c;
                }
            } catch (Exception ignored) {
                // try the next
            }
        }
        python = "";
        return null;
    }

    /** The folder of the test engines, put on the workers' path. */
    public static String testEngines() {
        return Path.of("src", "test", "python").toAbsolutePath().toString();
    }

    /** Settings from the YAML of a {@code compute:} document, the interpreter set to {@link #python()}, data under {@code dir}. */
    public static ComputeSettings settings(Path dir, String yaml) {
        try {
            Map<String, Object> doc = new ObjectMapper(new YAMLFactory()).readValue(yaml, new TypeReference<Map<String, Object>>() {});
            @SuppressWarnings("unchecked")
            Map<String, Object> c = (Map<String, Object>) doc.get("compute");
            if (c != null && !c.containsKey("python") && python() != null) {
                c.put("python", python());
            }
            return ComputeSettings.parse(doc, dir, "", UnaryOperator.identity(), "test");
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** One pool, {@code workers} workers, both test engines, the given extra pool lines (indented under the pool). */
    public static String onePool(String name, int min, int max, String mode, String extra) {
        return "compute:\n  enabled: true\n  pools:\n    " + name + ":\n      engines: [" + VECTORS + ", " + FAULTS + "]\n"
                + "      workers: { min: " + min + ", max: " + max + " }\n      mode: " + mode + "\n      python-path: [\"" + testEngines().replace("\\", "/")
                + "\"]\n" + (extra == null ? "" : extra);
    }

    public static ComputeService service(ComputeSettings s, Path configDir) {
        Approvals a = new Approvals(new ApprovalStore.Memory(), null, Clock.systemUTC(), 0);
        return new ComputeService(s, "test", configDir, a, PeerTransport.NONE).start();
    }

    /** The request folders left under the scratch root (none, after every request). */
    public static List<Path> scratchLeft(ComputeSettings s) throws java.io.IOException {
        if (!Files.isDirectory(s.scratch())) {
            return List.of();
        }
        try (var st = Files.list(s.scratch())) {
            return st.toList();
        }
    }
}
