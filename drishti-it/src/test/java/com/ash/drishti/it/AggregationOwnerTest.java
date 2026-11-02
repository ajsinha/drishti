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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The architecture rule of the pivot engine (PIVOT_ENGINE.md section 13.1): no module but {@code drishti-bicompute} re-implements
 * aggregation for a pivot. A source scan of every main Java file: no class outside the module implements the function SPI
 * ({@code implements AggFunction}, {@code new AggFunction<}), no class outside it writes {@code GROUPING SETS}, and nothing in the server's
 * pivot code or the console's pivot screen writes a {@code GROUP BY} or adds up figures itself (the console only draws).
 */
class AggregationOwnerTest {

    private static final Pattern SPI = Pattern.compile("implements\\s+(?:[\\w.]+\\.)?AggFunction\\b|new\\s+(?:[\\w.]+\\.)?AggFunction\\s*<");
    private static final Pattern GROUPING_SETS = Pattern.compile("GROUPING\\s+SETS", Pattern.CASE_INSENSITIVE);
    private static final Pattern GROUP_BY = Pattern.compile("GROUP\\s+BY", Pattern.CASE_INSENSITIVE);

    @Test
    void onlyTheBicomputeModuleAggregatesForAPivot() throws IOException {
        Path root = RepositoryFiles.root();
        List<String> found = new ArrayList<>();
        for (Path p : RepositoryFiles.withSuffixes(Set.of(".java", ".js", ".py"))) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            if (rel.startsWith("drishti-bicompute/") || rel.contains("/src/test/") || rel.contains("/tests/") || rel.startsWith("tools/")) {
                continue;
            }
            String text = Files.readString(p);
            if (rel.endsWith(".java") && SPI.matcher(text).find()) {
                found.add(rel + ": implements the aggregation-function SPI (put the function in drishti-bicompute)");
            }
            if (rel.endsWith(".java") && GROUPING_SETS.matcher(text).find()) {
                found.add(rel + ": writes GROUPING SETS (the grouping-sets compiler is drishti-bicompute's)");
            }
            boolean pivotCode = rel.startsWith("drishti-server/src/main/java/com/ash/drishti/server/pivot/") || rel.endsWith("VarPivotProvider.java")
                    || rel.contains("/static/js/pivot") || rel.contains("routes/pivot");
            if (pivotCode && GROUP_BY.matcher(text).find()) {
                found.add(rel + ": writes GROUP BY in pivot code (ask the engine)");
            }
        }
        assertThat(found).as("PIVOT_ENGINE.md section 13.1: no other module re-implements aggregation").isEmpty();
    }
}
