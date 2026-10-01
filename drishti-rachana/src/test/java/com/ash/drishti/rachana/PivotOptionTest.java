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

import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.PivotSpec;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.SutraParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The {@code pivot} option of table and ladder panels: opting in, the fields and arrangement, and DRS-2031. */
class PivotOptionTest {

    private final SutraParser parser = new SutraParser();

    private static String sutra(String panel) {
        return """
                rachana: 1
                sutra: book-trades
                version: 1
                match: { kind: book }
                panels:
                """ + panel.indent(2);
    }

    private Panel panel(String text) {
        Sutra s = parser.parse(sutra(text), "t.sutra.yaml", "x");
        return s.panels().get(0);
    }

    private List<String> problems(String text) {
        try {
            parser.parse(sutra(text), "t.sutra.yaml", "x");
            return List.of();
        } catch (SutraException e) {
            return e.problems().stream().map(p -> p.code() + " " + p.message()).toList();
        }
    }

    static final String COLUMNS = """
              columns:
                - { label: Trade, bind: "@.tradeId" }
                - { label: Currency, bind: "@.currency" }
                - { label: MTM (USD), bind: "@.mtm", fmt: signed0 }
                - { label: Notional in USD, bind: "@.notional * @.fx", fmt: amount0 }
            """;

    @Test
    void noOptionNoPivotAndFalseIsOff() {
        assertThat(panel("- id: trades\n  kind: table\n  rows: $.trades\n" + COLUMNS).pivot()).isEmpty();
        assertThat(panel("- id: trades\n  kind: table\n  rows: $.trades\n  pivot: false\n" + COLUMNS).pivot()).isEmpty();
    }

    @Test
    void trueOffersTheColumnsNamedByTheirPathsOrLabels() {
        PivotSpec p = panel("- id: trades\n  kind: table\n  rows: $.trades\n  pivot: true\n" + COLUMNS).pivot().orElseThrow();
        assertThat(p.names()).containsExactly("tradeId", "currency", "mtm", "notional-in-usd");
        assertThat(p.field("notional-in-usd").bind()).isEqualTo("@.notional * @.fx");
        assertThat(p.field("mtm").label()).isEqualTo("MTM (USD)");
        assertThat(p.field("mtm").fmt()).isEqualTo("signed0");
        assertThat(p.explicit()).isFalse();
        assertThat(p.rows()).isEmpty();
        assertThat(p.totals()).isTrue();
    }

    @Test
    void aMappingListsFieldsAndTheArrangementItOpensWith() {
        Panel panel = panel("""
                - id: trades
                  kind: ladder
                  rows: $.trades
                  pivot:
                    fields: [book, currency, maturityBucket, mtm, { field: year, bind: "@.maturity", label: Maturity }]
                    rows: [book, maturityBucket]
                    columns: [currency]
                    values: [{ field: mtm, agg: sum }, { field: mtm, agg: count, show: pctTotal }]
                    filters: [year, { field: currency, values: [USD, EUR] }, { field: mtm, min: -1000000, max: 5e6 }]
                    heat: true
                    chart: heatmap
                """ + COLUMNS);
        PivotSpec p = panel.pivot().orElseThrow();
        assertThat(p.explicit()).isTrue();
        assertThat(p.names()).containsExactly("book", "currency", "maturityBucket", "mtm", "year");
        assertThat(p.field("book").bind()).isEqualTo("@.book");
        assertThat(p.field("year").label()).isEqualTo("Maturity");
        assertThat(p.rows()).containsExactly("book", "maturityBucket");
        assertThat(p.columns()).containsExactly("currency");
        assertThat(p.values()).containsExactly(new PivotSpec.Value("mtm", "sum", "value"), new PivotSpec.Value("mtm", "count", "pctTotal"));
        assertThat(p.filters()).containsExactly(new PivotSpec.Filter("year", null, null, null),
                new PivotSpec.Filter("currency", List.of("USD", "EUR"), null, null), new PivotSpec.Filter("mtm", null, -1000000L, 5000000L));
        assertThat(p.heat()).isTrue();
        assertThat(p.chart()).isEqualTo("heatmap");
        assertThat(p.toMap()).containsEntry("rows", List.of("book", "maturityBucket")).containsKey("fields");
    }

    @Test
    void mistakesAreDrs2031WithTheirReasons() {
        String head = "- id: trades\n  kind: table\n  rows: $.trades\n";
        assertThat(problems(head + "  pivot: yes please\n" + COLUMNS))
                .anySatisfy(m -> assertThat(m).startsWith("DRS-2031").contains("true, false, or a mapping"));
        assertThat(problems(head + "  pivot: { rows: [desk] }\n" + COLUMNS))
                .anySatisfy(m -> assertThat(m).contains("pivot rows name 'desk', which is not one of its fields (currency, mtm, notional-in-usd, tradeId)"));
        assertThat(problems(head + "  pivot: { fields: [mtm], values: [{ field: mtm, agg: median }] }\n" + COLUMNS))
                .anySatisfy(m -> assertThat(m).contains("'agg' must be one of sum, count, avg, min, max, distinct, not 'median'"));
        assertThat(problems(head + "  pivot: { fields: [mtm], values: [{ field: mtm, show: pctBook }] }\n" + COLUMNS))
                .anySatisfy(m -> assertThat(m).contains("'show' must be one of value, pctRow, pctColumn, pctTotal"));
        assertThat(problems(head + "  pivot: { fields: [a, b], rows: [a], columns: [a] }\n" + COLUMNS))
                .anySatisfy(m -> assertThat(m).contains("'a' is in both rows and columns"));
        assertThat(problems(head + "  pivot: { fields: [a, b, c, d, e], rows: [a, b, c, d, e] }\n" + COLUMNS))
                .anySatisfy(m -> assertThat(m).contains("at most 4 row fields"));
        assertThat(problems(head + "  pivot: { fields: [\"$.book\"] }\n" + COLUMNS))
                .anySatisfy(m -> assertThat(m).contains("is not a field path").contains("bind"));
        assertThat(problems(head + "  pivot: { fields: [a, a] }\n" + COLUMNS)).anySatisfy(m -> assertThat(m).contains("listed twice"));
        assertThat(problems(head + "  pivot: { fields: [a], colour: red }\n" + COLUMNS)).anySatisfy(m -> assertThat(m).contains("unknown key 'colour' in pivot"));
        assertThat(problems(head + "  pivot: { fields: [a], chart: pie }\n" + COLUMNS)).anySatisfy(m -> assertThat(m).contains("'chart' must be one of bar, line, heatmap"));
        assertThat(problems(head + "  pivot: { fields: [a], heat: maybe }\n" + COLUMNS)).anySatisfy(m -> assertThat(m).contains("'heat' is true or false"));
        assertThat(problems(head + "  pivot: { fields: [a], filters: [{ field: a, values: [x], min: 1 }] }\n" + COLUMNS))
                .anySatisfy(m -> assertThat(m).contains("either 'values' or a range"));
        assertThat(problems(head + "  pivot: { fields: [] }\n" + COLUMNS)).anySatisfy(m -> assertThat(m).contains("non-empty list"));
    }

    @Test
    void onlyTablesAndLaddersTakeAPivot() {
        assertThat(problems("- { id: terms, kind: kv, rows: $.terms, pivot: true }"))
                .anySatisfy(m -> assertThat(m).isEqualTo("DRS-2023 option 'pivot' applies only to panels that show a table (table, ladder), not 'kv'"));
        assertThat(problems("- { id: g, kind: pivot, rows: $.p, by: book, across: ccy, pivot: true }"))
                .anySatisfy(m -> assertThat(m).startsWith("DRS-2023"));
    }

    @Test
    void aBrokenFieldExpressionIsCaughtAtLoad() {
        Sutra s = parser.parse(sutra("- id: t\n  kind: table\n  rows: $.t\n  pivot: { fields: [{ field: x, bind: \"@.a +\" }] }\n"), "t.yaml", "x");
        var problems = new SutraExpressions(new com.ash.drishti.rachana.el.ElCompiler()).check(s);
        assertThat(problems).anySatisfy(p -> assertThat(p.code()).isEqualTo("DRS-2101"));
    }

    @Test
    void anArrangementIsReadAgainstTheFieldsOnOffer() {
        PivotSpec offered = panel("- id: trades\n  kind: table\n  rows: $.trades\n  pivot: true\n" + COLUMNS).pivot().orElseThrow();
        var ok = PivotSpec.arrangement(Map.of("rows", List.of("currency"), "values", List.of(Map.of("field", "mtm", "agg", "avg")),
                "chart", "bar"), offered);
        assertThat(ok.problems()).isEmpty();
        assertThat(ok.spec().rows()).containsExactly("currency");
        assertThat(ok.spec().names()).isEqualTo(offered.names());
        var bad = PivotSpec.arrangement(Map.of("rows", List.of("desk"), "fields", List.of("x")), offered);
        assertThat(bad.spec()).isNull();
        assertThat(bad.problems()).anySatisfy(m -> assertThat(m).contains("unknown key 'fields' in a pivot arrangement"))
                .anySatisfy(m -> assertThat(m).contains("'desk'"));
    }

    @Test
    void aKindsSpecReadsDocumentPaths() {
        var parsed = PivotSpec.parse(Map.of("fields", List.of("book", "$.counterparty.name", "mtm"), "rows", List.of("counterparty.name"),
                "values", List.of(Map.of("field", "mtm"))), PivotSpec.Mode.KIND, List.of());
        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.spec().names()).containsExactly("book", "counterparty.name", "mtm");
        assertThat(parsed.spec().field("counterparty.name").bind()).isEqualTo("$.counterparty.name");
        var bound = PivotSpec.parse(Map.of("fields", List.of(Map.of("field", "x", "bind", "$.a + 1"))), PivotSpec.Mode.KIND, List.of());
        assertThat(bound.problems()).anySatisfy(m -> assertThat(m).contains("'bind' is for panels"));
        assertThat(PivotSpec.parse(true, PivotSpec.Mode.KIND, PivotSpec.fromPaths(List.of("$.book", "mtm"))).spec().names())
                .containsExactly("book", "mtm");
    }
}
