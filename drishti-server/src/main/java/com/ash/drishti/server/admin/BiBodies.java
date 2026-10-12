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
package com.ash.drishti.server.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reads a BI file body (YAML) as a list of {@code (key, text value, depth)} triples, so a reference such as {@code dataset: trades-basic}
 * or {@code source: { connector: trading-store, kind: trade }} can be found without running the item. Array elements inherit the key of
 * the list ({@code datasets: [a, b]} gives two {@code datasets} triples). The root key that names the file itself
 * ({@code dataset: x} at the top of a dataset) is left out: it is the file, not a reference to another.
 */
final class BiBodies {

    private BiBodies() {
    }

    /** The keys under which one BI file names another of {@code targetType}. */
    static Set<String> keysFor(String targetType) {
        return switch (targetType) {
            case "dataset" -> Set.of("dataset", "datasets", "extracts", "sources", "source");
            case "report" -> Set.of("report", "reports");
            case "board" -> Set.of("board", "boards");
            case "notebook" -> Set.of("notebook", "notebooks");
            case "python" -> Set.of("python", "transform");
            default -> Set.of(targetType);
        };
    }

    static List<String[]> pairs(ObjectMapper yaml, String body, String ownKind) {
        List<String[]> out = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return out;
        }
        try {
            walk(yaml.readTree(body), "", 0, ownKind, out);
        } catch (Exception e) {
            return out;                                           // a body that does not parse names nothing
        }
        return out;
    }

    private static void walk(JsonNode n, String key, int depth, String ownKind, List<String[]> out) {
        if (n == null) {
            return;
        }
        if (n.isObject()) {
            n.fields().forEachRemaining(e -> walk(e.getValue(), e.getKey(), depth + 1, ownKind, out));
        } else if (n.isArray()) {
            n.forEach(x -> walk(x, key, depth, ownKind, out));
        } else if (n.isTextual() && !(depth == 1 && key.equals(ownKind))) {
            out.add(new String[] {key, n.asText(), Integer.toString(depth)});
        }
    }
}
