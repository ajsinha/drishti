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
 * A lake maintained by tools/lake/maintain.py (compacted, checkpointed, vacuumed) still reads the same through Delta
 * Kernel, with either engine: the two agree on the files they write and read. Skipped where uv (to run the tool) is not installed.
 */
class DeltaAfterMaintenanceTest {

    @Test
    void theJavaReaderReadsALakeTheMaintenanceJobCompactedAndVacuumed() throws Exception {
        assumeTrue(new ProcessBuilder("uv", "--version").start().waitFor(30, TimeUnit.SECONDS), "uv is not installed");
        Path root = Files.createTempDirectory("drishti-maintained");
        Path src = Path.of("src/test/resources/lake");
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
        Process p = new ProcessBuilder("uv", "run", "-q", "--with", "deltalake", "--with", "pyarrow", "--with", "pyyaml", "python",
                "tools/lake/maintain.py", "--config", config.toString(), "--once").directory(Path.of("../..").toFile()).inheritIO().start();
        assertThat(p.waitFor(5, TimeUnit.MINUTES) && p.exitValue() == 0).as("maintenance ran").isTrue();
        assertThat(Files.list(root.resolve("desk/trade/_delta_log")).anyMatch(f -> f.toString().endsWith(".checkpoint.parquet"))).isTrue();
        for (String engine : new String[] {"native", "hadoop"}) {                  // both engines read the checkpoint
            DeltaSourcePlugin plugin = new DeltaSourcePlugin();
            plugin.start(DatedSourceContract.context(Map.of("root", root.toString(), "domain", "desk", "mode.counterparty", "effective",
                    "engine", engine)));
            try {
                for (LocalDate d : new LocalDate[] {DatedSourceContract.D1, DatedSourceContract.D2, DatedSourceContract.D3}) {
                    String expected = DatedSourceContract.ROWS.stream().filter(r -> r.kind().equals("trade") && r.id().equals("T-1") && r.date().equals(d))
                            .findFirst().orElseThrow().json();
                    double mtm = new com.fasterxml.jackson.databind.ObjectMapper().readTree(expected).get("mtm").asDouble();
                    assertThat(plugin.fetch(EntityRef.of("trade", "T-1"), new AsOf(d, null)).orElseThrow().data().get("mtm").asDouble())
                            .as(engine + ": T-1 on " + d).isEqualTo(mtm);
                }
            } finally {
                plugin.close();
            }
        }
    }
}
