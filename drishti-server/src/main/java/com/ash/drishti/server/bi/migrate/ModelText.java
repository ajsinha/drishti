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
package com.ash.drishti.server.bi.migrate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A converted model being written (docs/architecture/BI_MODEL_SPEC.md section 2): what the converter collects from a {@code rachana: 1} dataset, and what a
 * composed dataset folded into it adds (its measures), rendered as one {@code rachana: 2} model YAML. Not thread-safe; one per conversion.
 */
final class ModelText {

    /** A measure as written: its text in flow style, and whether the converter added it from another item (and from which). */
    record Measure(String name, String flow, String expr, String addedFrom) {}

    final String name;
    final String version;
    String header = "";
    final Map<String, String> catalogue = new LinkedHashMap<>();
    final Map<String, String[]> datasets = new LinkedHashMap<>();          // alias -> {dataset, role}
    final List<String> relationships = new ArrayList<>();
    final Map<String, Measure> measures = new LinkedHashMap<>();
    final Map<String, List<String>> hierarchies = new LinkedHashMap<>();
    final List<String> rollups = new ArrayList<>();
    final List<String> tests = new ArrayList<>();
    final Map<String, List<String>> maySeeAll = new LinkedHashMap<>();

    ModelText(String name, String version) {
        this.name = name;
        this.version = version;
    }

    String render() {
        StringBuilder b = new StringBuilder(header);
        b.append("rachana: 2\nexpressions: 2                  # DEL v2, the Python-like expression language (docs/guides/EXPRESSIONS.md)\n");
        b.append("model: ").append(YamlText.scalar(name)).append('\n');
        b.append("version: ").append(version).append('\n');
        catalogue.forEach((k, v) -> b.append(k).append(": ").append(v).append('\n'));
        b.append("\ndatasets:                       # alias: the dataset it reads, and its role (a fact is added up; a dimension groups and filters)\n");
        int w = datasets.keySet().stream().mapToInt(String::length).max().orElse(0);
        datasets.forEach((alias, d) -> b.append("  ").append(pad(alias + ":", w + 1)).append(" { dataset: ").append(YamlText.scalar(d[0])).append(", role: ").append(d[1])
                .append(" }\n"));
        if (!relationships.isEmpty()) {
            b.append("\nrelationships:                  # from the MANY side to the ONE side\n");
            relationships.forEach(r -> b.append("  - ").append(r).append('\n'));
        }
        if (!measures.isEmpty()) {
            b.append("\nmeasures:\n");
            int mw = measures.keySet().stream().mapToInt(n -> YamlText.key(n).length()).max().orElse(0);
            for (Measure m : measures.values()) {
                b.append("  ").append(pad(YamlText.key(m.name()) + ":", mw + 1)).append(' ').append(m.flow());
                if (m.addedFrom() != null) {
                    b.append("   # from ").append(m.addedFrom());
                }
                b.append('\n');
            }
        }
        if (!hierarchies.isEmpty()) {
            b.append("\nhierarchies:\n");
            hierarchies.forEach((k, v) -> b.append("  ").append(YamlText.key(k)).append(": ").append(YamlText.list(v)).append('\n'));
        }
        if (!rollups.isEmpty()) {
            b.append("\nrollups:\n");
            rollups.forEach(r -> b.append("  - ").append(r).append('\n'));
        }
        if (!tests.isEmpty()) {
            b.append("\ntests:\n");
            tests.forEach(t -> b.append("  - ").append(t).append('\n'));
        }
        if (!maySeeAll.isEmpty()) {
            b.append("\nsecurity:\n");
            b.append("  # Row rules live in each dataset now (BI_MODEL_SPEC.md 2.6). A role restricted on one dataset sees every row of the datasets its rules never\n");
            b.append("  # reached (in 2.1 such a question was refused): the converter notes that here, as the security safeguard asks (BI_MODEL_SPEC.md 8.5).\n");
            b.append("  may-see-all: ").append(YamlText.flow(listsOf(maySeeAll))).append('\n');
        }
        return b.toString();
    }

    private static Map<String, String> listsOf(Map<String, List<String>> m) {
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, YamlText.list(v)));
        return out;
    }

    static String pad(String s, int w) {
        return s.length() >= w ? s : s + " ".repeat(w - s.length());
    }
}
