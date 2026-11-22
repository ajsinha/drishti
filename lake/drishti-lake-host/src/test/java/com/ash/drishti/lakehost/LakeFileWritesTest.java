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
package com.ash.drishti.lakehost;

import com.ash.lake.config.Lake;
import com.ash.lake.config.LakeFiles;
import com.ash.lake.config.Lakes;

import com.ash.lake.tck.TestRoots;
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

    static final com.fasterxml.jackson.dataformat.yaml.YAMLMapper YAML = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper();

    /** The repository root (the module's parent folder, where config/ is). */
    static final Path REPO = Path.of(TestRoots.module()).getParent().getParent();
    static final Path DEMO = REPO.resolve("config/lakes/demo.yaml");
    static final Path DEMO_JAR = REPO.resolve("lake/drishti-lake-host/src/main/resources/drishti/lakes/demo.yaml");

    @TempDir
    Path tmp;

    /** Every shipped pack's pack.yaml where the one resolution finds it (site, modules, kernel), and the example packs. */
    static List<Path> shippedPackFiles() throws Exception {
        List<Path> out = new java.util.ArrayList<>(com.ash.drishti.api.resolution.ConfigResolution.repository(REPO).packDirs().stream()
                .map(d -> d.resolve("pack.yaml")).toList());
        out.addAll(LakeFiles.packFiles(DrishtiLake.runtime(), List.of(REPO.resolve("examples/packs"))));
        return out;
    }

    static Map<String, JsonNode> shippedDemoTables() throws Exception {
        return LakeFiles.packTables(DrishtiLake.runtime(), shippedPackFiles(), "demo");
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
    void anEntryIsAddedInTheFilesStyleAndAnEmptyLakeKeepsItsComments() throws Exception {
        Path f = tmp.resolve("risklake.yaml");
        String empty = """
                # the header
                # and its description
                lake: risklake
                format: delta
                root: ${lake.data.dir:${drishti.data.dir}}/delta/risklake
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
        assertTrue(java.util.Arrays.stream(added).allMatch(x -> x.length() <= 150), text);
        assertEquals("    vectors: [PNL]", added[added.length - 2]);
        assertEquals("    date: BUSINESS_DATE", added[added.length - 1]);
        Lake l = Lakes.readFile(f, k -> null);
        assertEquals(30, l.require("risk", "wide").promote().size());
        assertEquals("TRADE_ID", l.require("trading", "trade", "trade_endur").id());
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
