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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Separate loads of several source systems into one shared table never replace each other (DATA_LAKE_GUIDE.md section 16.2): Endur,
 * Calypso and Murex each in its own table, WSS, SwapOne and ICI into one table split by {@code SOURCE_SYSTEM}; each load in replace mode,
 * one connector at a time; the counts per system exact in Delta and Iceberg, a reload of one system replaces only its own rows, a row of
 * another system in a system's file is rejected, and a shared table that cannot be split refuses a replace.
 */
class SharedTableSliceTest {

    @TempDir
    Path tmp;

    static final List<String> OWN = List.of("ENDUR", "CALYPSO", "MUREX");
    static final List<String> SHARED = List.of("WSS", "SWAPONE", "ICI");

    private Path pack(boolean partitioned) throws Exception {
        Path pack = Files.createDirectories(tmp.resolve(partitioned ? "pack-split" : "pack-flat").resolve("trading"));
        StringBuilder y = new StringBuilder("pack: trading\nconnectors:\n");
        for (String s : OWN) {
            y.append("  trade-").append(s.toLowerCase()).append(":\n    plugin: delta\n    kinds: [trade]\n    settings:\n      domain: trading\n")
                    .append("      table.trade: trade_").append(s.toLowerCase()).append("\n      layout.trade.columns: TRADE_ID,SOURCE_SYSTEM\n")
                    .append("      set:\n        SOURCE_SYSTEM: ").append(s).append('\n');
        }
        for (String s : SHARED) {
            y.append("  trade-").append(s.toLowerCase()).append(":\n    plugin: delta\n    kinds: [trade]\n    settings:\n      domain: trading\n")
                    .append("      table.trade: trades_shared\n      layout.trade.columns: TRADE_ID,SOURCE_SYSTEM\n")
                    .append("      set:\n        SOURCE_SYSTEM: ").append(s).append('\n');
        }
        Files.writeString(pack.resolve("pack.yaml"), y.toString());
        return pack;
    }

    /** {@code n} trades of {@code system} for one date, without SOURCE_SYSTEM (the connector stamps it), in their own folder. */
    private Path file(String system, int n, String date, String extraLine) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("in").resolve(system + "-" + n + "-" + date));
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            lines.add("{\"TRADE_ID\":\"" + system + "-" + i + "\",\"NOTIONAL\":" + (i * 100) + "}");
        }
        if (extraLine != null) {
            lines.add(extraLine);
        }
        Files.write(dir.resolve("trade." + date + ".jsonl"), lines);
        return dir;
    }

    private JsonNode load(Path pack, String system, Path dir, String format, Path lake, boolean partitioned) throws Exception {
        String split = format.equals("delta") && partitioned ? "  partition-by: [SOURCE_SYSTEM]\n" : "";
        String yaml = "source:\n  files: '" + dir + "/*.jsonl'\n  id: TRADE_ID\n  pack: '" + pack + "'\n  connector: trade-" + system.toLowerCase() + "\n"
                + "  promote: [TRADE_ID, SOURCE_SYSTEM]\n" + split
                + "target:\n  format: " + format + "\n  lake: '" + lake + "'\n  kind: trade\n  mode: replace\n";
        return LakeLoadTest.load(tmp, yaml);
    }

    private static Map<String, Integer> bySystem(String format, Path lake, String table) throws Exception {
        Map<String, Map<String, Object>> rows = format.equals("delta") ? TableDump.delta(lake.resolve("trading/" + table).toString(), Map.of())
                : TableDump.iceberg(lake.toString(), "trading", table, Map.of());
        Map<String, Integer> out = new TreeMap<>();
        rows.values().forEach(r -> out.merge(String.valueOf(r.get("SOURCE_SYSTEM")), 1, Integer::sum));
        return out;
    }

    @Test
    void sixSystemsLoadedOneByOneKeepEachOthersRowsAndAReloadReplacesOnlyItsOwn() throws Exception {
        Path pack = pack(true);
        Map<String, Integer> sent = Map.of("ENDUR", 41, "CALYPSO", 37, "MUREX", 29, "WSS", 21, "SWAPONE", 24, "ICI", 23);
        for (String format : List.of("delta", "iceberg")) {
            Path lake = tmp.resolve("lake-" + format);
            for (String s : List.of("ENDUR", "CALYPSO", "MUREX", "WSS", "SWAPONE", "ICI")) {
                JsonNode r = load(pack, s, file(s, sent.get(s), "2026-07-08", null), format, lake, true);
                assertThat(r.path("exit").asInt()).as(format + " " + s + ": " + r.path("said").asText() + r.path("error").asText()).isZero();
            }
            for (String s : OWN) {
                assertThat(bySystem(format, lake, "trade_" + s.toLowerCase())).as(format + " " + s).containsExactly(Map.entry(s, sent.get(s)));
            }
            assertThat(bySystem(format, lake, "trades_shared")).as(format + ": the shared table holds every system's rows, exactly")
                    .containsExactlyInAnyOrderEntriesOf(Map.of("WSS", 21, "SWAPONE", 24, "ICI", 23));

            // WSS sent again, smaller: only WSS's rows of the date are replaced; a row of another system in WSS's file is rejected
            JsonNode again = load(pack, "WSS", file("WSS", 15, "2026-07-08", "{\"TRADE_ID\":\"X-1\",\"SOURCE_SYSTEM\":\"ICI\"}"), format, lake, true);
            assertThat(again.path("exit").asInt()).as(again.path("said").asText()).isEqualTo(2);
            assertThat(again.path("rejects").path("byReason").toString()).contains("SOURCE_SYSTEM is ICI, not this load's WSS");
            assertThat(bySystem(format, lake, "trades_shared")).as(format + " after WSS's reload")
                    .containsExactlyInAnyOrderEntriesOf(Map.of("WSS", 15, "SWAPONE", 24, "ICI", 23));
            if (format.equals("delta")) {                // its undo brings WSS's rows before it back, the other systems' rows once
                String id = again.path("loads").fieldNames().next();
                LakeUndo.undo(lake.toString(), id, List.of(), false);
                assertThat(bySystem(format, lake, "trades_shared")).as("after the undo of WSS's reload")
                        .containsExactlyInAnyOrderEntriesOf(Map.of("WSS", 21, "SWAPONE", 24, "ICI", 23));
            }
        }
    }

    @Test
    void aSharedTableThatCannotBeSplitRefusesAReplaceAndWritesNothing() throws Exception {
        Path pack = pack(false);
        Path lake = tmp.resolve("lake-flat");
        JsonNode r = load(pack, "WSS", file("WSS", 5, "2026-07-08", null), "delta", lake, false);
        assertThat(r.path("exit").asInt()).isEqualTo(1);
        assertThat(r.path("said").asText() + r.path("error").asText()).contains("is shared by trade-ici, trade-swapone, trade-wss")
                .contains("partition-by:");
        assertThat(Files.exists(lake.resolve("trading/trades_shared/_delta_log"))).isFalse();
    }
}
