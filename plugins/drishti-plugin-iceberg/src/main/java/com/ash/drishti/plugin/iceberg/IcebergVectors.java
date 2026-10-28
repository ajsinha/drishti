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

import com.ash.drishti.api.VectorSink;
import com.ash.drishti.plugin.iceberg.KindTable.Day;
import com.ash.drishti.plugin.iceberg.KindTable.Layout;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * What a day of an Iceberg table gives in one pass, on top of {@link KindTable#scan} (so both engines read it alike): the rows of a
 * vector column ({@code layout.<kind>.vectors}, a {@code list<double>} such as a trade's 509-value P&amp;L vector, VAR_GUIDE.md section
 * 29.3) with some text columns, one entity's vectors put back into its document in place of their {@code {"$vector": "PNL"}} markers,
 * and every document of the day file by file. The same answers as the Delta connector's: a null element is NaN (never 0), a null
 * vector has length -1, a number in a text column keeps its digits ({@code QUANTITY} 1500.0 reads "1500"). Stateless.
 */
final class IcebergVectors {

    private IcebergVectors() {
    }

    /**
     * Streams the day's rows with the vector {@code column} and the {@code textColumns} (physical names) to {@code sink}, file by file
     * in file order: one buffer, reused for every row, so a date of millions of vectors is never held here.
     */
    static void vectors(KindTable t, Layout l, Day day, String column, List<String> textColumns, VectorSink sink) {
        vectors(t, l, day, column, textColumns, f -> true, sink);
    }

    /** As above, only the files {@code keep} accepts by index (a VaR shard's read skips those holding none of its keys). */
    static void vectors(KindTable t, Layout l, Day day, String column, List<String> textColumns, java.util.function.IntPredicate keep,
            VectorSink sink) {
        List<String> cols = new ArrayList<>(List.of(IcebergLayout.ID, column));
        cols.addAll(textColumns);
        double[][] buf = {new double[512]};
        String[] texts = new String[textColumns.size()];
        for (int f = 0; f < day.files().size(); f++) {
            if (!keep.test(f)) {
                continue;
            }
            t.scan(l, day, f, cols, null, r -> {
                for (int c = 0; c < texts.length; c++) {
                    texts[c] = text(r[c + 2]);
                }
                String id = r[0] == null ? "" : r[0].toString();
                sink.row(id, buf[0], copy(r[1], buf), texts);
            });
        }
    }

    /** One entity's vector columns in one file of the day (its document's markers are put back with them); empty when none. */
    static Map<String, double[]> vectorsOf(KindTable t, Layout l, Day day, int file, String id, List<String> columns) {
        if (file < 0 || file >= day.files().size() || columns.isEmpty()) {
            return Map.of();
        }
        List<String> cols = new ArrayList<>(List.of(IcebergLayout.ID));
        cols.addAll(columns);
        Map<String, double[]> out = new LinkedHashMap<>();
        t.scan(l, day, file, cols, id, r -> {
            if (!out.isEmpty() || !id.equals(String.valueOf(r[0]))) {
                return;                                          // the row group's other rows
            }
            for (int c = 0; c < columns.size(); c++) {
                double[][] one = {new double[0]};
                int len = copy(r[c + 1], one);
                if (len >= 0) {
                    out.put(columns.get(c), Arrays.copyOf(one[0], len));
                }
            }
        });
        return out;
    }

    /** A document whose vector columns were kept out of it ({@code {"$vector": "PNL"}} markers) with the vectors put back. */
    static String withVectors(String json, Map<String, double[]> vectors) {
        String out = json;
        for (Map.Entry<String, double[]> e : vectors.entrySet()) {
            StringBuilder a = new StringBuilder(e.getValue().length * 8).append('[');
            for (int i = 0; i < e.getValue().length; i++) {
                double x = e.getValue()[i];
                if (i > 0) {
                    a.append(',');
                }
                if (Double.isNaN(x) || Double.isInfinite(x)) {
                    a.append("null");
                } else if (x == Math.rint(x) && Math.abs(x) < 1e15) {
                    a.append((long) x);                          // whole currency units stay whole, as the batch wrote them
                } else {
                    a.append(x);
                }
            }
            String array = a.append(']').toString();
            // the marker as the lake tools write it (compact JSON), with or without a space after the colon
            out = out.replace("{\"$vector\":\"" + e.getKey() + "\"}", array).replace("{\"$vector\": \"" + e.getKey() + "\"}", array);
        }
        return out;
    }

    /** Every document of the day, file by file in file order (deletes applied): id and JSON text. */
    static void documents(KindTable t, Layout l, Day day, BiConsumer<String, String> sink) {
        for (int f = 0; f < day.files().size(); f++) {
            t.scan(l, day, f, List.of(IcebergLayout.ID, IcebergLayout.DOC), null, r -> {
                if (r[0] != null && r[1] != null) {
                    sink.accept(r[0].toString(), r[1].toString());
                }
            });
        }
    }

    /**
     * A list cell's values into {@code buf[0]} (grown when short): doubles, or floats widened exactly; a null element is NaN. Returns the
     * length, or -1 for a null cell (an entity without a vector: counted by the caller, never taken for zeros).
     */
    static int copy(Object cell, double[][] buf) {
        if (cell == null) {
            return -1;
        }
        if (!(cell instanceof List<?> list)) {
            throw new IllegalStateException("a vector column holds " + cell.getClass().getSimpleName() + ", not a list of numbers");
        }
        int len = list.size();
        if (buf[0].length < len) {
            buf[0] = new double[Math.max(len, buf[0].length * 2)];
        }
        double[] b = buf[0];
        for (int i = 0; i < len; i++) {
            Object v = list.get(i);
            b[i] = v == null ? Double.NaN : ((Number) v).doubleValue();
        }
        return len;
    }

    /** A cell as text, as the Delta connector gives it: a number keeps its digits (1500, never 1500.0). */
    static String text(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Double d) {
            return com.ash.drishti.api.IdText.of(d);
        }
        if (v instanceof Float f) {
            return com.ash.drishti.api.IdText.of(f);
        }
        if (v instanceof BigDecimal d) {
            return d.stripTrailingZeros().toPlainString();
        }
        return v.toString();
    }
}
