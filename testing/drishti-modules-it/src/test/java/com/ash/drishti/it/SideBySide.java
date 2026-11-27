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

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The side-by-side helper of R2 (docs/plans/R2_CUT_PLAN.md, "Switching a route"): one packaged server with Drishti Risk's JARs in its
 * {@code lib/engines}, asked the same route twice, {@code path=in-tree} and {@code path=engine}; the answers' named fields must be equal,
 * and the engine's exact figures are compared with a raw-bit reference. Every difference is kept with its words; a test asserts none.
 */
final class SideBySide {

    final List<String> differences = new ArrayList<>();
    final List<String> bitDifferences = new ArrayList<>();
    int compared;
    int sameBits;

    /** Copies every {@code *.jar} of {@code jars} into {@code <home>/lib/engines}, as a site copies them. */
    static void dropJars(Path jars, Path home) throws IOException {
        Path engines = Files.createDirectories(home.resolve("lib").resolve("engines"));
        try (var list = Files.list(jars)) {
            for (Path j : list.filter(x -> x.getFileName().toString().endsWith(".jar")).toList()) {
                Files.copy(j, engines.resolve(j.getFileName()));
            }
        }
    }

    /** The route {@code pathAndQuery} on both paths; the named fields must be equal (JSON equality: numbers, text, null). */
    JsonNode[] both(PackagedServer server, String what, String pathAndQuery, String... fields) throws IOException, InterruptedException {
        String sep = pathAndQuery.contains("?") ? "&" : "?";
        JsonNode inTree = server.get(pathAndQuery + sep + "path=in-tree");
        JsonNode engine = server.get(pathAndQuery + sep + "path=engine");
        compared++;
        for (String f : fields) {
            JsonNode a = inTree.path(f);
            JsonNode b = engine.path(f);
            if (!Objects.equals(norm(a), norm(b))) {
                differences.add(what + " " + f + ": " + first(a, b));
            }
        }
        return new JsonNode[] {inTree, engine};
    }

    /** The same for a POST of {@code json}. */
    JsonNode[] bothPost(PackagedServer server, String what, String pathAndQuery, String json, String... fields) throws IOException, InterruptedException {
        String sep = pathAndQuery.contains("?") ? "&" : "?";
        JsonNode inTree = server.post(pathAndQuery + sep + "path=in-tree", json);
        JsonNode engine = server.post(pathAndQuery + sep + "path=engine", json);
        compared++;
        for (String f : fields) {
            if (!Objects.equals(norm(inTree.path(f)), norm(engine.path(f)))) {
                differences.add(what + " " + f + ": " + first(inTree.path(f), engine.path(f)));
            }
        }
        return new JsonNode[] {inTree, engine};
    }

    /** The engine's exact figure against a raw-bit reference (a golden's double, a recorder's bits). */
    void bits(String what, double reference, double engine) {
        if (Double.doubleToRawLongBits(reference) == Double.doubleToRawLongBits(engine)) {
            sameBits++;
        } else if (bitDifferences.size() < 5000) {
            bitDifferences.add(what + ": reference " + reference + ", engine " + engine);
        }
    }

    /** The first place two answers' field differ: the element (an array) or the key (an object), both sides. */
    static String first(JsonNode a, JsonNode b) {
        if (a.isArray() && b.isArray()) {
            for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
                if (!Objects.equals(norm(a.path(i)), norm(b.path(i)))) {
                    return "[" + i + "] " + first(a.path(i), b.path(i)) + " (sizes " + a.size() + ", " + b.size() + ")";
                }
            }
        }
        if (a.isObject() && b.isObject()) {
            java.util.Set<String> keys = new java.util.LinkedHashSet<>();
            a.fieldNames().forEachRemaining(keys::add);
            b.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) {
                if (!Objects.equals(norm(a.path(k)), norm(b.path(k)))) {
                    return "." + k + " " + first(a.path(k), b.path(k));
                }
            }
        }
        String x = String.valueOf(a);
        String y = String.valueOf(b);
        return "in-tree " + x.substring(0, Math.min(200, x.length())) + ", engine " + y.substring(0, Math.min(200, y.length()));
    }

    private static Object norm(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        return n.isNumber() ? (Object) n.decimalValue().stripTrailingZeros() : n.isContainerNode() ? n.toString() : n.asText();
    }
}
