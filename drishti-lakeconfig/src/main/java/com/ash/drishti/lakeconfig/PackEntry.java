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
package com.ash.drishti.lakeconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a pack recommends for a kind's table (docs/architecture/LAKE_CONFIG.md section 3, decision L2), read from its {@code pack.yaml}:
 * its {@code lake:} block first ({@code lake: {tables: {<domain>.<kind>: {...}}}}, the same entry a lake file holds), else, for a pack
 * not yet moved, its Delta or Iceberg connector template ({@code connector}, else the first that serves the kind): the domain and table,
 * the promoted columns ({@code layout.<kind>.columns}), the vector fields, the flattened fields, the sort, and the ingest key, date and
 * latest-by ({@code ingest.<kind>}). The entry is a recommendation: {@link LakeFiles#addTable} copies it into a lake file once, and from
 * then on the lake file is the truth.
 *
 * @param table the table's folder (trade_endur), or null for the kind's name
 * @param entry the pack's own {@code lake:} entry when it has one, else null
 */
public record PackEntry(String domain, String table, String key, String date, List<String> promote, List<String> vectors,
        Map<String, String> flatten, List<String> sortBy, String latestBy, LakeTable entry) {

    /** The pack's entry for {@code kind}. */
    public static PackEntry read(Path pack, String kind, String connector) throws IOException {
        Path file = Files.isDirectory(pack) ? pack.resolve("pack.yaml") : pack;
        JsonNode m = new YAMLMapper().readTree(Files.readString(file));
        String name = m.path("pack").asText(file.getParent().getFileName().toString());
        JsonNode ingest = m.path("ingest").path(kind);
        for (var it = m.path("lake").path("tables").fields(); it.hasNext(); ) {
            var e = it.next();
            int dot = e.getKey().indexOf('.');
            String entryKind = e.getValue().path("kind").asText(dot > 0 ? e.getKey().substring(dot + 1) : "");
            String folder = dot > 0 ? e.getKey().substring(dot + 1) : "";
            // several tables of one kind (trade_endur, trade_calypso): the connector's table picks; else the first of the kind
            String wanted = connector == null ? null : tableOf(m, connector, kind);
            if (entryKind.equals(kind) && (wanted == null || wanted.equals(folder) || e.getValue().path("path").asText("").endsWith("/" + wanted))) {
                LakeTable t = Lakes.table(new Lakes.Values(file.toString(), System::getenv), file.toString(), "delta", e.getKey(), e.getValue(),
                        JsonNodeFactory.instance.objectNode());
                return new PackEntry(t.domain(), t.folder().equals(kind) ? null : t.folder(), t.id() != null ? t.id() : text(ingest, "key"),
                        t.date() != null ? t.date() : text(ingest, "date"), t.promote(), t.vectors(), t.flatten(), t.sortBy(),
                        t.latestBy() != null ? t.latestBy() : text(ingest, "latest-by"), t);
            }
        }
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
        if (ingest.path("flatten").size() > 0) {                // the ingest block's flatten replaces the layout's, as lake build
            flatten.clear();
            ingest.path("flatten").fields().forEachRemaining(e -> flatten.put(e.getKey(), e.getValue().asText()));
        }
        return new PackEntry(domain, table, text(ingest, "key"), text(ingest, "date"), promote, vectors, flatten, sortBy,
                text(ingest, "latest-by"), null);
    }

    /** The entry as a lake file's table ({@code <domain>.<kind>}): the pack's own {@code lake:} entry as written when it has one. */
    public LakeTable toTable(String kind) {
        if (entry != null) {
            return entry;
        }
        String folder = table == null ? kind : table;
        return new LakeTable(domain, folder, kind, domain + "/" + folder, "delta", List.of(), promote, vectors, flatten, key, date, latestBy,
                LakeTable.REPLACE, sortBy, Map.of(), null, Map.of(), null, Map.of());
    }

    /** The {@code table.<kind>} folder a pack's connector template names (null: none, the kind's own name). */
    private static String tableOf(JsonNode pack, String connector, String kind) {
        JsonNode defs = pack.path("connectors").isObject() ? pack.path("connectors") : pack.path("connector-templates");
        JsonNode st = defs.path(connector).path("settings");
        if (st.path(ConnectorLake.TABLES).isTextual()) {               // the lake form: lake-tables names the entries
            for (String n : st.path(ConnectorLake.TABLES).asText().split(",")) {
                String name = n.trim();
                int dot = name.indexOf('.');
                JsonNode e = pack.path("lake").path("tables").path(name);
                if (dot > 0 && e.path("kind").asText(name.substring(dot + 1)).equals(kind)) {
                    return name.substring(dot + 1);
                }
            }
            return null;
        }
        JsonNode t = st.path("table").isObject() ? st.path("table").path(kind) : st.path("table." + kind);
        return t.isTextual() && !t.asText().isBlank() ? t.asText() : null;
    }

    private static String text(JsonNode n, String key) {
        return n.path(key).isTextual() ? n.path(key).asText() : null;
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
