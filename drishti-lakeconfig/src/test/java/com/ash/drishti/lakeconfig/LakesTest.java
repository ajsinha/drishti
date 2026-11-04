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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lake files: reading with defaults and environment references, every refusal naming its key, several tables of one kind, a
 * connector's settings from its lake, the one migration warning, the migration of old connector files, the check against a table on disk,
 * a pack's entry copied once, and the command the Python tools run. Every file is written under a temporary folder.
 */
class LakesTest {

    @TempDir
    Path tmp;

    static final Function<String, String> ENV = Map.of("BUCKET", "acme-lake")::get;

    static final String MAIN = """
            lake: main
            format: delta
            root: s3://${BUCKET}/delta
            storage:
              region: ${AWS_REGION:us-east-1}
            defaults:
              sort-by: id
              file-rows: 250000
            tables:
              trading.trade_endur:
                kind: trade
                partition-by: [PRODUCT_TYPE]
                promote: [TRADE_ID, PRODUCT_TYPE, COUNTERPARTY]
              trading.trade_murex:
                kind: trade
                partition-by: [PRODUCT_TYPE]
              risk.pnl-vector:
                promote: [VALUE_TYPE, TRADE_ID]
                vectors: [PNL]
                load-mode: append
            """;

    private Lake main() {
        return Lakes.parse(MAIN, "main", ENV);
    }

    @Test
    void aLakeIsReadWithItsDefaultsAndReferences() {
        Lake l = main();
        assertEquals("s3://acme-lake/delta", l.root());
        assertEquals("us-east-1", l.storage().get("region"));
        LakeTable t = l.require("trading", "trade", "trade_endur");
        assertEquals(List.of("business_date", "PRODUCT_TYPE"), t.partitionColumns());
        assertEquals(List.of("id"), t.sortBy());
        assertEquals(250000, t.layoutNumber("file-rows", 0));
        assertEquals("s3://acme-lake/delta/trading/trade_endur", l.location(t));
        LakeTable v = l.require(null, "pnl-vector");
        assertTrue(v.appends());
        assertEquals("risk/pnl-vector", v.path());
        assertFalse(l.require(null, "trade", "trade_murex").appends());
    }

    @Test
    void severalTablesOfOneKindAreNamedNeverGuessed() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> main().require(null, "trade"));
        assertTrue(e.getMessage().contains("trading.trade_endur") && e.getMessage().contains("trading.trade_murex"), e.getMessage());
        IllegalArgumentException none = assertThrows(IllegalArgumentException.class, () -> main().require(null, "desk-pnl"));
        assertTrue(none.getMessage().contains("never creates a table"), none.getMessage());
    }

    @Test
    void everyRefusalNamesItsKey() {
        assertRefused("lake: main\nroot: /x\ntables:\n  t.k:\n    partition-by: [business_date]\n", "business_date is always the first");
        assertRefused("lake: main\nroot: /x\ntables:\n  t.k:\n    partition-by: [A, B, C, D, E]\n", "at most 4");
        assertRefused("lake: main\nroot: /x\ntables:\n  t.k:\n    load-mode: upsert\n", "load-mode upsert");
        assertRefused("lake: main\nroot: /x\ntables:\n  t.k:\n    colour: red\n", "unknown key tables.t.k.colour");
        assertRefused("lake: main\nformat: parquet\nroot: /x\n", "delta or iceberg");
        assertRefused("lake: main\nroot: s3://b\nstorage:\n  secret-key: abc\n", "a secret is never written");
        assertRefused("lake: other\nroot: /x\n", "differs from the file's name");
        assertRefused("lake: main\nformat: iceberg\nroot: /x\ntables:\n  t.k:\n    partition-by: [A]\n", "partition-by is Delta's");
        assertRefused("lake: main\nroot: mem://feeds\n", "memory.max-mb");
        assertRefused("lake: main\nroot: s3://${NOT_SET}/x\n", "NOT_SET is not set");
        assertRefused("lake: main\nroot: /x\ntables:\n  nodot: {}\n", "<domain>.<kind>");
    }

    private static void assertRefused(String yaml, String words) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Lakes.parse(yaml, "main", ENV));
        assertTrue(e.getMessage().contains(words), e.getMessage());
    }

    @Test
    void referencesNestAndADottedNameIsReadAsTheServerReadsIt() {
        // the build's surefire sets drishti.data.dir (target/test-data), and a system property comes before the environment's form
        String before = System.clearProperty("drishti.data.dir");
        try {
            Function<String, String> env = Map.of("DRISHTI_DATA_DIR", "/srv/drishti")::get;
            Lake l = Lakes.parse("lake: main\nroot: ${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}\n", "main", env);
            assertEquals("/srv/drishti/delta", l.root());
            assertEquals("./data/delta", Lakes.parse("lake: main\nroot: ${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}\n", "main",
                    k -> null).root());
            System.setProperty("drishti.data.dir", "/opt/drishti");
            assertEquals("/opt/drishti/delta", Lakes.parse("lake: main\nroot: ${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}\n",
                    "main", env).root());
        } finally {
            if (before == null) {
                System.clearProperty("drishti.data.dir");
            } else {
                System.setProperty("drishti.data.dir", before);
            }
        }
    }

    @Test
    void aCatalogLakeNeedsNoRootAndTheDemoLakeIsShipped() {
        Lake rest = Lakes.parse("lake: main\nformat: iceberg\ncatalog: {type: rest, uri: 'http://c:8181', namespace: risk}\n"
                + "tables:\n  risk.trade: {}\n", "main", ENV);
        Map<String, String> s = ConnectorLake.expand("t", "iceberg", List.of("trade"), Map.of("lake", "main"), () -> Lakes.of(rest), x -> { });
        assertFalse(s.containsKey("root"));
        assertEquals("rest", s.get("catalog"));
        assertEquals("http://c:8181", s.get("uri"));
        assertEquals("risk", s.get("domain"));
        Lake demo = Lakes.read(tmp.resolve("no-such-folder"), k -> null).get("demo");      // the jar's copy of config/lakes/demo.yaml
        assertEquals(List.of("business_date", "PRODUCT_TYPE"), demo.require("trading", "trade", "trade_endur").partitionColumns());
    }

    @Test
    void aChildProcessIsHandedTheSettingsTheLakeFilesUse() throws Exception {
        Path lakes = Files.createDirectories(tmp.resolve("env-lakes"));
        Files.writeString(lakes.resolve("main.yaml"), "lake: main\nroot: ${SITE_LAKE_ROOT:/x}/delta\n");
        System.setProperty(Lakes.PROPERTY, lakes.toString());
        Lakes.useProperties(Map.of("SITE_LAKE_ROOT", "/srv/lake", "UNRELATED", "y")::get);     // as a --setting of the server
        try {
            Map<String, String> env = Lakes.environment();
            assertEquals("/srv/lake", env.get("SITE_LAKE_ROOT"));
            assertFalse(env.containsKey("UNRELATED"));
            assertEquals("/srv/lake/delta", Lakes.read(lakes, env::get).get("main").root());   // the child resolves it the same way
        } finally {
            Lakes.useProperties(null);
            System.clearProperty(Lakes.PROPERTY);
        }
    }

    @Test
    void anInMemoryStoreIsALake() {
        Lake l = Lakes.parse("lake: feeds\nroot: mem://feeds\nmemory:\n  max-mb: 512\n  contract: vendor-no-disk\ntables:\n  news.news: {}\n",
                "feeds", ENV);
        assertTrue(l.inMemory());
        assertEquals("feeds", l.store());
        assertEquals("512", l.memory().get("max-mb"));
        assertEquals("feeds", Lakes.of(l).memoryStore("feeds").orElseThrow().name());
    }

    @Test
    void aConnectorThatNamesALakeGetsItsSettingsFromIt() {
        Lakes lakes = Lakes.of(main());
        List<String> warned = new ArrayList<>();
        Map<String, String> s = ConnectorLake.expand("risk-store", "delta", List.of("pnl-vector"), Map.of("lake", "main", "refresh-seconds", "5"),
                () -> lakes, warned::add);
        assertEquals("s3://acme-lake/delta", s.get("root"));
        assertEquals("risk", s.get("domain"));
        assertEquals("VALUE_TYPE,TRADE_ID", s.get("layout.pnl-vector.columns"));
        assertEquals("PNL", s.get("layout.pnl-vector.vectors"));
        assertEquals("us-east-1", s.get("s3.region"));
        assertEquals("5", s.get("refresh-seconds"));
        assertFalse(s.containsKey("lake"));

        Map<String, String> endur = ConnectorLake.expand("trade-endur", "delta", List.of("trade"),
                Map.of("lake", "main", "lake-tables", "trading.trade_endur"), () -> lakes, warned::add);
        assertEquals("trade_endur", endur.get("table.trade"));
        assertEquals("trading", endur.get("domain"));

        IllegalArgumentException both = assertThrows(IllegalArgumentException.class, () -> ConnectorLake.expand("x", "delta", List.of("pnl-vector"),
                Map.of("lake", "main", "root", "/elsewhere"), () -> lakes, warned::add));
        assertTrue(both.getMessage().contains("[root]"), both.getMessage());
        assertTrue(warned.isEmpty());

        Map<String, String> other = ConnectorLake.expand("files", "file", List.of(), Map.of("root", "/x"), () -> {
            throw new AssertionError("a connector of another plugin never reads the lakes");
        }, warned::add);
        assertEquals("/x", other.get("root"));
    }

    @Test
    void anOldConnectorStartsWithOneWarning() {
        List<String> warned = new ArrayList<>();
        Map<String, String> old = Map.of("root", "./data/delta", "domain", "risk", "layout.pnl-vector.columns", "A");
        assertEquals(old, ConnectorLake.expand("old-one", "delta", List.of("pnl-vector"), old, Lakes::of, warned::add));
        ConnectorLake.expand("old-one", "delta", List.of("pnl-vector"), old, Lakes::of, warned::add);
        assertEquals(1, warned.size());
        assertTrue(warned.get(0).contains("drishti.py lake migrate-config"));
    }

    @Test
    void migrationWritesLakeFilesAndRewritesTheConnectors() throws Exception {
        Path conns = Files.createDirectories(tmp.resolve("connectors"));
        Files.writeString(conns.resolve("trade-endur.yaml"), """
                # the header stays
                plugin: delta
                kinds: [trade]
                settings:
                  root: /lake/delta
                  domain: trading
                  table.trade: trade_endur
                  source-name: trade-endur
                  layout.trade.columns: TRADE_ID,PRODUCT_TYPE
                  layout.trade.file-rows: 250000
                """);
        Files.writeString(conns.resolve("risk-store.yaml"), """
                plugin: delta
                kinds: [pnl-vector]
                settings:
                  root: /lake/delta
                  domain: risk
                  s3.region: eu-west-2
                  layout.pnl-vector.columns: VALUE_TYPE
                  layout.pnl-vector.vectors: PNL
                """);
        Files.writeString(conns.resolve("files.yaml"), "plugin: file\nkinds: [x]\nsettings:\n  root: /files\n");
        Path lakesDir = tmp.resolve("lakes");
        LakeFiles.Migration dry = LakeFiles.migrate(conns, lakesDir, false);
        assertFalse(Files.exists(lakesDir));
        assertEquals(1, dry.lakeFiles().size());
        LakeFiles.migrate(conns, lakesDir, true);
        Lakes lakes = Lakes.read(lakesDir, ENV);
        Lake l = lakes.get("delta");
        assertEquals("/lake/delta", l.root());
        assertEquals("trade", l.entry("trading.trade_endur").orElseThrow().kind());
        assertEquals(List.of("TRADE_ID", "PRODUCT_TYPE"), l.entry("trading.trade_endur").orElseThrow().promote());
        assertEquals(List.of("PNL"), l.require("risk", "pnl-vector").vectors());
        assertEquals("eu-west-2", l.storage().get("region"));
        String endur = Files.readString(conns.resolve("trade-endur.yaml"));
        assertTrue(endur.startsWith("# the header stays"), endur);
        assertTrue(endur.contains("lake: delta") && endur.contains("lake-tables: trading.trade_endur") && endur.contains("source-name: trade-endur"), endur);
        assertFalse(endur.contains("layout.trade.columns"), endur);
        assertTrue(Files.readString(conns.resolve("files.yaml")).contains("root: /files"));
        Map<String, String> s = ConnectorLake.expand("trade-endur", "delta", List.of("trade"),
                Map.of("lake", "delta", "lake-tables", "trading.trade_endur", "source-name", "trade-endur"), () -> lakes, x -> { });
        assertEquals("/lake/delta", s.get("root"));
        assertEquals("trade_endur", s.get("table.trade"));
        assertEquals("TRADE_ID,PRODUCT_TYPE", s.get("layout.trade.columns"));
    }

    @Test
    void theCheckComparesTheLakeFileWithTheTableOnDisk() throws Exception {
        Path log = Files.createDirectories(tmp.resolve("lake/trading/trade_endur/_delta_log"));
        Files.writeString(log.resolve("00000000000000000000.json"), "{\"metaData\":{\"partitionColumns\":[\"business_date\"],\"schemaString\":"
                + "\"{\\\"type\\\":\\\"struct\\\",\\\"fields\\\":[{\\\"name\\\":\\\"id\\\"},{\\\"name\\\":\\\"TRADE_ID\\\"}]}\"}}\n");
        Lake l = Lakes.parse("lake: main\nroot: '" + tmp.resolve("lake") + "'\ntables:\n  trading.trade_endur:\n    kind: trade\n"
                + "    partition-by: [PRODUCT_TYPE]\n    promote: [TRADE_ID, PORTFOLIO]\n", "main", ENV);
        List<LakeCheck.Finding> f = LakeCheck.check(Lakes.of(l), Map.of("x", Map.entry("delta", Map.of("lake", "nowhere"))));
        assertTrue(f.stream().anyMatch(x -> x.message().contains("partitioned by [business_date] on disk")), f.toString());
        assertTrue(f.stream().anyMatch(x -> x.message().contains("[PRODUCT_TYPE, PORTFOLIO]") || x.message().contains("PORTFOLIO")), f.toString());
        assertTrue(f.stream().anyMatch(x -> x.message().contains("names lake nowhere")), f.toString());
    }

    @Test
    void aPacksEntryIsCopiedIntoTheLakeFileOnce() throws Exception {
        Path pack = Files.createDirectories(tmp.resolve("packs/demo"));
        Files.writeString(pack.resolve("pack.yaml"), """
                pack: demo
                lake:
                  tables:
                    risk.desk-pnl:
                      promote: [DESK_ID, CURRENCY]
                      id: DESK_PNL_ID
                """);
        Path lakes = Files.createDirectories(tmp.resolve("lakes"));
        Files.writeString(lakes.resolve("main.yaml"), "# header\nlake: main\nroot: /x\ntables:\n  risk.pnl-vector: {}\n");
        LakeTable entry = PackEntry.read(pack, "desk-pnl", null).toTable("desk-pnl");
        assertTrue(LakeFiles.addTable(lakes.resolve("main.yaml"), entry));
        assertFalse(LakeFiles.addTable(lakes.resolve("main.yaml"), entry));
        Lake l = Lakes.read(lakes, ENV).get("main");
        assertEquals("DESK_PNL_ID", l.require("risk", "desk-pnl").id());
        assertTrue(Files.readString(lakes.resolve("main.yaml")).startsWith("# header"));
    }

    @Test
    void theCommandPrintsJsonOrTheRefusal() throws Exception {
        Path lakes = Files.createDirectories(tmp.resolve("lakes"));
        Files.writeString(lakes.resolve("demo.yaml"), "lake: demo\nroot: /x\ntables:\n  trading.trade_endur:\n    kind: trade\n");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        assertEquals(0, LakesCli.run(new String[] {"table", "--dir", lakes.toString(), "--lake", "demo", "--kind", "trade"},
                new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(out.toString(java.nio.charset.StandardCharsets.UTF_8).contains("\"location\" : \"/x/trading/trade_endur\""), out.toString());
        out.reset();
        assertEquals(2, LakesCli.run(new String[] {"table", "--dir", lakes.toString(), "--lake", "nope", "--kind", "trade"},
                new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(out.toString(java.nio.charset.StandardCharsets.UTF_8).contains("no lake named nope"));
    }
}
