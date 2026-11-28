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
package com.ash.drishti.plugin.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.plugin.delta.DeltaSourcePlugin;
import com.ash.drishti.plugin.iceberg.VectorLake.Pos;
import com.ash.drishti.testkit.DatedSourceContract;
import com.ash.lake.config.ConnectorLake;
import com.ash.lake.config.Lake;
import com.ash.lake.config.Lakes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A bank's external lake (docs/admin/TABLE_CATALOGUE.md, "Binding a bank's external lake"): tables at folders Drishti does not choose,
 * different names and depths, one Delta and one Iceberg, known to the server only by symbolic name. The lake file binds each name to its uri,
 * format and contract; a connector names the lake and the table, and the Delta and Iceberg plugins read exactly those folders (the same
 * rows from both). Nothing is read from or written under the lake's own root, and a table with no uri is a fallback, said so.
 */
class ExternalLakeTest {

    static final LocalDate D1 = LocalDate.of(2026, 10, 21);
    static final LocalDate D2 = LocalDate.of(2026, 10, 22);

    @TempDir
    Path tmp;

    private static Map<LocalDate, List<Pos>> days() {
        Map<LocalDate, List<Pos>> m = new TreeMap<>();
        m.put(D1, VectorLake.positions(12, 1));
        m.put(D2, VectorLake.positions(15, 2));
        return m;
    }

    private Lakes bank(Path unusedRoot, Path delta, Path iceberg) {
        Lake lake = Lakes.parse("""
                lake: bank
                format: delta
                root: %s
                tables:
                  risk.pnl-murex:
                    kind: pnl-vector
                    uri: %s
                    format: delta
                    contract: pnl-vector@1
                    promote: [DESK, TT_BOOK, QUANTITY]
                    vectors: [PNL]
                  risk.pnl-mart:
                    kind: pnl-vector
                    uri: %s
                    format: iceberg
                    contract: pnl-vector@1
                    promote: [DESK, TT_BOOK, QUANTITY]
                    vectors: [PNL]
                  market.curve: {}
                """.formatted(unusedRoot, delta, iceberg), "bank", k -> null);
        return Lakes.of(lake);
    }

    /** What the server gives a connector's plugin: the connector's own reading settings plus the lake file's say about the table. */
    private Map<String, String> settings(Lakes lakes, String plugin, String table, Path cache) {
        Map<String, String> own = new HashMap<>(Map.of("lake", "bank", "lake-tables", table, "source-name", "bank-" + plugin, "engine", "native",
                "refresh-seconds", "3600", "id-map-disk.dir", cache.resolve("idmaps").toString(), "doc-disk-cache.dir", cache.resolve("docs").toString()));
        Map<String, String> out = new HashMap<>(ConnectorLake.expand("bank-" + plugin, plugin, List.of("pnl-vector"), own, () -> lakes, w -> { }));
        out.remove("lake");
        out.remove("lake-name");
        return out;
    }

    private static Map<String, Object> documents(SourcePlugin p, LocalDate day) throws Exception {
        Map<String, Object> out = new TreeMap<>();
        assertThat(p.documents(VectorLake.KIND, AsOf.of(day), (EntityDocument d) -> out.put(d.ref().id(), d.data().unwrap()))).isTrue();
        return out;
    }

    @Test
    void aLakeAtArbitraryUrisIsReadByTheServerBySymbolicNamesOnly() throws Exception {
        Path estate = tmp.resolve("bank-estate");
        Path deltaTable = estate.resolve("fo-archive").resolve("2019").resolve("mx_trades_v7");                       // three folders deep, its own name
        Path iceTable = estate.resolve("mart").resolve("risk").resolve("deep").resolve("a").resolve("scn_pv_v3");    // five deep
        Path unusedRoot = tmp.resolve("drishti-root-never-used");
        VectorLake.loadDelta(tmp, deltaTable, days());
        VectorLake.loadIcebergAt(tmp, iceTable, "native", days());
        Lakes lakes = bank(unusedRoot, deltaTable, iceTable);

        // the registry: each symbolic name carries its uri, format and contract; the one without a uri is a fallback
        Lake lake = lakes.get("bank");
        assertThat(lake.location(lake.require("risk", "pnl-vector", "pnl-murex"))).isEqualTo(deltaTable.toString());
        assertThat(lake.location(lake.require("risk", "pnl-vector", "pnl-mart"))).isEqualTo(iceTable.toString());
        assertThat(lake.require("risk", "pnl-vector", "pnl-mart").format()).isEqualTo("iceberg");
        assertThat(lake.require("risk", "pnl-vector", "pnl-mart").contract()).isEqualTo("pnl-vector@1");
        Map<String, Object> hows = new TreeMap<>();
        lake.tables().values().forEach(t -> hows.put(t.name(), t.describe().get("how")));
        assertThat(hows).containsEntry("risk.pnl-murex", "configured").containsEntry("risk.pnl-mart", "configured").containsEntry("market.curve", "fallback");

        // the connectors: expanded from the lake file alone, no root, domain or folder written by hand
        Map<String, String> ds = settings(lakes, "delta", "risk.pnl-murex", tmp.resolve("cache-d"));
        Map<String, String> is = settings(lakes, "iceberg", "risk.pnl-mart", tmp.resolve("cache-i"));
        assertThat(ds).containsEntry("root", deltaTable.getParent().getParent().toString()).containsEntry("domain", "2019")
                .containsEntry("table.pnl-vector", "mx_trades_v7");
        assertThat(is).containsEntry("root", iceTable.getParent().getParent().toString()).containsEntry("domain", "a")
                .containsEntry("table.pnl-vector", "scn_pv_v3");

        DeltaSourcePlugin d = new DeltaSourcePlugin();
        d.start(DatedSourceContract.context(ds));
        IcebergSourcePlugin i = new IcebergSourcePlugin();
        i.start(DatedSourceContract.context(is));
        try {
            assertThat(d.inventory(VectorLake.KIND, 10)).containsEntry(D2, 15L).containsEntry(D1, 12L);
            assertThat(i.inventory(VectorLake.KIND, 10)).isEqualTo(d.inventory(VectorLake.KIND, 10));
            for (LocalDate day : List.of(D1, D2)) {
                assertThat(documents(i, day)).as("documents of " + day).isEqualTo(documents(d, day)).hasSize(day.equals(D1) ? 12 : 15);
            }
        } finally {
            d.close();
            i.close();
        }
        assertThat(Files.exists(unusedRoot)).as("nothing under the lake's own root: every table has its uri").isFalse();
    }
}
