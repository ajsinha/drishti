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
package com.ash.drishti.plugin.delta;

import com.ash.lake.tck.TestRoots;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * A lake maintained by tools/lake/maintain.py (compacted, checkpointed, vacuumed) still reads the same through the product's Delta reader.
 * Skipped where uv (to run the tool) is not installed, or the drishti-lake wheel (lib/wheels, or LAKE_PYTHON: tools/lake/lakewheel.py) is not at hand.
 */
class DeltaAfterMaintenanceTest {

    @Test
    void theJavaReaderReadsALakeTheMaintenanceJobCompactedAndVacuumed() throws Exception {
        assumeTrue(new ProcessBuilder("uv", "--version").start().waitFor(30, TimeUnit.SECONDS), "uv is not installed");
        Path repo = Path.of(TestRoots.repo());
        boolean wheel = System.getenv("LAKE_PYTHON") != null;
        try (var wheels = Files.isDirectory(repo.resolve("lib/wheels")) ? Files.list(repo.resolve("lib/wheels")) : java.util.stream.Stream.<Path>empty()) {
            wheel |= wheels.anyMatch(f -> f.getFileName().toString().startsWith("drishti_lake-"));
        }
        assumeTrue(wheel, "the drishti-lake wheel is not in lib/wheels (and LAKE_PYTHON is not set)");
        Path root = Files.createTempDirectory("drishti-maintained");
        Path src = Path.of(TestRoots.module(), "src", "test", "resources", "lake");
        try (var files = Files.walk(src)) {
            for (Path f : files.toList()) {
                Path to = root.resolve(src.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(f, to);
                }
            }
        }
        Path config = root.resolve("maintenance.yaml");
        Files.writeString(config, "lakes:\n  - root: " + root + "\n    compact: true\n    checkpoint: true\n    vacuum-hours: 0\n");
        // the test writes its own table (not through lake build), so the ownership rule needs --force-foreign to maintain it
        ProcessBuilder pb = new ProcessBuilder("uv", "run", "-q", "--with", "deltalake", "--with", "pyarrow", "--with", "pyyaml", "python",
                "tools/lake/maintain.py", "--config", config.toString(), "--once", "--force-foreign").directory(Path.of(TestRoots.repo()).toFile()).inheritIO();
        pb.environment().put("DRISHTI_DATA_DIR", root.resolve("data").toString());   // its history in the test's folder, not the site's
        Process p = pb.start();
        assertThat(p.waitFor(5, TimeUnit.MINUTES) && p.exitValue() == 0).as("maintenance ran").isTrue();
        assertThat(Files.list(root.resolve("desk/trade/_delta_log")).anyMatch(f -> f.toString().endsWith(".checkpoint.parquet"))).isTrue();
        DeltaSourcePlugin plugin = new DeltaSourcePlugin();
        plugin.start(DatedSourceContract.context(Map.of("root", root.toString(), "domain", "desk", "mode.counterparty", "effective")));
        try {
            for (LocalDate d : new LocalDate[] {DatedSourceContract.D1, DatedSourceContract.D2, DatedSourceContract.D3}) {
                String expected = DatedSourceContract.ROWS.stream().filter(r -> r.kind().equals("trade") && r.id().equals("T-1") && r.date().equals(d))
                        .findFirst().orElseThrow().json();
                double mtm = new com.fasterxml.jackson.databind.ObjectMapper().readTree(expected).get("mtm").asDouble();
                assertThat(plugin.fetch(EntityRef.of("trade", "T-1"), new AsOf(d, null)).orElseThrow().data().get("mtm").asDouble())
                        .as("T-1 on " + d).isEqualTo(mtm);
            }
        } finally {
            plugin.close();
        }
    }
}
