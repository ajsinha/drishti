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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.NodeType;
import com.ash.drishti.plugin.iceberg.VectorLake.Pos;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Vector columns on Iceberg (VAR_GUIDE.md section 29.3), on both engines: 509-value P&amp;L vectors loaded by {@link IcebergLoader} as
 * {@code list<double>} read back to the bit in bulk ({@link IcebergSourcePlugin#vectors}) and in a fetched document, a missing element
 * kept missing (NaN, never 0), a row without a vector given as length -1, text columns as the Delta connector gives them (QUANTITY, a
 * double, as "1500"); a list that is not numbers is a reject; a relayout keeps the vectors.
 */
class IcebergVectorsTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 23);

    @TempDir
    Path tmp;

    private IcebergSourcePlugin plugin(Path root, String engine) throws Exception {
        IcebergSourcePlugin p = new IcebergSourcePlugin();
        p.start(DatedSourceContract.context(VectorLake.settings(root, engine, tmp.resolve("cache-" + engine))));
        return p;
    }

    /** Every row's vector and texts as the plugin streams them (the sink's arrays are reused, so they are copied). */
    static Map<String, double[]> read(IcebergSourcePlugin p, List<String> texts, Map<String, String[]> textsOut) throws Exception {
        Map<String, double[]> got = new LinkedHashMap<>();
        boolean read = p.vectors(VectorLake.KIND, "PNL", texts, AsOf.of(DAY), (id, values, len, t) -> {
            got.put(id, len < 0 ? null : Arrays.copyOf(values, len));
            textsOut.put(id, t.clone());
        });
        assertThat(read).isTrue();
        return got;
    }

    static void assertBitEqual(List<Pos> want, Map<String, double[]> got) {
        assertThat(got).hasSize(want.size());
        for (Pos p : want) {
            if (p.pnl() == null) {
                assertThat(got.get(p.id())).as(p.id() + " has no vector").isNull();
                continue;
            }
            assertThat(got.get(p.id())).as(p.id()).hasSize(VectorLake.N);
            for (int j = 0; j < VectorLake.N; j++) {
                assertThat(Double.doubleToLongBits(got.get(p.id())[j])).as(p.id() + "[" + j + "]")
                        .isEqualTo(Double.doubleToLongBits(p.pnl()[j]));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"native", "library"})
    void vectorsRoundTripToTheBitWithMissingElementsKept(String engine) throws Exception {
        Path root = tmp.resolve("lake-" + engine);
        List<Pos> want = VectorLake.positions(30, 11);
        VectorLake.loadIceberg(tmp, root, engine, Map.of(DAY, want));
        IcebergSourcePlugin p = plugin(root, engine);
        try {
            assertThat(p.vectorColumns(VectorLake.KIND)).containsExactly("PNL");
            assertThat(p.columnar(VectorLake.KIND)).containsExactlyInAnyOrder("DESK", "TT_BOOK", "QUANTITY");   // no scalar PNL
            Map<String, String[]> texts = new HashMap<>();
            Map<String, double[]> got = read(p, List.of("TT_BOOK", "QUANTITY"), texts);
            assertBitEqual(want, got);
            assertThat(texts.get("POS-00004")).containsExactly("BOOK-2", "1504");      // a number in a text column keeps its digits
            assertThat(got.get("POS-00001")[3]).isNaN();                              // missing, never 0

            // a document read alone has its vector back in place of the marker, a missing element as null
            var doc = p.fetch(EntityRef.of(VectorLake.KIND, "POS-00003"), AsOf.of(DAY)).orElseThrow().data();
            assertThat(doc.get("PNL").type()).isEqualTo(NodeType.ARRAY);
            assertThat(doc.get("PNL").size()).isEqualTo(VectorLake.N);
            assertThat(doc.get("PNL").get(7).asDouble()).isEqualTo(want.get(3).pnl()[7]);
            assertThat(doc.get("PNL").get(3).isNull()).isTrue();
            // the position without a vector keeps its marker
            var none = p.fetch(EntityRef.of(VectorLake.KIND, "POS-00005"), AsOf.of(DAY)).orElseThrow().data();
            assertThat(none.get("PNL").get("$vector").asText()).isEqualTo("PNL");
        } finally {
            p.close();
        }
    }

    @Test
    void notAnswerableAsVectorsIsFalseWithNothingGiven() throws Exception {
        Path root = tmp.resolve("lake");
        VectorLake.loadIceberg(tmp, root, "native", Map.of(DAY, VectorLake.positions(4, 3)));
        IcebergSourcePlugin p = plugin(root, "native");
        try {
            List<String> given = new ArrayList<>();
            // a text column the table does not keep, a column that is no vector, a kind with none
            assertThat(p.vectors(VectorLake.KIND, "PNL", List.of("NOT_KEPT"), AsOf.of(DAY), (id, v, n, t) -> given.add(id))).isFalse();
            assertThat(p.vectors(VectorLake.KIND, "DESK", List.of(), AsOf.of(DAY), (id, v, n, t) -> given.add(id))).isFalse();
            assertThat(p.vectors("other", "PNL", List.of(), AsOf.of(DAY), (id, v, n, t) -> given.add(id))).isFalse();
            assertThat(given).isEmpty();
            // a date the table does not hold: read, nothing given
            assertThat(p.vectors(VectorLake.KIND, "PNL", List.of(), AsOf.of(LocalDate.of(2020, 1, 2)), (id, v, n, t) -> given.add(id))).isTrue();
            assertThat(given).isEmpty();
        } finally {
            p.close();
        }
    }

    /** A list holding text is not a vector: the row is not loaded, the load ends with an error counting it, the other rows load. */
    @Test
    void aListThatIsNotNumbersIsARejectAndTheOtherRowsLoad() throws Exception {
        Path root = tmp.resolve("lake");
        List<Pos> ps = VectorLake.positions(3, 5);
        List<String> lines = new ArrayList<>(List.of(VectorLake.line(ps.get(0), DAY, null), VectorLake.line(ps.get(2), DAY, null)));
        lines.add(VectorLake.line(ps.get(1), DAY, List.of(1.0, "x", 2.0)));
        assertThatThrownBy(() -> VectorLake.loadLines(tmp, root, "native", lines))
                .hasMessageContaining("1 rows not loaded")
                .hasMessageContaining("POS-00001")
                .hasMessageContaining("the vector field 'PNL' is not a list of numbers");
        IcebergSourcePlugin p = plugin(root, "native");
        try {
            Map<String, double[]> got = read(p, List.of(), new HashMap<>());
            assertThat(got.keySet()).containsExactlyInAnyOrder("POS-00000", "POS-00002");
        } finally {
            p.close();
        }
    }

    /** A relayout (maintenance) copies the vector columns as they are: the document holds only the marker, never the values. */
    @ParameterizedTest
    @ValueSource(strings = {"native", "library"})
    void aRelayoutKeepsTheVectors(String engine) throws Exception {
        Path root = tmp.resolve("lake-" + engine);
        List<Pos> want = VectorLake.positions(20, 17);
        VectorLake.loadIceberg(tmp, root, engine, Map.of(DAY, want));
        try (LayoutLake lake = LayoutLake.of(Map.of("root", root.toString(), "domain", "risk", "engine", engine))) {
            LayoutLake.LayoutTable t = lake.load(VectorLake.KIND).orElseThrow();
            assertThat(t.relayout(false, tmp.resolve("spill"), 64L << 20)).as("a day in the layout is not drifted by its vector column").isZero();
            assertThat(t.relayout(true, tmp.resolve("spill"), 64L << 20)).isEqualTo(1);
        }
        IcebergSourcePlugin p = plugin(root, engine);
        try {
            assertBitEqual(want, read(p, List.of("DESK"), new HashMap<>()));
        } finally {
            p.close();
        }
    }
}
