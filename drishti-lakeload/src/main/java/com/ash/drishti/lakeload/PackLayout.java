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
package com.ash.drishti.lakeload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a pack says about a kind, read from its {@code pack.yaml} as {@code drishti.py lake build --pack} reads it
 * ({@code tools/lake/build.py pack_defaults}): the domain and table of its Delta connector ({@code source.connector}, else the first that
 * serves the kind), the promoted columns ({@code layout.<kind>.columns}), the vector fields ({@code layout.<kind>.vectors}), the flattened
 * fields, the sort ({@code layout.<kind>.sort-by}), and the ingest key and date ({@code ingest.<kind>}). The loader's own keys win over the pack's.
 *
 * @param table the connector's {@code table.<kind>} folder (trade_endur), or null for the kind's name
 */
record PackLayout(String domain, String table, String key, String date, List<String> promote, List<String> vectors, Map<String, String> flatten,
        List<String> sortBy) {

    static PackLayout read(Path pack, String kind, String connector) throws IOException {
        Path file = Files.isDirectory(pack) ? pack.resolve("pack.yaml") : pack;
        JsonNode m = new YAMLMapper().readTree(Files.readString(file));
        String name = m.path("pack").asText(file.getParent().getFileName().toString());
        JsonNode defs = m.path("connectors").isObject() ? m.path("connectors") : m.path("connector-templates");
        JsonNode chosen = null;
        JsonNode first = null;
        String domain = name;
        for (var it = defs.fields(); it.hasNext(); ) {
            var e = it.next();
            JsonNode c = e.getValue();
            if (!"delta".equals(c.path("plugin").asText()) && !"iceberg".equals(c.path("plugin").asText())) {
                continue;
            }
            first = first == null ? c : first;
            if (connector != null ? e.getKey().equals(connector) : chosen == null && serves(c, kind)) {
                chosen = c;
            }
        }
        if (connector == null && chosen == null) {
            chosen = first;                                    // as lake build: the pack's first Delta connector
        }
        if (connector != null && chosen == null) {
            throw new IllegalArgumentException("source.connector " + connector + ": the pack " + name + " has no Delta or Iceberg connector of that name");
        }
        List<String> promote = new ArrayList<>();
        List<String> vectors = new ArrayList<>();
        List<String> sortBy = new ArrayList<>();
        Map<String, String> flatten = new LinkedHashMap<>();
        String table = null;
        if (chosen != null) {
            JsonNode st = chosen.path("settings");
            domain = st.path("domain").asText(name);
            JsonNode lay = st.path("layout").path(kind);
            promote.addAll(list(lay.has("columns") ? lay.path("columns") : st.path("layout." + kind + ".columns")));
            vectors.addAll(list(lay.has("vectors") ? lay.path("vectors") : st.path("layout." + kind + ".vectors")));
            sortBy.addAll(list(lay.has("sort-by") ? lay.path("sort-by") : st.path("layout." + kind + ".sort-by")));
            lay.path("flatten").fields().forEachRemaining(e -> flatten.put(e.getKey(), e.getValue().asText()));
            JsonNode t = st.path("table").isObject() ? st.path("table").path(kind) : st.path("table." + kind);
            table = t.isMissingNode() || t.isNull() || t.asText().isBlank() ? null : t.asText();
        }
        JsonNode ingest = m.path("ingest").path(kind);
        if (ingest.path("flatten").size() > 0) {                // the ingest block's flatten replaces the layout's, as lake build
            flatten.clear();
            ingest.path("flatten").fields().forEachRemaining(e -> flatten.put(e.getKey(), e.getValue().asText()));
        }
        String key = ingest.path("key").isTextual() ? ingest.path("key").asText() : null;
        String date = ingest.path("date").isTextual() ? ingest.path("date").asText() : null;
        return new PackLayout(domain, table, key, date, promote, vectors, flatten, sortBy);
    }

    private static boolean serves(JsonNode connector, String kind) {
        for (JsonNode k : connector.path("kinds")) {
            if (k.asText().equals(kind)) {
                return true;
            }
        }
        return false;
    }

    /** A YAML list, or one comma-separated text. */
    private static List<String> list(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n.isArray()) {
            n.forEach(x -> {
                if (!x.asText().isBlank()) {
                    out.add(x.asText().trim());
                }
            });
        } else if (n.isTextual()) {
            for (String s : n.asText().split(",")) {
                if (!s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }
}
