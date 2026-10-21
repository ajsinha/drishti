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

import static com.ash.drishti.testkit.DatedSourceContract.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code table.<kind>}: one source system's trades in their own Iceberg table ({@code trading/trade_calypso}), served as kind {@code trade}, as
 * {@code build_demo_lakes.py --store calypso=iceberg} writes them and its connector file reads them. Two connectors on one domain, each naming
 * its own table, see only their own trades.
 */
class IcebergNamedTableTest {

    private static Path root;

    @BeforeAll
    static void load() throws Exception {
        Path base = Files.createTempDirectory("drishti-iceberg-named");
        root = base.resolve("iceberg");
        StringBuilder sb = new StringBuilder();
        for (String[] t : new String[][] {{"trade_calypso", "CLY-3000001"}, {"trade_calypso", "CLY-3000002"}, {"trade_murex", "MX-20000001"}}) {
            for (String d : new String[] {"2026-09-29", "2026-09-30"}) {
                sb.append("{\"domain\":\"trading\",\"kind\":\"").append(t[0]).append("\",\"id\":\"").append(t[1]).append("\",\"date\":\"").append(d)
                        .append("\",\"doc\":\"{\\\"TRADE_ID\\\":\\\"").append(t[1]).append("\\\"}\",\"columns\":{\"TRADE_ID\":\"").append(t[1]).append("\"}}\n");
            }
        }
        Path lines = base.resolve("rows.jsonl");
        Files.writeString(lines, sb);
        IcebergLoader.load(new String[] {lines.toString(), root.toString(), "--engine", "native", "--spill-dir", base.resolve("spill").toString()});
    }

    private static IcebergSourcePlugin start(String table, boolean kinds) throws Exception {
        Map<String, String> s = new HashMap<>(Map.of("root", root.toString(), "domain", "trading", "source-name", "trade-" + table.substring(6),
                "engine", "native", "table.trade", table, "layout.trade.columns", "TRADE_ID"));
        if (kinds) {
            s.put("kinds", "trade");
        }
        IcebergSourcePlugin p = new IcebergSourcePlugin();
        p.start(context(s));
        return p;
    }

    @Test
    void aKindIsReadFromTheTableItsSettingNames() throws Exception {
        AsOf sept30 = AsOf.of(LocalDate.parse("2026-09-30"));
        for (boolean kinds : new boolean[] {true, false}) {
            IcebergSourcePlugin calypso = start("trade_calypso", kinds);
            IcebergSourcePlugin murex = start("trade_murex", kinds);
            try {
                assertThat(calypso.manifest().kinds()).as("kinds %s", kinds).containsExactly("trade");
                assertThat(calypso.fetch(EntityRef.of("trade", "CLY-3000002"), sept30)).isPresent();
                assertThat(calypso.fetch(EntityRef.of("trade", "MX-20000001"), sept30)).isEmpty();
                assertThat(murex.fetch(EntityRef.of("trade", "MX-20000001"), sept30)).isPresent();
                assertThat(murex.fetch(EntityRef.of("trade", "CLY-3000001"), sept30)).isEmpty();
                assertThat(calypso.health()).startsWith("UP");
                // as the Delta connector: BI scans the promoted columns, and the history cache and BI store key by the data's version
                assertThat(calypso.latestDate("trade")).contains(LocalDate.parse("2026-09-30"));
                assertThat(calypso.earliestDate("trade")).contains(LocalDate.parse("2026-09-29"));
                assertThat(calypso.heldDates("trade").orElseThrow()).hasSize(2);
                assertThat(calypso.dataVersion("trade")).isPresent();
                assertThat(calypso.dataVersion("trade", LocalDate.parse("2026-09-30"))).isPresent().isNotEqualTo(Optional.of("none"));
                assertThat(calypso.dataVersion("trade", LocalDate.parse("2026-01-02"))).contains("none");
                try (com.ash.drishti.api.ArrowScan scan = calypso.scan(new com.ash.drishti.api.ScanRequest("trade", List.of("TRADE_ID"), List.of(),
                        LocalDate.parse("2026-09-30"), LocalDate.parse("2026-09-30"), 1000)).orElseThrow()) {
                    assertThat(scan.next().rows()).isEqualTo(2);
                    assertThat(scan.next()).isNull();
                }
            } finally {
                calypso.close();
                murex.close();
            }
        }
    }

    @Test
    void aTableNameIsNeverAPath() {
        assertThatThrownBy(() -> LakeTables.Named.of(Map.of("table.trade", "../elsewhere"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a path");
    }
}
