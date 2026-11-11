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
package com.ash.drishti.rachana;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.rachana.model.PivotSpec;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.SutraParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Promoting a user's pivot: the panel's {@code pivot:} is rewritten, everything else of the file stays as written. */
class SutraPivotEditorTest {

    private final SutraPivotEditor editor = new SutraPivotEditor();
    private final SutraParser parser = new SutraParser();

    static final String SRC = """
            # the desk's book view
            rachana: 1
            sutra: book-trades
            version: 2
            match: { kind: book }
            panels:
              - id: trades
                kind: table
                title: Trades
                rows: $.trades
                # pivot by book and currency by default
                pivot:
                  fields: [book, currency, maturityBucket, mtm]   # what users may use
                  rows: [book]

                  values: [{ field: mtm, agg: sum }]
                totalLabel: Total
                columns:
                  - { label: Book, bind: "@.book" }
                  - { label: MTM, bind: "@.mtm", total: true }
              - { id: flows, kind: ladder, rows: $.flows, pivot: true, columns: [{ label: Date, bind: "@.date" }, { label: Amount, bind: "@.amount" }] }
              - id: plain
                kind: table
                rows: $.x
                pivot: true
                columns:
                  - { label: Ccy, bind: "@.ccy" }
            keys: { F9: raw }
            """;

    private PivotSpec arrangement(String panel, Map<String, Object> a) {
        Sutra s = parser.parse(SRC, "t.yaml", "x");
        PivotSpec offered = s.panels().stream().filter(p -> p.id().equals(panel)).findFirst().orElseThrow().pivot().orElseThrow();
        var parsed = PivotSpec.arrangement(a, offered);
        assertThat(parsed.problems()).isEmpty();
        return parsed.spec();
    }

    @Test
    void rewritesTheBlockPivotKeepingFieldsCommentsAndTheRest() {
        PivotSpec a = arrangement("trades", Map.of("rows", List.of("currency"), "columns", List.of("maturityBucket"),
                "values", List.of(Map.of("field", "mtm", "agg", "sum"), Map.of("field", "mtm", "agg", "count", "show", "pctTotal")),
                "filters", List.of(Map.of("field", "book", "values", List.of("BOOK-A", "true"))), "heat", true, "chart", "bar"));
        var edit = editor.apply(SRC, "trades", a);
        assertThat(edit.fromVersion()).isEqualTo(2);
        assertThat(edit.version()).isEqualTo(3);
        assertThat(edit.text()).contains("""
                    # pivot by book and currency by default
                    pivot:
                      fields: [book, currency, maturityBucket, mtm]
                      rows: [currency]
                      columns: [maturityBucket]
                      values: [{ field: mtm, agg: sum }, { field: mtm, agg: count, show: pctTotal }]
                      filters: [{ field: book, values: [BOOK-A, "true"] }]
                      heat: true
                      chart: bar
                    totalLabel: Total
                """).startsWith("# the desk's book view\n").contains("keys: { F9: raw }");
        PivotSpec got = parser.parse(edit.text(), "t.yaml", "x").panels().get(0).pivot().orElseThrow();
        assertThat(got.arrangementMap()).isEqualTo(a.arrangementMap());
        assertThat(edit.changes()).anySatisfy(c -> assertThat(c).isEqualTo("the pivot of 'trades' opens with rows currency (was book)"))
                .anySatisfy(c -> assertThat(c).contains("opens with columns maturityBucket (was none)"))
                .anySatisfy(c -> assertThat(c).contains("is shaded by value"))
                .anySatisfy(c -> assertThat(c).contains("opens with a bar chart"));
    }

    @Test
    void aFlowPanelAndPivotTrueGetAnArrangement() {
        var edit = editor.apply(SRC, "flows", arrangement("flows", Map.of("rows", List.of("date"), "values", List.of(Map.of("field", "amount")))));
        assertThat(edit.text()).contains("- { id: flows, kind: ladder, rows: $.flows, pivot: { rows: [date], values: [{ field: amount, agg: sum }] }, columns:");
        var plain = editor.apply(SRC, "plain", arrangement("plain", Map.of("rows", List.of("ccy"))));
        assertThat(plain.text()).contains("    pivot:\n      rows: [ccy]\n    columns:");
    }

    @Test
    void anEmptyArrangementOfImplicitFieldsIsPivotTrue() {
        var edit = editor.apply(SRC, "plain", arrangement("plain", Map.of()));
        assertThat(edit.text()).contains("    pivot: true\n");
        assertThat(edit.changes()).isEmpty();
    }

    @Test
    void refusesPanelsWithoutAPivot() {
        PivotSpec any = arrangement("plain", Map.of());
        assertThatThrownBy(() -> editor.apply(SRC, "nope", any)).hasMessageContaining("no panel 'nope'");
        String noPivot = SRC.replace("    pivot: true\n    columns:\n      - { label: Ccy", "    columns:\n      - { label: Ccy");
        assertThatThrownBy(() -> editor.apply(noPivot, "plain", any)).hasMessageContaining("does not offer a pivot");
    }

    @Test
    void quotesOnlyWhatMustBeQuoted() {
        assertThat(SutraPivotEditor.flow(List.of("book", "no", "a: b", "BOOK-1", 5L, true))).isEqualTo("[book, \"no\", \"a: b\", BOOK-1, 5, true]");
    }
}
