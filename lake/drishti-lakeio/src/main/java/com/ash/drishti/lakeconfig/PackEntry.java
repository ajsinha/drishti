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
import java.util.stream.Stream;

/**
 * What a pack recommends for a kind's table (docs/architecture/DATA_ARCHITECTURE.md section 3, decision L2), read from its {@code pack.yaml}:
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

    /**
     * The pack's {@code pack.yaml} with the Delta read template of each source module that requires the pack
     * ({@code modules/source-<system>/connectors/delta.yaml}, connector {@code <kind>-<system>}) added to its connectors where the pack does
     * not declare that name itself: a source system is a module of its own, and its connectors have that one home. The modules folder is
     * found above the pack, else {@code drishti.modules.dir}.
     */
    static JsonNode manifest(Path file) throws IOException {
        JsonNode m = new YAMLMapper().readTree(Files.readString(file));
        String pack = m.path("pack").asText("");
        Path modules = modulesDir(file);
        if (pack.isEmpty() || modules == null || !(m instanceof com.fasterxml.jackson.databind.node.ObjectNode root)) {
            return m;
        }
        com.fasterxml.jackson.databind.node.ObjectNode defs = root.path("connectors").isObject()
                ? (com.fasterxml.jackson.databind.node.ObjectNode) root.get("connectors") : root.putObject("connectors");
        try (Stream<Path> s = Files.list(modules)) {
            for (Path dir : s.filter(d -> d.getFileName().toString().startsWith("source-")).sorted().toList()) {
                Path mod = dir.resolve("module.yaml");
                Path tpl = dir.resolve("connectors").resolve("delta.yaml");
                if (!Files.isRegularFile(mod) || !Files.isRegularFile(tpl) || !new YAMLMapper().readTree(Files.readString(mod)).path("requires").has(pack)) {
                    continue;
                }
                JsonNode c = new YAMLMapper().readTree(Files.readString(tpl));
                String kind = c.path("kinds").path(0).asText("");
                String name = kind + "-" + dir.getFileName().toString().substring("source-".length());
                if (!kind.isEmpty() && !defs.has(name)) {
                    defs.set(name, c);
                }
            }
        }
        return root;
    }

    private static Path modulesDir(Path file) {
        String set = System.getProperty("drishti.modules.dir");
        if (set != null && Files.isDirectory(Path.of(set))) {
            return Path.of(set);
        }
        for (Path p = file.toAbsolutePath().getParent(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("modules")) && Files.isDirectory(p.resolve("config"))) {
                return p.resolve("modules");
            }
        }
        return null;
    }

    /** The pack's entry for {@code kind}. */
    public static PackEntry read(Path pack, String kind, String connector) throws IOException {
        Path file = Files.isDirectory(pack) ? pack.resolve("pack.yaml") : pack;
        JsonNode m = manifest(file);
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
                Map<String, String> flat = t.flatten();
                if (ingest.path("flatten").size() > 0) {        // the ingest block's flatten replaces the entry's, as on the older path
                    flat = new LinkedHashMap<>();
                    Map<String, String> into = flat;
                    ingest.path("flatten").fields().forEachRemaining(f -> into.put(f.getKey(), f.getValue().asText()));
                }
                String key = t.id() != null ? t.id() : text(ingest, "key") != null ? text(ingest, "key") : contractKey(file.getParent(), kind);
                return new PackEntry(t.domain(), t.folder().equals(kind) ? null : t.folder(), key,
                        t.date() != null ? t.date() : text(ingest, "date"), t.promote(), t.vectors(), flat, t.sortBy(),
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
        return new PackEntry(domain, table, text(ingest, "key") != null ? text(ingest, "key") : contractKey(file.getParent(), kind), text(ingest, "date"),
                promote, vectors, flatten, sortBy,
                text(ingest, "latest-by"), null);
    }

    /**
     * The entry as a lake file's table ({@code <domain>.<kind>}): the pack's own {@code lake:} entry as written when it has one, with the
     * kind's key as its {@code id:} when the entry names none (the ingest key, else the key of the kind's data contract), so a table created
     * by its first load never rejects every row for want of an id.
     */
    public LakeTable toTable(String kind) {
        if (entry != null) {
            return entry.id() == null && key != null ? entry.withId(key) : entry;
        }
        String folder = table == null ? kind : table;
        return new LakeTable(domain, folder, kind, domain + "/" + folder, "delta", List.of(), promote, vectors, flatten, key, date, latestBy,
                LakeTable.REPLACE, sortBy, Map.of(), null, Map.of(), Map.of(), null, Map.of());
    }

    /**
     * The key of a kind's data contract ({@code <pack>/contracts/<kind>.contract.yaml}, {@code key:}): every kind declares one. Only a
     * single field is an id (a row's one value); a key of several fields, or none, gives null.
     */
    public static String contractKey(Path packDir, String kind) {
        if (packDir == null || kind == null) {
            return null;
        }
        Path f = packDir.resolve("contracts").resolve(kind + ".contract.yaml");
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            JsonNode k = new YAMLMapper().readTree(Files.readString(f)).path("key");
            if (k.isArray() && k.size() == 1 && k.get(0).isTextual() && !k.get(0).asText().isBlank()) {
                return k.get(0).asText().trim();
            }
            return k.isTextual() && !k.asText().isBlank() && !k.asText().contains(",") ? k.asText().trim() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** One table a pack's Delta or Iceberg connector writes: its folder ({@code trade_endur}), its kind and the connector. */
    public record PackTable(String table, String kind, String connector) {}

    /**
     * Every table the pack's Delta and Iceberg connectors write, by folder ({@code trade_endur} of {@code trade-endur}; a kind's own name
     * when the connector names no table): a tar's folder or a file's name may be a table name, which resolves to its kind and connector.
     */
    public static List<PackTable> tables(Path pack) throws IOException {
        Path file = Files.isDirectory(pack) ? pack.resolve("pack.yaml") : pack;
        JsonNode m = manifest(file);
        JsonNode defs = m.path("connectors").isObject() ? m.path("connectors") : m.path("connector-templates");
        List<PackTable> out = new ArrayList<>();
        for (var it = defs.fields(); it.hasNext(); ) {
            var e = it.next();
            JsonNode c = e.getValue();
            if (!"delta".equals(c.path("plugin").asText()) && !"iceberg".equals(c.path("plugin").asText())) {
                continue;
            }
            for (JsonNode k : c.path("kinds")) {
                String kind = k.asText();
                String folder = tableOf(m, e.getKey(), kind);
                out.add(new PackTable(folder == null ? kind : folder, kind, e.getKey()));
            }
        }
        return out;
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
