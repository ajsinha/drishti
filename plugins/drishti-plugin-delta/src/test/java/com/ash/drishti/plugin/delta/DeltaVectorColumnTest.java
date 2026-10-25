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
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A vector column (VAR_DATA_MODEL.md section 15): P&amp;L vectors of 509 doubles written as {@code list<double>} by Drishti's lake
 * writer, the document keeping a {@code {"$vector": "PNL"}} marker, read back to the bit in bulk ({@link DeltaSourcePlugin#vectors},
 * streamed to a sink with promoted text columns beside) and in a fetched document (the marker replaced by the array).
 */
class DeltaVectorColumnTest {

    static final int N = 509;
    static final LocalDate DAY = LocalDate.of(2026, 10, 23);

    @TempDir
    Path tmp;

    /** Writes {@code rows} trades' vectors with Drishti's writer; returns each trade's vector. */
    private Map<String, double[]> write(Path table, int rows) throws Exception {
        Random r = new Random(7);
        Map<String, double[]> want = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            String id = "MX-" + (20000000 + i);
            double[] v = new double[N];
            StringBuilder a = new StringBuilder("[");
            for (int j = 0; j < N; j++) {
                v[j] = Math.round(r.nextGaussian() * 50_000) + (j % 7 == 0 ? 0.25 : 0);     // whole units and a few fractions
                a.append(j == 0 ? "" : ",").append(v[j]);
            }
            want.put(id, v);
            String doc = "{\\\"VECTOR_ID\\\":\\\"PV-" + id + "\\\",\\\"PNL\\\":{\\\"$vector\\\":\\\"PNL\\\"},\\\"DESK\\\":\\\"DESK-RATES\\\"}";
            lines.add("[\"" + id + "\",\"" + doc + "\",{\"DESK\":\"DESK-RATES\",\"TT_BOOK\":\"BOOK-RATES-" + (i % 3 + 1) + "\",\"PNL\":" + a + "]}]");
        }
        Path rowsFile = tmp.resolve("v.rows");
        Files.write(rowsFile, lines, StandardCharsets.UTF_8);
        String m = "{\"table\":\"" + table + "\",\"partitionColumns\":[\"business_date\"],\"columns\":["
                + "{\"name\":\"id\",\"type\":\"string\"},{\"name\":\"doc\",\"type\":\"string\"},{\"name\":\"business_date\",\"type\":\"date\"},"
                + "{\"name\":\"DESK\",\"type\":\"string\"},{\"name\":\"TT_BOOK\",\"type\":\"string\"},{\"name\":\"PNL\",\"type\":\"vector\"}],"
                + "\"promoted\":[\"DESK\",\"TT_BOOK\",\"PNL\"],\"mode\":\"overwrite-dates\",\"batchRows\":11,\"statsColumns\":[\"id\",\"business_date\"],"
                + "\"parts\":[{\"file\":\"" + rowsFile + "\",\"values\":{\"business_date\":\"" + DAY + "\"}}]}";
        Path mf = tmp.resolve("manifest.json");
        Files.writeString(mf, m);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = LakeCli.run(new String[] {"lake", "write", "--manifest", mf.toString()}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(code).as(err.toString(StandardCharsets.UTF_8)).isZero();
        return want;
    }

    private DeltaSourcePlugin plugin(Path root) throws Exception {
        Map<String, String> s = new HashMap<>(Map.of("root", root.toString(), "domain", "risk", "source-name", "risk-store", "engine", "native",
                "layout.pnl-vector.columns", "DESK,TT_BOOK", "layout.pnl-vector.vectors", "PNL"));
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    @Test
    void vectorsRoundTripToTheBitInBulkAndInADocument() throws Exception {
        Path root = tmp.resolve("lake");
        Map<String, double[]> want = write(root.resolve("risk/pnl-vector"), 40);
        DeltaSourcePlugin p = plugin(root);
        try {
            assertThat(p.vectorColumns("pnl-vector")).containsExactly("PNL");
            assertThat(p.columnar("pnl-vector")).containsExactlyInAnyOrder("DESK", "TT_BOOK");      // the vector is no scalar column
            Map<String, double[]> got = new LinkedHashMap<>();
            Map<String, String> books = new HashMap<>();
            boolean read = p.vectors("pnl-vector", "PNL", List.of("TT_BOOK"), AsOf.of(DAY), (id, values, len, texts) -> {
                got.put(id, java.util.Arrays.copyOf(values, len));
                books.put(id, texts[0]);
            });
            assertThat(read).isTrue();
            assertThat(got).hasSize(40);
            want.forEach((id, v) -> {
                assertThat(got.get(id)).as(id).hasSize(N);
                for (int j = 0; j < N; j++) {
                    assertThat(Double.doubleToLongBits(got.get(id)[j])).as(id + "[" + j + "]").isEqualTo(Double.doubleToLongBits(v[j]));
                }
            });
            assertThat(books.get("MX-20000004")).isEqualTo("BOOK-RATES-2");

            // a document read alone has its vector back in place of the marker
            var doc = p.fetch(EntityRef.of("pnl-vector", "MX-20000003"), AsOf.of(DAY)).orElseThrow().data();
            assertThat(doc.get("PNL").type()).isEqualTo(com.ash.drishti.api.NodeType.ARRAY);
            assertThat(doc.get("PNL").size()).isEqualTo(N);
            assertThat(doc.get("PNL").get(7).asDouble()).isEqualTo(want.get("MX-20000003")[7]);
            assertThat(doc.get("PNL").get(8).asDouble()).isEqualTo(want.get("MX-20000003")[8]);

            // a column the kind does not keep as a vector is not answered as one
            assertThat(p.vectors("pnl-vector", "PV", List.of(), AsOf.of(DAY), (id, values, len, texts) -> { })).isFalse();
        } finally {
            p.close();
        }
    }
}
