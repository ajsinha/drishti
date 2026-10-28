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
package com.ash.drishti.server.exposure;

import com.ash.drishti.deltalake.write.LakeCli;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ExposureLakeFixture}'s lake (its rows unchanged) with the exposure paths written again carrying each trade's {@code LIMIT_LETTER}
 * (2.9.5): A and C under LL-A (two netting sets, one desk), B under LL-B (the other desk; NS-X is split between the letters).
 */
final class ExposureHierarchyFixture {

    static final LocalDate DAY = ExposureLakeFixture.DAY;
    static final Map<String, String> LETTER = Map.of("A", "LL-A", "B", "LL-B", "C", "LL-A");
    private static final ObjectMapper JSON = new ObjectMapper();

    static Path write(Path root) throws Exception {
        ExposureLakeFixture.write(root);
        Path old = root.resolve("risk").resolve("exposure-path");               // written anew with the letter column (a new table)
        try (var walk = Files.walk(old)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        String run = "XR-" + DAY + "-01";
        String batch = "XB-" + DAY + "-01";
        double m = ExposureLakeFixture.M;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            int i = k / 2;
            boolean primary = k % 2 == 1;
            rows.add(path(run, batch, "A", "NS-X", "DESK-A", "BOOK-A", k,
                    ExposureLakeFixture.scale(primary ? ExposureLakeFixture.A[i] : ExposureLakeFixture.L[i], m)));
            rows.add(path(run, batch, "B", "NS-X", "DESK-B", "BOOK-B", k,
                    ExposureLakeFixture.scale(primary ? ExposureLakeFixture.B[i] : new double[ExposureLakeFixture.P], m)));
            rows.add(path(run, batch, "C", "NS-Y", "DESK-A", "BOOK-A", k,
                    ExposureLakeFixture.scale(primary ? ExposureLakeFixture.C[i] : new double[ExposureLakeFixture.P], m)));
        }
        table(root, rows, List.of("TRADE_ID", "NETTING_SET", "COUNTERPARTY", "LEGAL_ENTITY", "DESK", "TT_BOOK", "LIMIT_LETTER", "ASSET_CLASS",
                "PRODUCT", "SOURCE_SYSTEM", "MATURITY_DATE", "NOTIONAL", "GRID_INDEX", "GRID_DATE", "RUN_ID", "BATCH_ID", "STATUS"));
        return root;
    }

    private static Map<String, Object> path(String run, String batch, String trade, String set, String desk, String book, int k, double[] mtm) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("PATH_ROW_ID", run + "~" + trade + "~" + k);
        d.put("BUSINESS_DATE", DAY.toString());
        d.put("RUN_ID", run);
        d.put("TRADE_ID", trade);
        d.put("SOURCE_SYSTEM", "TEST");
        d.put("NETTING_SET", set);
        d.put("COUNTERPARTY", "CP-1");
        d.put("LEGAL_ENTITY", "LE-A");
        d.put("DESK", desk);
        d.put("TT_BOOK", book);
        d.put("LIMIT_LETTER", LETTER.get(trade));
        d.put("ASSET_CLASS", trade.equals("C") ? "FX" : "Rates");
        d.put("PRODUCT", "swap");
        d.put("NOTIONAL", 10 * ExposureLakeFixture.M);
        d.put("MATURITY_DATE", "2028-12-31");
        d.put("GRID_INDEX", (double) k);
        d.put("GRID_DATE", ExposureLakeFixture.grid().get(k));
        d.put("STATUS", "ok");
        d.put("BATCH_ID", batch);
        d.put("MTM", mtm);
        return d;
    }

    /** The exposure-path table of the day written again (overwrite-dates), as ExposureLakeFixture writes its tables. */
    private static void table(Path root, List<Map<String, Object>> docs, List<String> promoted) throws Exception {
        Path work = Files.createDirectories(root.resolve("_work"));
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> d : docs) {
            Map<String, Object> doc = new LinkedHashMap<>(d);
            Map<String, Object> cols = new LinkedHashMap<>();
            for (String c : promoted) {
                cols.put(c, d.get(c));
            }
            cols.put("MTM", d.get("MTM"));
            doc.put("MTM", Map.of("$vector", "MTM"));
            lines.add(JSON.writeValueAsString(List.of(String.valueOf(d.get("PATH_ROW_ID")), JSON.writeValueAsString(doc), cols)));
        }
        Path f = work.resolve("exposure-path-letters-" + DAY + ".rows");
        Files.write(f, lines, StandardCharsets.UTF_8);
        List<Map<String, Object>> columns = new ArrayList<>(List.of(Map.of("name", "id", "type", "string"), Map.of("name", "doc", "type", "string"),
                Map.of("name", "business_date", "type", "date")));
        for (String c : promoted) {
            boolean num = docs.stream().allMatch(d -> d.get(c) == null || d.get(c) instanceof Number);
            columns.add(Map.of("name", c, "type", num ? "double" : "string"));
        }
        columns.add(Map.of("name", "MTM", "type", "vector"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("table", root.resolve("risk").resolve("exposure-path").toAbsolutePath().toString());
        m.put("partitionColumns", List.of("business_date"));
        m.put("columns", columns);
        m.put("promoted", promoted);
        m.put("mode", "overwrite-dates");
        m.put("batchRows", 1000);
        m.put("statsColumns", List.of("id", "business_date"));
        m.put("parts", List.of(Map.of("file", f.toString(), "values", Map.of("business_date", DAY.toString()))));
        Path mf = work.resolve("exposure-path-letters.manifest.json");
        Files.writeString(mf, JSON.writeValueAsString(m));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = LakeCli.run(new String[] {"lake", "write", "--manifest", mf.toString()}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        if (code != 0) {
            throw new IllegalStateException("exposure-path: " + err.toString(StandardCharsets.UTF_8));
        }
    }
}
