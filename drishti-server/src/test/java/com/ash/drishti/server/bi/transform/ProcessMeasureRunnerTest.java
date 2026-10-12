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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A Python measure run through the console's sandboxed runner, started from the server as a Transform is (what an alert on a Python measure uses). */
class ProcessMeasureRunnerTest {

    @TempDir Path scratch;

    static final String SOURCE = "def compute(table, params):\n    return float(table['pnl1d'].sum()) * params['k']\n";

    ProcessTransformRunner runner() {
        Path python = Path.of("..", "drishti-console", ".venv", "bin", "python").toAbsolutePath();
        Assumptions.assumeTrue(Files.isExecutable(python), "the console's Python environment (drishti-console/.venv) is needed");
        TransformProperties props = new TransformProperties(true, python.toString(), Path.of("..", "drishti-console", "core", "transform_cli.py").toAbsolutePath().toString(), "", null, null, null, null,
                null, null, null, null, null);
        return new ProcessTransformRunner(props, scratch, List.of("pandas", "numpy", "pyarrow"), 30, 512);
    }

    Map<String, Object> manifest(String source) {
        return Map.of("name", "sum", "interface", "measure", "entry", "compute", "inputs", List.of("pnl1d"), "packages", List.of("pandas"), "target", "server", "timeout", 10, "memory", 256,
                "params", Map.of("k", 1), "source", source);
    }

    @Test
    void oneNumberForTheWholeInputAndOneForEachGroup() {
        ProcessTransformRunner r = runner();
        List<List<Object>> rows = List.of(List.of(1.0, "A"), List.of(2.0, "A"), List.of(5.0, "B"));
        JsonNode whole = r.measure(manifest(SOURCE), Map.of("k", 2), List.of(), List.of("pnl1d", "desk"), rows);
        assertThat(whole.path("rows").get(0).get(0).asDouble()).isEqualTo(16.0);
        JsonNode groups = r.measure(manifest(SOURCE), Map.of(), List.of("desk"), List.of("pnl1d", "desk"), rows);
        assertThat(groups.path("by").get(0).asText()).isEqualTo("desk");
        assertThat(groups.path("rows").get(0).toString()).isEqualTo("[\"A\",3.0]");
        assertThat(groups.path("rows").get(1).toString()).isEqualTo("[\"B\",5.0]");
    }

    @Test
    void theSandboxRefusesWhatItRefusesForAReport() {
        ProcessTransformRunner r = runner();
        String evil = "import os\ndef compute(table, params):\n    return 1.0\n";
        assertThatThrownBy(() -> r.measure(manifest(evil), Map.of(), List.of(), List.of("pnl1d"), List.of(List.of(1.0)))).isInstanceOf(DrishtiException.class)
                .hasMessageContaining("os");
    }
}
