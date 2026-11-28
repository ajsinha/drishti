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
package com.ash.drishti.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The route-inventory guard of the row-rule coverage (R11 of the production-readiness audit): every controller of the repository is listed
 * in {@code row-rule-route-inventory.tsv} with how it reaches row rules and masks. A new controller fails this test until it is classified
 * (gated, delegated or none-with-a-reason); a "gated" controller that loses its gate, or a "none" one without a reason, fails too.
 */
class RowRuleRouteInventoryGuardTest {

    private static final Pattern CONTROLLER = Pattern.compile("(?m)^@(RestController|Controller)\\b");
    private static final Pattern GATE = Pattern.compile(
            "Entitlements|PrincipalAccess|ViewerAccess|RowRule|TableRules|OutputGate|DatasetService|Authoris|rowRules");

    @Test
    void everyControllerIsClassifiedAndTheClassHolds() throws IOException {
        Path root = repositoryRoot();
        Map<String, String[]> listed = new LinkedHashMap<>();
        try (InputStream in = getClass().getResourceAsStream("/row-rule-route-inventory.tsv")) {
            assertThat(in).as("row-rule-route-inventory.tsv").isNotNull();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split("\t", -1);
                listed.put(p[1], p);
            }
        }
        Map<String, String> found = new LinkedHashMap<>();
        for (String top : List.of("kernel", "modules", "compute")) {
            try (Stream<Path> files = Files.walk(root.resolve(top))) {
                for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".java")
                        && x.toString().contains("/src/main/")).sorted()::iterator) {
                    String src = Files.readString(f);
                    if (CONTROLLER.matcher(src).find()) {
                        found.put(root.relativize(f).toString(), src);
                    }
                }
            }
        }
        List<String> problems = new ArrayList<>();
        for (var e : found.entrySet()) {
            String[] row = listed.get(e.getKey());
            if (row == null) {
                problems.add("not in the inventory: " + e.getKey());
                continue;
            }
            boolean gate = GATE.matcher(e.getValue()).find();
            switch (row[0]) {
                case "gated" -> {
                    if (!gate) {
                        problems.add("listed gated but names no gate: " + e.getKey());
                    }
                }
                case "delegated" -> {
                    if (gate) {
                        problems.add("names a gate itself: list it as gated: " + e.getKey());
                    }
                }
                case "none" -> {
                    if (row.length < 3 || row[2].isBlank()) {
                        problems.add("none needs a reason: " + e.getKey());
                    }
                }
                default -> problems.add("unknown class " + row[0] + ": " + e.getKey());
            }
        }
        for (String path : listed.keySet()) {
            if (!found.containsKey(path)) {
                problems.add("listed but no such controller: " + path);
            }
        }
        assertThat(found).as("controllers found").hasSizeGreaterThan(100);
        assertThat(problems).as("classify each controller in kernel/drishti-server/src/test/resources/row-rule-route-inventory.tsv").isEmpty();
    }

    private static Path repositoryRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("kernel").resolve("drishti-server"))) {
            p = p.getParent();
        }
        assertThat(p).as("repository root").isNotNull();
        return p;
    }
}
