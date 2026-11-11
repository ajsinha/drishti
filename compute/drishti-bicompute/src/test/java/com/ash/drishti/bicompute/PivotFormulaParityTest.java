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
package com.ash.drishti.bicompute;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.kernel.api.pivot.PivotView;
import com.ash.drishti.bicompute.formula.FormulaFunction;
import com.ash.drishti.kernel.api.pivot.Batch;
import com.ash.drishti.kernel.api.pivot.Viewer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The pivot formula parity proof (ARCHITECTURE.md section 26.5): every formula of {@code formula-parity/corpus.yaml} (each shipped and test
 * formula, every construct of the grammar) gives the answer recorded before formulas were read by Rachana (answers.json), on the SQL path
 * (the tabular provider's {@code GROUPING SETS}: every cell of regions by desks and their totals) and on the Java path (the formula over the
 * fixture's rows), value for value. A formula the saved-formula migration rewrites is asked in its new text ({@code rachana}). Recording:
 * {@code -Ddrishti.record.formulas=<folder>} writes answers.json there (done once, on the old parser).
 */
class PivotFormulaParityTest {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final List<String> TEXT = List.of("REGION", "DESK", "CCY", "TRADE_DATE", "CPTY");
    private static final List<String> NUMBER = List.of("NOTIONAL", "PNL", "SALARY", "RATE_PCT");

    /** One formula of the corpus: as written, and as the migration writes it (null: unchanged). */
    record Entry(String formula, String rachana) {
        String asked() {
            return rachana == null ? formula : rachana;
        }
    }

    @SuppressWarnings("unchecked")
    static List<Entry> corpus() throws IOException {
        try (InputStream in = PivotFormulaParityTest.class.getResourceAsStream("/formula-parity/corpus.yaml")) {
            Map<String, Object> m = YAML.readValue(in, Map.class);
            List<Entry> out = new ArrayList<>();
            for (Object o : (List<Object>) m.get("formulas")) {
                Map<String, Object> e = (Map<String, Object>) o;
                out.add(new Entry(String.valueOf(e.get("formula")), e.get("rachana") == null ? null : String.valueOf(e.get("rachana"))));
            }
            return out;
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyFormulaGivesTheRecordedAnswerCellForCell() throws IOException {
        List<Entry> corpus = corpus();
        Map<String, Object> answers = new TreeMap<>();
        try (PivotFixture fx = new PivotFixture(3_000, StateCache.none(), PivotCore.Settings.defaults())) {
            Map<String, Object[]> columns = columns(fx);
            int rows = ((Object[]) columns.get("REGION")).length;
            for (Entry e : corpus) {
                Map<String, Object> answer = new LinkedHashMap<>();
                Map<String, Object> view = Map.of("source", "trades", "rows", List.of("REGION"), "columns", List.of("DESK"), "values",
                        List.of(Map.of("measure", e.asked(), "as", "F")));
                Map<String, Object> sql = new TreeMap<>();
                try {
                    Map<String, Object> a = fx.core.query(Viewer.of("rita"), PivotView.parse(view));
                    ((Map<String, Map<String, Object>>) a.get("cells")).forEach((key, cell) -> sql.put(key, ((Map<String, Object>) cell.get("values")).get("F")));
                } catch (RuntimeException x) {
                    sql.put("refused", true);                                // refused by the view as it was: refused the same way after
                }
                answer.put("sql", sql);
                try {
                    FormulaFunction f = FormulaFunction.of(e.asked());
                    Object[] in = new Object[f.fields().size()];
                    for (int i = 0; i < in.length; i++) {
                        in[i] = column(columns, f.fields().get(i), rows);
                    }
                    answer.put("java", f.finish(f.add(f.start(), new Batch(rows, in))));
                } catch (RuntimeException x) {
                    answer.put("java", "refused");
                }
                answers.put(e.formula(), JSON.readValue(JSON.writeValueAsString(answer), Map.class));
            }
        }
        String record = System.getProperty("drishti.record.formulas");
        if (record != null && !record.isBlank()) {
            Files.writeString(Path.of(record).resolve("answers.json"), JSON.writeValueAsString(answers) + "\n");
            return;
        }
        Map<String, Object> recorded;
        try (InputStream in = PivotFormulaParityTest.class.getResourceAsStream("/formula-parity/answers.json")) {
            recorded = JSON.readValue(in, Map.class);
        }
        assertThat(answers.keySet()).as("every formula of the corpus was recorded").isEqualTo(recorded.keySet());
        int cells = 0;
        for (Entry e : corpus) {
            Map<String, Object> got = (Map<String, Object>) answers.get(e.formula());
            Map<String, Object> want = (Map<String, Object>) recorded.get(e.formula());
            assertThat(got).as(e.formula() + (e.rachana() == null ? "" : " asked as " + e.rachana())).isEqualTo(want);
            cells += ((Map<String, Object>) want.get("sql")).size() + 1;
        }
        assertThat(cells).as("answers compared").isGreaterThan(corpus.size() * 10);
    }

    /** The fixture's rows, column by column, in the order DuckDB holds them. */
    private static Map<String, Object[]> columns(PivotFixture fx) {
        List<String> all = new ArrayList<>(TEXT);
        all.addAll(NUMBER);
        return fx.exec.query("SELECT " + String.join(", ", all) + " FROM trades ORDER BY rowid", List.of(), rs -> {
            Map<String, List<Object>> cols = new LinkedHashMap<>();
            all.forEach(c -> cols.put(c, new ArrayList<>()));
            while (rs.next()) {
                for (String c : all) {
                    cols.get(c).add(rs.getObject(c));
                }
            }
            Map<String, Object[]> out = new LinkedHashMap<>();
            cols.forEach((k, v) -> out.put(k, v.toArray()));
            return out;
        });
    }

    /** A column as the Java path reads it: text as text, numbers as doubles with an absent value as NaN. */
    private static Object column(Map<String, Object[]> columns, String name, int rows) {
        Object[] v = columns.get(name);
        if (v == null) {
            throw new IllegalArgumentException("no column " + name + " in the fixture");
        }
        if (TEXT.contains(name)) {
            String[] s = new String[rows];
            for (int i = 0; i < rows; i++) {
                s[i] = v[i] == null ? null : String.valueOf(v[i]);
            }
            return s;
        }
        double[] d = new double[rows];
        for (int i = 0; i < rows; i++) {
            d[i] = v[i] == null ? Double.NaN : ((Number) v[i]).doubleValue();
        }
        return d;
    }
}
