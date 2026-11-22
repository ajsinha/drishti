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
package com.ash.drishti.banking.official;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.lakehost.LakeRowsLoad;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The intraday PV stream's windows on an Iceberg risk lake (2.9.5): {@link LakeAppends} knows an Iceberg table from a Delta one, and
 * appends a window (a P&amp;L vector as {@code list<double>}) as one commit beside what the table holds, nothing replaced.
 */
class LakeAppendsTest {

    static final LocalDate DAY = LocalDate.of(2026, 9, 30);

    @TempDir
    Path lake;

    private static LakeRowsLoad.Row row(String id, String trade, List<Double> pnl) {
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("TRADE_ID", trade);
        cols.put("QUANTITY", 150.0);
        cols.put("PNL", pnl);
        return new LakeRowsLoad.Row(id, "{\"TRADE_ID\":\"" + trade + "\",\"BUSINESS_DATE\":\"" + DAY + "\",\"PNL\":{\"$vector\":\"PNL\"}}", cols);
    }

    private long append(List<LakeRowsLoad.Row> rows) throws Exception {
        return LakeRowsLoad.load("iceberg", lake.resolve(OfficialSink.FOLDER).resolve("pnl-vector").toString(), OfficialSink.FOLDER, "pnl-vector", DAY, "append", rows,
                List.of("TRADE_ID", "QUANTITY"), List.of("PNL"), Map.of(), Map.of());
    }

    @Test
    void windowsAreAppendedToAnIcebergTableAsCommitsNothingReplaced() throws Exception {
        assertThat(LakeAppends.iceberg(lake, OfficialSink.FOLDER, "pnl-vector")).isFalse();
        long first = append(List.of(row("PV-T1", "T1", List.of(1.5, -2.25))));
        assertThat(LakeAppends.iceberg(lake, OfficialSink.FOLDER, "pnl-vector")).isTrue();
        long second = append(List.of(row("PV-T1-HS-20-B-I1", "T1", List.of(3.0, -4.5)), row("PV-N1-HS-20-B-I1", "N1", List.of(0.0, 1.0))));
        assertThat(second).isGreaterThan(first);
        Map<String, Map<String, Object>> rows = OfficialTables.read("iceberg", lake.toString(), "pnl-vector", Map.of());
        assertThat(rows).containsOnlyKeys("PV-T1", "PV-T1-HS-20-B-I1", "PV-N1-HS-20-B-I1");
        assertThat(rows.get("PV-T1-HS-20-B-I1")).containsEntry("TRADE_ID", "T1");
        Files.createDirectories(lake.resolve("risk/pnl-vector/_delta_log"));
        assertThat(LakeAppends.iceberg(lake, "risk", "pnl-vector")).as("a Delta table").isFalse();
    }
}
