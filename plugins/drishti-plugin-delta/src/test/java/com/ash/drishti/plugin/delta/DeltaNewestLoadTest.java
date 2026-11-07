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
package com.ash.drishti.plugin.delta;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.deltalake.write.LakeCli;
import com.ash.drishti.testkit.DatedSourceContract;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Appends (docs/guides/DATA_LAKE_GUIDE.md): an id two loads wrote on one date is read as the newest load's copy (the
 * largest {@code _load_id}, then commit order), by a document fetch and by the bulk vector read; ids only one load wrote are kept.
 */
class DeltaNewestLoadTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 23);

    @TempDir
    Path tmp;

    /** Appends one load's rows ({@code id → PNL}) under {@code loadId}. */
    private void append(Path table, String loadId, Map<String, Double> rows, int n) throws Exception {
        List<String> lines = new ArrayList<>();
        rows.forEach((id, v) -> lines.add("[\"" + id + "\",\"{\\\"VECTOR_ID\\\":\\\"" + id + "\\\",\\\"LOAD\\\":\\\"" + loadId
                + "\\\",\\\"PNL\\\":{\\\"$vector\\\":\\\"PNL\\\"}}\",{\"DESK\":\"D\",\"PNL\":[" + v + "," + v + "],\"_load_id\":\"" + loadId + "\"}]"));
        Path rowsFile = tmp.resolve("rows-" + n + ".rows");
        Files.write(rowsFile, lines, StandardCharsets.UTF_8);
        String m = "{\"table\":\"" + table + "\",\"partitionColumns\":[\"business_date\"],\"columns\":["
                + "{\"name\":\"id\",\"type\":\"string\"},{\"name\":\"doc\",\"type\":\"string\"},{\"name\":\"business_date\",\"type\":\"date\"},"
                + "{\"name\":\"DESK\",\"type\":\"string\"},{\"name\":\"PNL\",\"type\":\"vector\"},{\"name\":\"_load_id\",\"type\":\"string\"}],"
                + "\"promoted\":[\"DESK\",\"PNL\",\"_load_id\"],\"mode\":\"append\",\"batchRows\":100,\"statsColumns\":[\"id\",\"business_date\",\"_load_id\"],"
                + "\"parts\":[{\"file\":\"" + rowsFile + "\",\"values\":{\"business_date\":\"" + DAY + "\"}}]}";
        Path mf = tmp.resolve("manifest-" + n + ".json");
        Files.writeString(mf, m);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = LakeCli.run(new String[] {"lake", "write", "--manifest", mf.toString()}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(code).as(err.toString(StandardCharsets.UTF_8)).isZero();
    }

    @Test
    void theNewestLoadsCopyOfAnIdIsTheOneRead() throws Exception {
        Path root = tmp.resolve("lake");
        Path table = root.resolve("risk/pnl-vector");
        append(table, "20261104T090000.000-1.1", new LinkedHashMap<>(Map.of("A", 1.0, "B", 2.0)), 1);
        append(table, "20261104T100000.000-2.1", new LinkedHashMap<>(Map.of("A", 10.0, "C", 3.0)), 2);
        Map<String, String> s = new HashMap<>(Map.of("root", root.toString(), "domain", "risk", "source-name", "risk-store", "engine", "native",
                "layout.pnl-vector.columns", "DESK", "layout.pnl-vector.vectors", "PNL"));
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(DatedSourceContract.context(s));
        try {
            var a = p.fetch(EntityRef.of("pnl-vector", "A"), AsOf.of(DAY)).orElseThrow().data();
            assertThat(a.get("LOAD").asText()).isEqualTo("20261104T100000.000-2.1");
            Map<String, Double> got = new LinkedHashMap<>();
            p.vectors("pnl-vector", "PNL", List.of(), AsOf.of(DAY), (id, values, len, texts) -> got.put(id, values[0]));
            assertThat(got).containsOnlyKeys("A", "B", "C").containsEntry("A", 10.0).containsEntry("B", 2.0).containsEntry("C", 3.0);
        } finally {
            p.close();
        }
    }
}
