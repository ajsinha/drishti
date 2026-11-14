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
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A source system end to end, on the real files of the module {@code modules/source-swapone} and the trading pack: a mocked
 * {@code swapone-<date>.jsonl.gz} with its {@code .ready} in the inbox is matched by the module's inbox rule, loaded through the module's
 * connector into {@code trading.trades_shared} with {@code SOURCE_SYSTEM = SWAPONE} stamped, and the trade kind (what TRD lists) shows it
 * through the module's field map; switching the system off removes it, on brings it back, without a restart.
 */
class SourceModuleEndToEndTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 3);

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
    void aSwapOneFileInTheInboxIsListedAndTheSwitchTakesItAwayAndBack() throws Exception {
        Path root = repo();
        Path pack = Files.createDirectories(tmp.resolve("config/packs/banking/trading"));
        Files.copy(root.resolve("config/packs/banking/trading/pack.yaml"), pack.resolve("pack.yaml"));
        Path module = tmp.resolve("modules/source-swapone");
        copy(root.resolve("modules/source-swapone"), module);
        YAMLMapper yaml = new YAMLMapper();
        // the inbox: the file and its .ready
        Path inbox = Files.createDirectories(tmp.resolve("data/inbox"));
        Path file = inbox.resolve("swapone-" + DAY + ".jsonl.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(("{\"swap_id\":\"SW1\",\"revision\":1,\"lifecycle\":\"LIVE\",\"swap_family\":\"IRS\",\"pay_receive\":\"PAY\",\"pay_leg\":{\"notional\":1000000,\"fixed_rate_pct\":5.0}}\n"
                    + "{\"swap_id\":\"SW2\",\"revision\":1,\"lifecycle\":\"LIVE\",\"swap_family\":\"OIS\",\"pay_receive\":\"REC\",\"pay_leg\":{\"notional\":2500000}}\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        // no .ready: the module's date-pattern reads the business date from the dashed name

        // the module's inbox rule matches the name
        JsonNode rule = yaml.readTree(Files.readString(module.resolve("dataload.yaml"))).path("dataload").path("rules").get(0);
        String glob = rule.path("match").asText();
        assertThat(file.getFileName().toString()).matches(glob.replace(".", "\\.").replace("*", ".*"));
        assertThat(rule.path("pack").asText()).isEqualTo("trading");
        assertThat(rule.path("kind").asText()).isEqualTo("trade");
        java.util.regex.Matcher dated = java.util.regex.Pattern.compile(rule.path("date-pattern").asText()).matcher(file.getFileName().toString());
        assertThat(dated.find()).as("the rule's date-pattern finds the date in the name").isTrue();
        assertThat(LocalDate.parse(dated.group(1))).isEqualTo(DAY);
        String connector = rule.path("connector").asText();
        assertThat(connector).isEqualTo("trade-swapone");

        // the load, through the module's connector: the shared table, the system stamped
        Path lake = tmp.resolve("lake");
        Path cfg = tmp.resolve("load.yaml");
        Files.writeString(cfg, "source:\n  files: '" + file + "'\n  as-of: " + DAY + "\n  id: " + rule.path("key").asText() + "\n  pack: '" + pack + "'\n  connector: "
                + connector + "\ntarget:\n  format: delta\n  lake: '" + lake + "'\n  kind: trade\n");
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        assertThat(LakeLoad.run(new String[] {"--config", cfg.toString(), "-q"}, new PrintStream(said, true, StandardCharsets.UTF_8)))
                .as(said.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(lake.resolve("trading/trades_shared/_delta_log")).as("the rows land in the shared table").isDirectory();

        // the connector of the module's template, on the lake the load wrote
        JsonNode template = yaml.readTree(Files.readString(module.resolve("connectors/delta.yaml")));
        Map<String, String> settings = new LinkedHashMap<>();
        template.path("settings").fields().forEachRemaining(e -> {
            if (e.getValue().isValueNode()) {
                settings.put(e.getKey(), e.getValue().asText());
            } else {
                e.getValue().fields().forEachRemaining(s -> settings.put(e.getKey() + "." + s.getKey(), s.getValue().asText()));
            }
        });
        settings.remove("lake");
        settings.remove("lake-tables");
        settings.put("root", lake.toString());
        settings.put("domain", "trading");
        settings.put("table.trade", "trades_shared");
        settings.put("engine", "native");
        settings.put("module-dir", module.toString());               // how the server finds the module's map file
        settings.put("id-map-disk.dir", tmp.resolve("cache/idmaps").toString());
        settings.put("doc-disk-cache.dir", tmp.resolve("cache/docs").toString());
        var cfgs = new LinkedHashMap<String, SourcesProperties.ConnectorSettings>();
        cfgs.put(connector, new SourcesProperties.ConnectorSettings("delta", true, List.of("trade"), settings));
        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofSeconds(5), null, cfgs, tmp.toString(), null, null, null, null);
        SourceRegistry registry = new SourceRegistry(List.of(new DeltaSourcePlugin()), props, new JsonCodec());
        registry.systems().installed(List.of(new SourceSystems.System("SWAPONE", "source-swapone", java.util.Set.of("trade"), true)));
        SourceRouter router = new SourceRouter(registry, props, Executors.newVirtualThreadPerTaskExecutor());
        router.bind(Map.of("trade", List.of(connector)));

        Map<String, Map<String, Object>> listed = list(router);
        assertThat(listed.keySet()).as("the trade kind lists the file's trades").containsExactly("SW1", "SW2");
        assertThat(listed.get("SW1")).containsEntry("SOURCE_SYSTEM", "SWAPONE").containsEntry("TRADE_ID", "SW1").containsEntry("PRODUCT_TYPE", "IRS_FIXFLOAT")
                .containsEntry("BUY_SELL_CODE", "B").containsEntry("QUANTITY", 1000000L)
                .containsEntry("TRADE_STATUS", "NEW").containsEntry("PRICE", 0.05);

        // the module off: gone, with no restart; on: back
        assertThat(registry.systems().switchTo("SWAPONE", false)).isTrue();
        assertThat(list(router)).as("source-swapone switched off").isEmpty();
        assertThat(registry.systems().switchTo("SWAPONE", true)).isTrue();
        assertThat(list(router).keySet()).as("source-swapone switched on again").containsExactly("SW1", "SW2");
    }

    private static Map<String, Map<String, Object>> list(SourceRouter router) {
        Map<String, Map<String, Object>> out = new TreeMap<>();
        router.documents("trade", AsOf.of(DAY), d -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = new TreeMap<>((Map<String, Object>) d.data().unwrap());
            out.put(d.ref().id(), m);
        });
        return out;
    }
}
