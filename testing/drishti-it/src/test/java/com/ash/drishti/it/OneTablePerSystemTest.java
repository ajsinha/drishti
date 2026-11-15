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

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.source.SourceSystems;
import com.ash.drishti.engine.source.SourcesProperties;
import com.ash.drishti.lakeload.LakeLoad;
import com.ash.drishti.plugin.delta.DeltaSourcePlugin;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One table a trading system, always (the owner, after the daily-loads rehearsal): the committed first day of every source system
 * (data/testdata/trades/&lt;system&gt;/trade_&lt;system&gt;.2026-07-08.jsonl.gz) is loaded through its own module's inbox rule and connector
 * template into its own table, {@code trading.trade_<system>}, and the trade kind (what TRD lists) reads all six as one union with each
 * system's exact count; no shared table is written.
 */
class OneTablePerSystemTest {

    static final LocalDate DAY = LocalDate.of(2026, 7, 8);
    static final List<String> SYSTEMS = List.of("endur", "calypso", "murex", "wss", "swapone", "ici");

    @TempDir
    Path tmp;

    private static Path repo() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("modules/source-swapone"))) {
            p = p.getParent();
        }
        assertThat(p).as("the repository root").isNotNull();
        return p;
    }

    private static void copy(Path from, Path to) throws Exception {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path f : s.toList()) {
                Path t = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(t);
                } else {
                    Files.copy(f, t);
                }
            }
        }
    }

    @Test
    void everySystemLoadsIntoItsOwnTableAndTheTradeKindListsAllSixExactly() throws Exception {
        Path root = repo();
        YAMLMapper yaml = new YAMLMapper();
        Path pack = Files.createDirectories(tmp.resolve("modules/banking/dmod/packs/trading"));
        Files.copy(root.resolve("modules/banking/dmod/packs/trading/pack.yaml"), pack.resolve("pack.yaml"));
        // what the committed test data says each system holds on the day
        Map<String, Long> expected = new TreeMap<>();
        for (JsonNode d : yaml.readTree(root.resolve("data/testdata/testdata.yaml").toFile()).path("datasets")) {
            String folder = d.path("folder").asText();
            if (folder.startsWith("trades/") && SYSTEMS.contains(folder.substring(7))) {
                for (JsonNode day : d.path("days")) {
                    if (day.path("day").asText().equals(DAY.toString())) {
                        expected.put(folder.substring(7).toUpperCase(java.util.Locale.ROOT), day.path("documents").asLong());
                    }
                }
            }
        }
        assertThat(expected).hasSize(6);

        Path lake = tmp.resolve("lake");
        for (String s : SYSTEMS) {                                         // every source module on, as a site runs them
            copy(root.resolve("modules/source-" + s), tmp.resolve("modules/source-" + s));
        }
        com.ash.drishti.api.resolution.ConfigResolution.install(com.ash.drishti.api.resolution.ConfigResolution.offline(
                k -> k.equals("drishti.modules.home") ? tmp.resolve("modules").toString() : k.equals("drishti.packs.site-dir")
                        ? tmp.resolve("config/packs").toString() : k.equals("drishti.data.dir") ? tmp.resolve("data").toString() : null));
        var cfgs = new LinkedHashMap<String, SourcesProperties.ConnectorSettings>();
        List<SourceSystems.System> installed = new ArrayList<>();
        for (String s : SYSTEMS) {
            Path module = tmp.resolve("modules/source-" + s);
            JsonNode rule = yaml.readTree(Files.readString(module.resolve("dataload.yaml"))).path("dataload").path("rules").get(0);
            String connector = rule.path("connector").asText();
            assertThat(connector).isEqualTo("trade-" + s);
            // the load: the day's file through the module's rule and connector
            Path file = root.resolve("data/testdata/trades/" + s + "/trade_" + s + "." + DAY + ".jsonl.gz");
            Path cfg = tmp.resolve("load-" + s + ".yaml");
            // the table as the inbox takes it (LoaderProcess): the connector's, from its module's template
            String table = com.ash.drishti.lakeconfig.PackEntry.read(pack, "trade", connector).table();
            assertThat(table).as(s + ": the table its connector names").isEqualTo("trade_" + s);
            Files.writeString(cfg, "source:\n  files: '" + file + "'\n  as-of: " + DAY + "\n  id: " + rule.path("key").asText() + "\n  pack: '" + pack
                    + "'\n  connector: " + connector + "\ntarget:\n  format: delta\n  lake: '" + lake + "'\n  kind: trade\n  table: " + table + "\n");
            ByteArrayOutputStream said = new ByteArrayOutputStream();
            assertThat(LakeLoad.run(new String[] {"--config", cfg.toString(), "-q"}, new PrintStream(said, true, StandardCharsets.UTF_8)))
                    .as(s + ": " + said.toString(StandardCharsets.UTF_8)).isZero();
            assertThat(lake.resolve("trading/trade_" + s + "/_delta_log")).as(s + " lands in its own table").isDirectory();

            // the connector of the module's template, on the lake the loads wrote
            JsonNode template = yaml.readTree(Files.readString(module.resolve("connectors/trade-" + s + ".yaml")));
            assertThat(template.path("settings").path("lake-tables").asText()).isEqualTo("trading.trade_" + s);
            assertThat(template.path("settings").has("where")).as(s + ": its own table needs no where:").isFalse();
            Map<String, String> settings = new LinkedHashMap<>();
            template.path("settings").fields().forEachRemaining(e -> {
                if (e.getValue().isValueNode()) {
                    settings.put(e.getKey(), e.getValue().asText());
                } else {
                    e.getValue().fields().forEachRemaining(x -> settings.put(e.getKey() + "." + x.getKey(), x.getValue().asText()));
                }
            });
            settings.remove("lake");
            settings.remove("lake-tables");
            settings.put("root", lake.toString());
            settings.put("domain", "trading");
            settings.put("table.trade", "trade_" + s);
            settings.put("engine", "native");
            settings.put("module-dir", module.toString());
            settings.put("id-map-disk.dir", tmp.resolve("cache/idmaps").toString());
            settings.put("doc-disk-cache.dir", tmp.resolve("cache/docs").toString());
            cfgs.put(connector, new SourcesProperties.ConnectorSettings("delta", true, List.of("trade"), settings));
            installed.add(new SourceSystems.System(s.toUpperCase(java.util.Locale.ROOT), "source-" + s, java.util.Set.of("trade"), true));
        }
        try (Stream<Path> tables = Files.list(lake.resolve("trading"))) {
            assertThat(tables.map(p -> p.getFileName().toString()).sorted().toList()).as("six tables, no shared one")
                    .containsExactly("trade_calypso", "trade_endur", "trade_ici", "trade_murex", "trade_swapone", "trade_wss");
        }

        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofSeconds(5), null, cfgs, tmp.toString(), null, null, null);
        SourceRegistry registry = new SourceRegistry(List.of(new DeltaSourcePlugin()), props, new JsonCodec());
        registry.systems().installed(installed);
        SourceRouter router = new SourceRouter(registry, props, Executors.newVirtualThreadPerTaskExecutor());
        router.bind(Map.of("trade", List.copyOf(cfgs.keySet())));
        Map<String, Long> listed = new TreeMap<>();
        router.documents("trade", AsOf.of(DAY), d -> listed.merge(String.valueOf(d.data().unwrap() instanceof Map<?, ?> m ? m.get("SOURCE_SYSTEM") : null),
                1L, Long::sum));
        assertThat(listed).as("TRD: all six systems, each with its exact count").isEqualTo(expected);
        assertThat(router.carried("trade", DAY)).as("every system holds the day").isEmpty();
    }
}
