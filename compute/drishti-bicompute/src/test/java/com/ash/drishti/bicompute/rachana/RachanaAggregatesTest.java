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
package com.ash.drishti.bicompute.rachana;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.kernel.api.pivot.FigureSource;
import com.ash.drishti.common.JsonCodec;
import com.ash.rachana.el.ElCompiler;
import com.ash.rachana.el.ElException;
import com.ash.rachana.el.EvalContext;
import com.ash.rachana.el.Functions;
import com.ash.rachana.el.Values;
import com.ash.rachana.format.Formats;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Rachana's aggregates come from the pivot engine (docs/architecture/ARCHITECTURE.md parts 1 to 3): found by ServiceLoader, the same AggFunctions a pivot
 * cell runs, with the results Rachana's own list forms gave (the assertions moved here from drishti-rachana's ElTest, MaskTest,
 * ControlsTest and MathFunctionsTest), the new aggregates, group's two ways of writing, and measure/cell through the server's figures.
 */
class RachanaAggregatesTest {

    static final ElCompiler EL = com.ash.drishti.rachana.DrishtiRachana.compiler();
    static final Formats F = Formats.load(null, List.of("../../examples/packs/finance/config/formats.yaml"));
    static final DataNode DOC;

    static {
        DataNode plain = new JsonCodec().read("""
                {"legs":[{"leg":1,"rows":[{"a":1},{"a":2.5}]},{"leg":2}],"rows":[{"pv":1},{"pv":2}],
                 "schedule":[{"amount":10},{"amount":20},{"amount":30}],"NUMS":[3, 1.5, "2", 10],
                 "LEGS":[{"LEG_ID":1,"NOTIONAL":242000000,"CURRENCY":"AUD","PRICE":2,"QUANTITY":1,"TRADE_ID":"T1"},
                         {"LEG_ID":2,"NOTIONAL":242000000,"CURRENCY":"AUD","PRICE":4,"QUANTITY":3,"TRADE_ID":"T1"},
                         {"LEG_ID":3,"NOTIONAL":16000000,"CURRENCY":"USD","PRICE":6,"QUANTITY":5,"TRADE_ID":"T2"}],
                 "EVENTS":[{"EVENT_DATE":"2026-10-20","AMOUNT":"-1500.5","LEG":{"ID":1}},{"EVENT_DATE":"2026-10-20","AMOUNT":2000,"LEG":{"ID":2}},
                           {"EVENT_DATE":"2026-11-03","AMOUNT":"","LEG":{"ID":1}},{"EVENT_DATE":"","AMOUNT":7},{"EVENT_DATE":"2027-01-15","AMOUNT":"x"}]}""");
        Map<String, DataNode> fields = new LinkedHashMap<>(((DataNode.Obj) plain).fields());
        fields.put("masked", DataNode.of(List.of(Map.of("pv", 5), Map.of("pv", DataNode.masked()))));
        fields.put("PVS", DataNode.of(List.of(Map.of("PV", 5), Map.of("PV", DataNode.masked()))));
        DOC = new DataNode.Obj(fields);
    }

    /** A number as an exact decimal: level 1.2 sums and averages integers exactly (a BigDecimal), 1.0 gave a Long. */
    static java.math.BigDecimal num(Object v) {
        return new java.math.BigDecimal(String.valueOf(v));
    }

    static Object eval(String src) {
        return Values.simplify(EL.compile(src).eval(EvalContext.of(DOC, F)));
    }

    @AfterEach
    void noFigures() {
        EngineFunctions.figures(null);
    }

    @Test
    void theListFormsRachanaHadKeepTheirResults() {
        assertThat(eval("sum($.legs[0].rows, 'a')")).isEqualTo(3.5);
        assertThat(eval("sum(flatten($.legs[*].rows), 'a')")).isEqualTo(3.5);
        assertThat(num(eval("sum($.schedule[*].amount)"))).isEqualByComparingTo("60");
        assertThat(num(eval("sum($.LEGS, 'NOTIONAL')"))).isEqualByComparingTo("500000000");
        assertThat(num(eval("sum($.nope)"))).isEqualByComparingTo("0");                                     // a sum of nothing is 0, as before
        assertThat(num(eval("max($.NUMS)"))).isEqualByComparingTo("10");
        assertThat(eval("min($.NUMS)")).isEqualTo(1.5);                                   // number-like text counts
        assertThat(num(eval("max(3, -1, 7)"))).isEqualByComparingTo("7");                                  // several arguments: greatest, Rachana's
        assertThat(Values.masked(EL.compile("max($.PVS[*].PV)").eval(EvalContext.of(DOC, F)))).isTrue();
        assertThat(eval("sum($.masked, 'pv')")).isEqualTo(DataNode.MASK);                // never added up
        assertThat(num(eval("sum($.rows, 'pv')"))).isEqualByComparingTo("3");
    }

    @Test
    void groupInTheQuotedFormKeepsItsResults() {
        assertThat(num(eval("size(group($.EVENTS, 'EVENT_DATE', 'AMOUNT'))"))).isEqualByComparingTo("3");          // a blank key is left out
        assertThat(eval("group($.EVENTS, 'EVENT_DATE', 'AMOUNT')[0].value")).isEqualTo(499.5);   // number-like text counts
        assertThat(num(eval("group($.EVENTS, 'EVENT_DATE', 'AMOUNT')[0].count"))).isEqualByComparingTo("2");
        assertThat(num(eval("group($.EVENTS, 'EVENT_DATE', 'AMOUNT')[2].value"))).isEqualByComparingTo("0");      // no number in the group: 0
        assertThat(eval("group($.EVENTS, 'EVENT_DATE', 'AMOUNT', 'month')[1].label")).isEqualTo("2026-11");
        assertThat(num(eval("group($.EVENTS, 'EVENT_DATE', '', 'year')[0].value"))).isEqualByComparingTo("3");    // no value field: the count
        assertThat(eval("group($.EVENTS, 'LEG.ID')[0].label")).isEqualTo("1");                    // a dotted key
        assertThat(eval("group($.nope, 'x')")).isNull();
    }

    @Test
    void groupWithExpressionsUsesAnyAggregateOfTheEngine() {
        assertThat(num(eval("group($.LEGS, @.CURRENCY, sum(@.NOTIONAL))[0].value"))).isEqualByComparingTo("484000000");
        assertThat(eval("group($.LEGS, @.CURRENCY, sum(@.NOTIONAL))[1].label")).isEqualTo("USD");
        assertThat(num(eval("group($.LEGS, @.CURRENCY, avg(@.PRICE))[0].value"))).isEqualByComparingTo("3");
        assertThat(eval("group($.LEGS, @.CURRENCY, wavg(@.PRICE, @.QUANTITY))[0].value")).isEqualTo(3.5);
        assertThat(num(eval("group($.LEGS, @.CURRENCY, percentile(@.PRICE, 0.5))[0].value"))).isEqualByComparingTo("3");
        assertThat(num(eval("group($.LEGS, @.CURRENCY, count(@))[0].value"))).isEqualByComparingTo("2");
        assertThat(num(eval("group($.LEGS, @.CURRENCY)[1].count"))).isEqualByComparingTo("1");
        assertThat(eval("group($.LEGS, upper(@.CURRENCY) + '-' + @.TRADE_ID, max(@.PRICE))[0].label")).isEqualTo("AUD-T1");
    }

    @Test
    void theEnginesOtherAggregatesOverAList() {
        assertThat(num(eval("count($.LEGS)"))).isEqualByComparingTo("3");
        assertThat(num(eval("count($.EVENTS, 'AMOUNT')"))).isEqualByComparingTo("5");                       // "" is a value; missing is not
        assertThat(num(eval("count($.nope)"))).isEqualByComparingTo("0");
        assertThat(num(eval("avg($.LEGS, 'PRICE')"))).isEqualByComparingTo("4");
        assertThat(num(eval("median($.LEGS[*].PRICE)"))).isEqualByComparingTo("4");
        assertThat(num(eval("percentile($.LEGS[*].PRICE, 1)"))).isEqualByComparingTo("6");
        assertThat(num(eval("distinct($.LEGS[*].CURRENCY)"))).isEqualByComparingTo("2");
        assertThat(eval("wavg($.LEGS, 'PRICE', 'QUANTITY')")).isEqualTo((2 * 1 + 4 * 3 + 6 * 5) / 9.0);
        assertThat(eval("wavg($.LEGS[*].PRICE, $.LEGS[*].QUANTITY)")).isEqualTo((2 * 1 + 4 * 3 + 6 * 5) / 9.0);
        assertThat(num(eval("variance($.LEGS[*].PRICE)"))).isEqualByComparingTo("4");
        assertThat(num(eval("stddev($.LEGS[*].PRICE)"))).isEqualByComparingTo("2");
        assertThat(num(eval("sum_once($.LEGS, 'QUANTITY', 'TRADE_ID')"))).isEqualByComparingTo("6");        // T1 once (its first value), T2 once
        assertThat(num(eval("count_once($.LEGS, 'QUANTITY', 'TRADE_ID')"))).isEqualByComparingTo("2");
        assertThat(eval("avg($.nope)")).isNull();                                           // nothing: empty, except sum and count
        assertThatThrownBy(() -> eval("percentile($.LEGS[*].PRICE, 2)")).isInstanceOf(ElException.class)
                .hasMessageContaining("percentile").hasMessageContaining("between 0 and 1");
        assertThatThrownBy(() -> eval("wavg($.LEGS[*].PRICE, $.NUMS)")).isInstanceOf(ElException.class)
                .hasMessageContaining("differ in length");
    }

    @Test
    void measureAndCellAskTheServersFiguresAndAreRefusedWithoutThem() {
        assertThatThrownBy(() -> eval("measure('VAR', 'DESK:DESK-COMM')")).isInstanceOf(ElException.class)
                .hasMessageContaining("measure").hasMessageContaining("server");
        EngineFunctions.figures(new FigureSource() {
            @Override
            public Object measure(String name, String scope, Map<String, String> options) {
                if (!"VAR".equals(name)) {
                    throw new IllegalArgumentException("no measure " + name);
                }
                return scope.equals("DESK:DESK-COMM") && "0.99".equals(options.get("confidence")) ? 1234567.0 : null;
            }

            @Override
            public Object cell(String view, Map<String, String> coordinates) {
                return "desk-pnl".equals(view) && Map.of("DESK", "DESK-COMM", "month", "2026-09").equals(coordinates) ? 42.5 : null;
            }
        });
        assertThat(num(eval("measure('VAR', 'DESK:DESK-COMM', 'confidence=0.99')"))).isEqualByComparingTo("1234567");
        assertThat(eval("measure('VAR', 'DESK:DESK-RATES')")).isNull();
        assertThatThrownBy(() -> eval("measure('NOPE')")).isInstanceOf(ElException.class).hasMessageContaining("no measure NOPE");
        // cell's arguments are read at level 1.0: Rachana's catalogue gives cell no parameters, so its 1.1+ check refuses any by place
        // (a Rachana finding, G4_REHEARSAL Pass 3: level 1.2)
        ElCompiler at10 = com.ash.drishti.rachana.DrishtiRachana.current().language(com.ash.rachana.profile.Grammar.LEVEL_1_0).compiler();
        assertThat(at10.compile("cell('desk-pnl', 'DESK', 'DESK-COMM', 'month', '2026-09')").eval(EvalContext.of(DOC, F))).isEqualTo(42.5);
        assertThatThrownBy(() -> at10.compile("cell('desk-pnl', 'DESK')").eval(EvalContext.of(DOC, F))).isInstanceOf(ElException.class).hasMessageContaining("pairs");
    }

    @Test
    void theCatalogueNamesCoreAsTheSourceOfTheAggregatesAndTheEngineOfItsOwn() {
        Map<String, Functions.Entry> byName = new LinkedHashMap<>();
        com.ash.drishti.rachana.DrishtiRachana.current().functions().catalogue().forEach(e -> byName.put(e.name(), e));
        for (String n : List.of("sum_once", "count_once", "avg_once", "measure", "cell")) {           // the engine's own
            assertThat(byName.get(n)).as(n).isNotNull();
            assertThat(byName.get(n).runs()).as(n).isEqualTo("E");
            assertThat(byName.get(n).source()).as(n).isEqualTo(EngineFunctions.SOURCE);
        }
        for (String n : List.of("sum", "count", "avg", "min", "max", "median", "percentile", "distinct", "wavg", "variance", "stddev",
                "group")) {                                                                           // Core's one aggregate kernel (8.3h)
            assertThat(byName.get(n)).as(n).isNotNull();
            assertThat(byName.get(n).source()).as(n).isEqualTo(com.ash.rachana.agg.CoreAggregates.INSTANCE.source());
        }
        assertThat(byName.get("upper").runs()).isEqualTo("R");
    }
}
