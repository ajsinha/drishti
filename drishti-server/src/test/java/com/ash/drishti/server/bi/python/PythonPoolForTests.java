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
package com.ash.drishti.server.bi.python;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * The compute pool {@code python} for the tests that run code people write over the whole server: the interpreter with pandas and pyarrow
 * ({@code -Ddrishti.compute.test.python}, {@code DRISHTI_PYTHON}, the console's virtual environment, or {@code python3}), and the shipped
 * {@code config/compute/compute.yaml} switched on for it. Whether the pool may run here (Linux with Landlock, the pinned versions) is the
 * server's own answer ({@code GET /api/v1/bi/python/runtime}): a test skips with that reason, never fails, where it cannot.
 */
public final class PythonPoolForTests {

    private static volatile String found;
    private static volatile boolean looked;

    private PythonPoolForTests() {}

    /** The interpreter, or null when none carries pandas and pyarrow. */
    public static String interpreter() {
        if (!looked) {
            synchronized (PythonPoolForTests.class) {
                if (!looked) {
                    found = look();
                    looked = true;
                }
            }
        }
        return found;
    }

    /** Switches compute on with the shipped compute file and this interpreter (nothing changes when there is none: Python then refuses in words). */
    public static void register(DynamicPropertyRegistry r) {
        String py = interpreter();
        if (py == null) {
            return;
        }
        r.add("drishti.compute.file", () -> Path.of("..", "config", "compute", "compute.yaml").toAbsolutePath().normalize().toString());
        r.add("DRISHTI_COMPUTE_ENABLED", () -> "true");
        r.add("DRISHTI_PYTHON", () -> py);
    }

    private static String look() {
        List<String> candidates = new ArrayList<>();
        for (String c : new String[] {System.getProperty("drishti.compute.test.python"), System.getenv("DRISHTI_PYTHON")}) {
            if (c != null && !c.isBlank()) {
                candidates.add(c);
            }
        }
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path venv = dir.resolve("drishti-console/.venv/bin/python");
            if (Files.isExecutable(venv)) {
                candidates.add(venv.toString());
            }
        }
        candidates.add("python3");
        for (String c : candidates) {
            try {
                Process p = new ProcessBuilder(c, "-c", "import pandas, pyarrow").redirectErrorStream(true).start();
                if (p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return c;
                }
            } catch (Exception ignored) {
                // the next
            }
        }
        return null;
    }
}
