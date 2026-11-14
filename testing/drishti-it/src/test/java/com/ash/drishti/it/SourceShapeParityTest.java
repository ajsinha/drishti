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
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.source.SourcesProperties;
import com.ash.drishti.lakeload.LakeLoad;
import com.ash.drishti.plugin.delta.DeltaSourcePlugin;
import com.ash.drishti.plugin.file.FileSourcePlugin;
import com.ash.drishti.plugin.iceberg.IcebergSourcePlugin;
import com.ash.drishti.common.JsonCodec;
import java.io.ByteArrayOutputStream;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code where}, {@code set} and {@code map} on real stores (DATA_LAKE_GUIDE.md, "Several source systems"): the same rows are written to a
 * Delta table, an Iceberg table and a JSON Lines folder, and one connector of each kind, with the same three options, reads the same
 * records: a shared table split by {@code where}, a table of native names mapped by {@code map} with {@code set} stamping the system.
 */
class SourceShapeParityTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 3);
    static final List<String> SHARED = List.of(
            "{\"TRADE_ID\":\"E1\",\"SOURCE_SYSTEM\":\"ENDUR\",\"DESK\":\"RATES-NY\",\"NOTIONAL\":100}",
            "{\"TRADE_ID\":\"E2\",\"SOURCE_SYSTEM\":\"ENDUR\",\"DESK\":\"FX-LDN\",\"NOTIONAL\":200}",
            "{\"TRADE_ID\":\"C1\",\"SOURCE_SYSTEM\":\"CALYPSO\",\"DESK\":\"RATES-NY\",\"NOTIONAL\":300}");
    static final List<String> NATIVE = List.of("{\"TRAN_NUM\":\"S1\",\"PORTFOLIO\":\"FX-LDN\",\"NOTIONAL_AMT\":1500}");

    @TempDir
    Path tmp;

    /** Writes {@code lines} as one table of the store ({@code delta}, {@code iceberg}) or as the day's JSON Lines file ({@code file}). */
    private void write(String store, Path lake, String table, String id, List<String> lines) throws Exception {
        Path drop = Files.createDirectories(tmp.resolve("drop-" + store + "-" + table));
        Path file = drop.resolve("trade." + DAY + ".jsonl");
        Files.write(file, lines);
        if (store.equals("file")) {
            Path day = Files.createDirectories(lake.resolve(table).resolve(DAY.toString()));
            Files.copy(file, day.resolve("trade.jsonl"));
            return;
        }
        Path cfg = tmp.resolve("load-" + store + "-" + table + ".yaml");
        Files.writeString(cfg, "source:\n  files: '" + drop + "/*.jsonl'\n  id: " + id + "\n  promote: [" + id + "]\n"
                + "target:\n  format: " + store + "\n  lake: '" + lake + "'\n  domain: trading\n  kind: trade\n  table: " + table + "\n");
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        int code = LakeLoad.run(new String[] {"--config", cfg.toString(), "-q"}, new PrintStream(said, true, StandardCharsets.UTF_8));
        assertThat(code).as(said.toString(StandardCharsets.UTF_8)).isZero();
    }

    private Map<String, Map<String, Object>> read(String store, Path lake) throws Exception {
        Files.createDirectories(tmp.resolve("mappings"));
        Files.writeString(tmp.resolve("mappings/swapone-trade.yaml"), "format: 1\nfields:\n  TRAN_NUM: TRADE_ID\n  PORTFOLIO: DESK\n"
                + "  NOTIONAL_AMT: { to: NOTIONAL, cast: number }\n");
        String plugin = store;
        Map<String, Map<String, String>> connectors = new LinkedHashMap<>();
        for (String[] c : new String[][] {{"trade-endur", "ENDUR"}, {"trade-calypso", "CALYPSO"}}) {
            connectors.put(c[0], settings(store, lake, c[0], "table.trade", "trades_shared", "where", "SOURCE_SYSTEM = '" + c[1] + "'"));
        }
        connectors.put("trade-swapone", settings(store, lake, "trade-swapone", "table.trade", "trade_native", "set.SOURCE_SYSTEM", "SWAPONE", "map",
                "mappings/swapone-trade.yaml"));
        var cfg = new LinkedHashMap<String, SourcesProperties.ConnectorSettings>();
        connectors.forEach((n, s) -> cfg.put(n, new SourcesProperties.ConnectorSettings(plugin, true, List.of("trade"), s)));
        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofSeconds(5), null, cfg, tmp.toString(), null, null, null, null);
        SourceRegistry registry = new SourceRegistry(List.of(new DeltaSourcePlugin(), new IcebergSourcePlugin(), new FileSourcePlugin()), props,
                new JsonCodec());
        registry.shapeBases(List.of(tmp));
        SourceRouter router = new SourceRouter(registry, props, Executors.newVirtualThreadPerTaskExecutor());
        router.bind(Map.of("trade", List.copyOf(connectors.keySet())));
        Map<String, Map<String, Object>> out = new TreeMap<>();
        router.documents("trade", AsOf.of(DAY), d -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = new TreeMap<>((Map<String, Object>) d.data().unwrap());
            m.keySet().removeIf(k -> k.startsWith("_"));
            m.replaceAll((k, v) -> v instanceof Number n ? (Object) n.doubleValue() : v);
            out.put(d.ref().id(), m);
        });
        return out;
    }

    private Map<String, String> settings(String store, Path lake, String name, String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("root", lake.toString());
        m.put("domain", "trading");
        m.put("source-name", name);
        m.put("union", "true");
        if (!store.equals("file")) {
            m.put("engine", "native");
        }
        m.put("id-map-disk.dir", tmp.resolve("cache-" + store + "/idmaps").toString());
        m.put("doc-disk-cache.dir", tmp.resolve("cache-" + store + "/docs").toString());
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        if (store.equals("file")) {                      // a folder of files holds one table: <root>/<date>/trade.jsonl
            m.put("root", lake.resolve(m.get("table.trade")).toString());
            m.remove("domain");
            m.put("id-field", m.get("table.trade").equals("trade_native") ? "TRAN_NUM" : "TRADE_ID");
        }
        return m;
    }

    @Test
    void whereSetAndMapReadTheSameRowsFromDeltaIcebergAndFiles() throws Exception {
        Map<String, Map<String, Map<String, Object>>> byStore = new LinkedHashMap<>();
        for (String store : List.of("delta", "iceberg")) {
            Path lake = Files.createDirectories(tmp.resolve("lake-" + store));
            write(store, lake, "trades_shared", "TRADE_ID", SHARED);
            write(store, lake, "trade_native", "TRAN_NUM", NATIVE);
            byStore.put(store, read(store, lake));
        }
        Map<String, Map<String, Object>> delta = byStore.get("delta");
        assertThat(delta.keySet()).containsExactly("C1", "E1", "E2", "S1");
        assertThat(delta.get("S1")).containsEntry("TRADE_ID", "S1").containsEntry("DESK", "FX-LDN").containsEntry("NOTIONAL", 1500.0)
                .containsEntry("SOURCE_SYSTEM", "SWAPONE").doesNotContainKeys("TRAN_NUM", "PORTFOLIO", "NOTIONAL_AMT");
        assertThat(delta.get("E1")).containsEntry("SOURCE_SYSTEM", "ENDUR");
        assertThat(byStore.get("iceberg")).as("Iceberg reads the rows Delta does").isEqualTo(delta);
    }
}
