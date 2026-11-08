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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A shipped, tracked lake file never changes at run time: the shipped demo lake file holds every table a shipped pack's connector reads
 * from it (and its jar copy is the same file), a start after the demo lake's move leaves it byte for byte, and an entry added for a site's
 * own connector is written at the end of the tables with every other line kept. Every write is under a temporary folder.
 */
class LakeFileWritesTest {

    /** The repository root (the module's parent folder, where config/ is). */
    static final Path REPO = Path.of("").toAbsolutePath().getParent();
    static final Path DEMO = REPO.resolve("config/lakes/demo.yaml");
    static final Path DEMO_JAR = REPO.resolve("drishti-lakeconfig/src/main/resources/drishti/lakes/demo.yaml");

    @TempDir
    Path tmp;

    static Map<String, JsonNode> shippedDemoTables() throws Exception {
        return LakeFiles.packTables(LakeFiles.packFiles(List.of(REPO.resolve("config/packs"), REPO.resolve("examples/packs"))), "demo");
    }

    @Test
    void theShippedDemoLakeFileHasEveryTableAShippedConnectorReads() throws Exception {
        Map<String, JsonNode> wanted = shippedDemoTables();
        assertTrue(wanted.size() > 100, "the shipped packs' demo connectors name " + wanted.size() + " tables");
        List<String> missing = LakeFiles.missing(DEMO, wanted);
        assertEquals(List.of(), missing, "config/lakes/demo.yaml lacks tables a shipped pack's connector reads: add them with "
                + "LakesCli pack-tables --dir config/lakes --lake demo --packs config/packs,examples/packs --write, then copy the file to " + DEMO_JAR);
        assertEquals(Files.readString(DEMO), Files.readString(DEMO_JAR), "the jar copy of the demo lake file is config/lakes/demo.yaml");
    }

    @Test
    void aStartAfterTheMoveLeavesACompleteDemoLakeFileByteForByte() throws Exception {
        Path lakes = Files.createDirectories(tmp.resolve("lakes"));
        Path conns = Files.createDirectories(tmp.resolve("connectors"));
        Files.copy(DEMO, lakes.resolve("demo.yaml"));
        byte[] before = Files.readAllBytes(lakes.resolve("demo.yaml"));
        // connector files in the form every shipped template had before the lake form: the old default root, its domain and kinds
        int n = 0;
        for (Path pack : LakeFiles.packFiles(List.of(REPO.resolve("config/packs"), REPO.resolve("examples/packs")))) {
            JsonNode m = LakeFiles.YAML.readTree(Files.readString(pack));
            for (var it = m.path("connectors").fields(); it.hasNext(); ) {
                var c = it.next();
                JsonNode st = c.getValue().path("settings");
                if (!"delta".equals(c.getValue().path("plugin").asText()) || !"demo".equals(st.path("lake").asText())) {
                    continue;
                }
                StringBuilder y = new StringBuilder("# Generated at first start from the connector template of pack 'x'.\nplugin: delta\nkinds:\n");
                StringBuilder s = new StringBuilder("settings:\n  root: ${DRISHTI_DELTA_ROOT:./data/delta}\n");
                String domain = null;
                for (String t : st.path("lake-tables").asText().split(",")) {
                    String name = t.strip();
                    String folder = name.substring(name.indexOf('.') + 1);
                    String kind = m.path("lake").path("tables").path(name).path("kind").asText(folder);
                    domain = name.substring(0, name.indexOf('.'));
                    y.append("- ").append(kind).append('\n');
                    if (!kind.equals(folder)) {
                        s.append("  table.").append(kind).append(": ").append(folder).append('\n');
                    }
                }
                s.append("  domain: ").append(domain).append('\n');
                Files.writeString(conns.resolve(c.getKey() + ".yaml"), y.append(s).toString());
                n++;
            }
        }
        assertTrue(n > 10, n + " connector files");
        LakeFiles.Migration m = LakeFiles.adoptDemo(conns, lakes, true);
        assertEquals(n, m.connectorFiles().size(), m.notes().toString());
        assertTrue(m.lakeFiles().isEmpty(), "nothing to add: " + m.lakeFiles().keySet());
        assertEquals(new String(before), Files.readString(lakes.resolve("demo.yaml")));
        // a second start: the connectors read lake: demo already; nothing is written at all
        LakeFiles.Migration again = LakeFiles.adoptDemo(conns, lakes, true);
        assertTrue(again.lakeFiles().isEmpty() && again.connectorFiles().isEmpty(), again.notes().toString());
        assertEquals(new String(before), Files.readString(lakes.resolve("demo.yaml")));
    }

    @Test
    void aSitesOwnConnectorAddsOnlyItsEntryAndKeepsEveryOtherLine() throws Exception {
        Path lakes = Files.createDirectories(tmp.resolve("lakes"));
        Path conns = Files.createDirectories(tmp.resolve("connectors"));
        Files.copy(DEMO, lakes.resolve("demo.yaml"));
        String before = Files.readString(lakes.resolve("demo.yaml"));
        Files.writeString(conns.resolve("site-store.yaml"), """
                plugin: delta
                kinds: [site-thing, var]
                settings:
                  root: ${DRISHTI_DELTA_ROOT:./data/delta}
                  domain: risk
                  layout.site-thing.columns: THING_ID,NAME
                """);
        LakeFiles.adoptDemo(conns, lakes, true);
        String after = Files.readString(lakes.resolve("demo.yaml"));
        assertTrue(after.startsWith(before), "every line of the file is kept, the entries come after them");
        assertEquals("  risk.site-thing: {promote: [THING_ID, NAME]}\n  risk.var: {}\n", after.substring(before.length()));
        Lake l = Lakes.readFile(lakes.resolve("demo.yaml"), k -> null);
        assertEquals(List.of("THING_ID", "NAME"), l.require("risk", "site-thing").promote());
        // the connector file is a site file: kept beside it, rewritten to the lake form
        try (var s = Files.list(conns)) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().startsWith("site-store.yaml.before-lake-")));
        }
        assertEquals("risk.site-thing,risk.var", LakeFiles.YAML.readTree(Files.readString(conns.resolve("site-store.yaml"))).path("settings").path("lake-tables").asText());
    }

    @Test
    void anEntryIsAddedInTheFilesStyleAndAnEmptyLakeKeepsItsComments() throws Exception {
        Path f = tmp.resolve("risklake.yaml");
        String empty = """
                # the header
                # and its description
                lake: risklake
                format: delta
                root: ${drishti.data.dir:./data}/delta/risklake
                defaults: {load-mode: replace}
                tables: {}
                """;
        Files.writeString(f, empty);
        ObjectNode e = JsonNodeFactory.instance.objectNode();
        e.put("kind", "trade");
        e.putArray("promote").add("TRADE_ID").add("PRODUCT_TYPE");
        e.put("file-rows", 250000);
        e.put("id", "TRADE_ID");
        Map<String, JsonNode> one = new LinkedHashMap<>();
        one.put("trading.trade_endur", e);
        assertEquals(List.of("trading.trade_endur"), LakeFiles.addEntries(f, one));
        assertEquals(empty.replace("tables: {}\n", "tables:\n  trading.trade_endur: {kind: trade, promote: [TRADE_ID, PRODUCT_TYPE], file-rows: 250000, "
                + "id: TRADE_ID}\n"), Files.readString(f));
        String once = Files.readString(f);
        assertEquals(List.of(), LakeFiles.addEntries(f, one), "an entry there is the truth: the file is not written");
        assertEquals(once, Files.readString(f));
        // a long entry: the name, then a line per setting, its list folded as the shipped files are
        ObjectNode wide = JsonNodeFactory.instance.objectNode();
        var p = wide.putArray("promote");
        for (int i = 0; i < 30; i++) {
            p.add("COLUMN_NUMBER_" + i);
        }
        wide.putArray("vectors").add("PNL");
        wide.put("date", "BUSINESS_DATE");
        Map<String, JsonNode> two = new LinkedHashMap<>();
        two.put("risk.wide", wide);
        LakeFiles.addEntries(f, two);
        String text = Files.readString(f);
        assertTrue(text.startsWith(once), text);
        String[] added = text.substring(once.length()).split("\n");
        assertEquals("  risk.wide:", added[0]);
        assertTrue(added[1].startsWith("    promote: [COLUMN_NUMBER_0, ") && added[1].endsWith(","), added[1]);
        assertTrue(added[2].startsWith("      COLUMN_NUMBER_"), added[2]);
        assertTrue(java.util.Arrays.stream(added).allMatch(x -> x.length() <= LakeFiles.WIDTH), text);
        assertEquals("    vectors: [PNL]", added[added.length - 2]);
        assertEquals("    date: BUSINESS_DATE", added[added.length - 1]);
        Lake l = Lakes.readFile(f, k -> null);
        assertEquals(30, l.require("risk", "wide").promote().size());
        assertEquals("TRADE_ID", l.require("trading", "trade", "trade_endur").id());
    }

    @Test
    void theOfficialResultsReaderIsNotMovedIntoTheDemoLake() throws Exception {
        Path lakes = Files.createDirectories(tmp.resolve("lakes"));
        Path conns = Files.createDirectories(tmp.resolve("connectors"));
        for (String n : List.of("demo.yaml", "banking/var-official.yaml")) {         // the banking lakes in their module's folder
            Files.copy(REPO.resolve("config/lakes").resolve(n), Files.createDirectories(lakes.resolve(n).getParent()).resolve(Path.of(n).getFileName()));
        }
        String results = "plugin: delta\nkinds:\n- var-official\nsettings:\n  root: ${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}\n"
                + "  domain: risk-results\n";
        Files.writeString(conns.resolve("var-results.yaml"), results);
        LakeFiles.Migration m = LakeFiles.adoptDemo(conns, lakes, true);
        assertTrue(m.connectorFiles().isEmpty() && m.lakeFiles().isEmpty(), m.notes().toString());
        assertEquals(results, Files.readString(conns.resolve("var-results.yaml")));
        assertEquals(Files.readString(DEMO), Files.readString(lakes.resolve("demo.yaml")));
    }

    @Test
    void entriesGoAtTheEndOfTheTablesBlockNotTheEndOfTheFile() throws Exception {
        Path f = tmp.resolve("x.yaml");
        String text = "lake: x\nroot: /x\ntables:\n    a.b: {id: B}\n    # a comment in the block\n    a.c: null\n\n# after\nstorage: {region: eu}\n";
        Files.writeString(f, text);
        Map<String, JsonNode> one = new LinkedHashMap<>();
        one.put("a.d", JsonNodeFactory.instance.objectNode().put("id", "${X:1}"));
        LakeFiles.addEntries(f, one);
        assertEquals(text.replace("    a.c: null\n", "    a.c: null\n    a.d: {id: '${X:1}'}\n"), Files.readString(f));
    }
}
