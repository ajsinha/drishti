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
package com.ash.drishti.server.tables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The editors' text work without a server: YAML lines, the canonical YAML of a document, the Text toggle's statement, "adds up?". */
class EditorTextTest {

    static final String TABLE = """
            # header kept
            rachana: 3
            table: mine.view
            from: { kind: trade }
            grain: $.LEGS[*]
            columns:
              ID:   { path: $.tradeId, type: string, key: true }
              NOTE: { expr: "concat(@.CCY, ', ', 'x')", type: string, format: plain }
            tests:
              - { unique: ID }
              - { not-null: NOTE }
            """;

    @Test
    void eachKeyHasItsLineAndAProblemPathFindsTheNearestParent() {
        Map<String, Integer> lines = EditorYaml.lines(TABLE);
        assertThat(lines).containsEntry("table", 3).containsEntry("from.kind", 4).containsEntry("columns.ID", 7).containsEntry("columns.NOTE.expr", 8)
                .containsEntry("tests[1]", 11).containsEntry("tests[1].not-null", 11);
        assertThat(EditorYaml.lineOf(lines, "columns.ID.masked")).isEqualTo(7);
        assertThat(EditorYaml.lineOf(lines, "nowhere")).isZero();
        EditorYaml.Read bad = EditorYaml.read("rachana: 3\ncolumns: [\n");
        assertThat(bad.ok()).isFalse();
        assertThat(bad.errorLine()).isPositive();
        assertThat(EditorYaml.header(TABLE)).containsExactly("# header kept");
    }

    @Test
    void aDocumentIsWrittenInTheLayersFormWithItsHeader() {
        Map<String, Object> doc = EditorYaml.read(TABLE).doc();
        String yaml = EditorYaml.write(EditorYaml.TABLE, doc, List.of("# header kept"));
        assertThat(yaml).startsWith("# header kept\nrachana: 3\ntable: mine.view\n").contains("NOTE:").contains("materialize: none");
        String plain = EditorYaml.write(EditorYaml.TABLE, Map.of("rachana", 3, "columns", Map.of()), List.of());
        assertThat(plain).contains("rachana: 3");
    }

    @Test
    void theStatementPrintsAndReadsBackWithoutLoss() {
        Map<String, Object> doc = EditorYaml.read(TABLE).doc();
        String st = TableStatement.print(doc);
        assertThat(st).isEqualTo("table mine.view from trade\ngrain $.LEGS[*]\nselect $.tradeId as ID: string key,\n"
                + "       concat(@.CCY, ', ', 'x') as NOTE: string\n");
        Map<String, Object> back = TableStatement.read(st + "where $.STATUS != 'CANCELLED'\n", doc);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> cols = (Map<String, Map<String, Object>>) back.get("columns");
        assertThat(cols.get("NOTE")).containsEntry("expr", "concat(@.CCY, ', ', 'x')").containsEntry("format", "plain");
        assertThat(cols.get("ID")).containsEntry("path", "$.tradeId").containsEntry("key", true);
        assertThat(back.get("filter")).isEqualTo("$.STATUS != 'CANCELLED'");
        assertThat(back.get("tests")).isEqualTo(doc.get("tests"));
        Map<String, Object> bare = TableStatement.read("table mine.x from table mine.view\nselect $.A, @.B as BEE\n", Map.of());
        assertThat(bare.get("from")).isEqualTo(Map.of("table", "mine.view"));
        assertThat(new java.util.ArrayList<Object>(((Map<?, ?>) bare.get("columns")).keySet())).containsExactly("A", "BEE");
        assertThatThrownBy(() -> TableStatement.read("table mine.x from trade\nselect $.A + 1\n", Map.of())).hasMessageContaining("name the computed column");
        assertThatThrownBy(() -> TableStatement.read("select $.A from trade table x", Map.of())).hasMessageContaining("order");
        assertThatThrownBy(() -> TableStatement.read("table mine.x from trade\nselect $.A as A: money\n", Map.of())).isInstanceOf(TableStatement.Refused.class)
                .extracting(e -> ((TableStatement.Refused) e).line()).isEqualTo(2);
        assertThatThrownBy(() -> TableStatement.print(Map.of("table", "x.y", "from", Map.of("query", "select 1"))))
                .hasMessageContaining("SQL");
    }

    @Test
    void addsUpIsTheEnginesAnswer() {
        Map<String, String> m = Map.of("MTM", "sum(t.MTM)", "N", "count(t)", "Avg", "avg(t.MTM)", "Per", "measure('MTM') / measure('N')",
                "Net", "sum(t.MTM) - sum(t.FEES)", "Thousands", "sum(t.MTM) / 1000", "Dist", "count_distinct(t.DESK)");
        assertThat(MeasureFacts.addsUp(m.get("MTM"), m).adds()).isTrue();
        assertThat(MeasureFacts.addsUp(m.get("N"), m).adds()).isTrue();
        assertThat(MeasureFacts.addsUp(m.get("Net"), m).adds()).isTrue();
        assertThat(MeasureFacts.addsUp(m.get("Thousands"), m).adds()).isTrue();
        assertThat(MeasureFacts.addsUp(m.get("Avg"), m).adds()).isFalse();
        assertThat(MeasureFacts.addsUp(m.get("Per"), m).word()).isEqualTo("ratio");
        assertThat(MeasureFacts.addsUp(m.get("Dist"), m).adds()).isFalse();
        assertThat(MeasureFacts.addsUp("sum(", m).word()).isEqualTo("unknown");
        assertThat(MeasureFacts.explain("sum(t.MTM, where = t.DESK == 'FX')")).contains("sum").contains("t.MTM").contains("t.DESK");
    }

    @Test
    void notebookCellsBecomeABuildFunction() {
        Map<String, Object> got = PythonDrafts.fromNotebook(List.of("import numpy as np\nx = drishti.table('risk.pnl')", "", "y = x.groupby('DESK').sum()\ny"));
        assertThat(got.get("source")).isEqualTo("import numpy as np\n\ndef build(inputs, params):\n    # cell 1\n    x = inputs[\"pnl\"]\n"
                + "    # cell 3\n    y = x.groupby('DESK').sum()\n    return y\n");
        assertThat(got.get("inputs")).isEqualTo(Map.of("pnl", "risk.pnl"));
        assertThat(PythonDrafts.itemName("market.fx_daily")).isEqualTo("market-fx_daily");
        String manifest = PythonDrafts.manifest(Map.of("name", "market.fx_daily", "source", "def build(inputs, params):\n    return None\n",
                "inputs", List.of("risk.pnl")));
        assertThat(manifest).contains("interface: transform").contains("entry: build").contains("target: server");
        assertThat(PythonDrafts.named(manifest, "market.fx_daily")).isTrue();
        assertThat(PythonDrafts.templates()).extracting(t -> t.get("id")).containsExactly("join-derive", "resample", "group-stats", "approved-feed");
    }
}
