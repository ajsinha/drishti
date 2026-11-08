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
import com.ash.drishti.bicompute.spi.FigureSource;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.ElException;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Functions;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.format.Formats;
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

    static final ElCompiler EL = new ElCompiler();
    static final Formats F = Formats.load(null, List.of("../examples/packs/finance/config/formats.yaml"));
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
        assertThat(eval("sum($.schedule[*].amount)")).isEqualTo(60L);
        assertThat(eval("sum($.LEGS, 'NOTIONAL')")).isEqualTo(500000000L);
        assertThat(eval("sum($.nope)")).isEqualTo(0L);                                     // a sum of nothing is 0, as before
        assertThat(eval("max($.NUMS)")).isEqualTo(10L);
        assertThat(eval("min($.NUMS)")).isEqualTo(1.5);                                   // number-like text counts
        assertThat(eval("max(3, -1, 7)")).isEqualTo(7L);                                  // several arguments: greatest, Rachana's
        assertThat(Values.masked(EL.compile("max($.PVS[*].PV)").eval(EvalContext.of(DOC, F)))).isTrue();
        assertThat(eval("sum($.masked, 'pv')")).isEqualTo(DataNode.MASK);                // never added up
        assertThat(eval("sum($.rows, 'pv')")).isEqualTo(3L);
    }

    @Test
    void groupInTheQuotedFormKeepsItsResults() {
        assertThat(eval("size(group($.EVENTS, 'EVENT_DATE', 'AMOUNT'))")).isEqualTo(3L);          // a blank key is left out
        assertThat(eval("group($.EVENTS, 'EVENT_DATE', 'AMOUNT')[0].value")).isEqualTo(499.5);   // number-like text counts
        assertThat(eval("group($.EVENTS, 'EVENT_DATE', 'AMOUNT')[0].count")).isEqualTo(2L);
        assertThat(eval("group($.EVENTS, 'EVENT_DATE', 'AMOUNT')[2].value")).isEqualTo(0L);      // no number in the group: 0
        assertThat(eval("group($.EVENTS, 'EVENT_DATE', 'AMOUNT', 'month')[1].label")).isEqualTo("2026-11");
        assertThat(eval("group($.EVENTS, 'EVENT_DATE', '', 'year')[0].value")).isEqualTo(3L);    // no value field: the count
        assertThat(eval("group($.EVENTS, 'LEG.ID')[0].label")).isEqualTo("1");                    // a dotted key
        assertThat(eval("group($.nope, 'x')")).isNull();
    }

    @Test
    void groupWithExpressionsUsesAnyAggregateOfTheEngine() {
        assertThat(eval("group($.LEGS, @.CURRENCY, sum(@.NOTIONAL))[0].value")).isEqualTo(484000000L);
        assertThat(eval("group($.LEGS, @.CURRENCY, sum(@.NOTIONAL))[1].label")).isEqualTo("USD");
        assertThat(eval("group($.LEGS, @.CURRENCY, avg(@.PRICE))[0].value")).isEqualTo(3L);
        assertThat(eval("group($.LEGS, @.CURRENCY, wavg(@.PRICE, @.QUANTITY))[0].value")).isEqualTo(3.5);
        assertThat(eval("group($.LEGS, @.CURRENCY, percentile(@.PRICE, 0.5))[0].value")).isEqualTo(3L);
        assertThat(eval("group($.LEGS, @.CURRENCY, count(@))[0].value")).isEqualTo(2L);
        assertThat(eval("group($.LEGS, @.CURRENCY)[1].count")).isEqualTo(1L);
        assertThat(eval("group($.LEGS, upper(@.CURRENCY) + '-' + @.TRADE_ID, max(@.PRICE))[0].label")).isEqualTo("AUD-T1");
    }

    @Test
    void theEnginesOtherAggregatesOverAList() {
        assertThat(eval("count($.LEGS)")).isEqualTo(3L);
        assertThat(eval("count($.EVENTS, 'AMOUNT')")).isEqualTo(5L);                       // "" is a value; missing is not
        assertThat(eval("count($.nope)")).isEqualTo(0L);
        assertThat(eval("avg($.LEGS, 'PRICE')")).isEqualTo(4L);
        assertThat(eval("median($.LEGS[*].PRICE)")).isEqualTo(4L);
        assertThat(eval("percentile($.LEGS[*].PRICE, 1)")).isEqualTo(6L);
        assertThat(eval("distinct($.LEGS[*].CURRENCY)")).isEqualTo(2L);
        assertThat(eval("wavg($.LEGS, 'PRICE', 'QUANTITY')")).isEqualTo((2 * 1 + 4 * 3 + 6 * 5) / 9.0);
        assertThat(eval("wavg($.LEGS[*].PRICE, $.LEGS[*].QUANTITY)")).isEqualTo((2 * 1 + 4 * 3 + 6 * 5) / 9.0);
        assertThat(eval("variance($.LEGS[*].PRICE)")).isEqualTo(4L);
        assertThat(eval("stddev($.LEGS[*].PRICE)")).isEqualTo(2L);
        assertThat(eval("sum_once($.LEGS, 'QUANTITY', 'TRADE_ID')")).isEqualTo(6L);        // T1 once (its first value), T2 once
        assertThat(eval("count_once($.LEGS, 'QUANTITY', 'TRADE_ID')")).isEqualTo(2L);
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
        assertThat(eval("measure('VAR', 'DESK:DESK-COMM', 'confidence=0.99')")).isEqualTo(1234567L);
        assertThat(eval("measure('VAR', 'DESK:DESK-RATES')")).isNull();
        assertThatThrownBy(() -> eval("measure('NOPE')")).isInstanceOf(ElException.class).hasMessageContaining("no measure NOPE");
        assertThat(eval("cell('desk-pnl', 'DESK', 'DESK-COMM', 'month', '2026-09')")).isEqualTo(42.5);
        assertThatThrownBy(() -> eval("cell('desk-pnl', 'DESK')")).isInstanceOf(ElException.class).hasMessageContaining("pairs");
    }

    @Test
    void theCatalogueNamesTheEngineAsTheSourceOfEveryAggregate() {
        Map<String, Functions.Entry> byName = new LinkedHashMap<>();
        Functions.catalogue().forEach(e -> byName.put(e.name(), e));
        for (String n : List.of("sum", "count", "avg", "min", "max", "median", "percentile", "distinct", "wavg", "variance", "stddev",
                "sum_once", "count_once", "avg_once", "group", "measure", "cell")) {
            assertThat(byName.get(n)).as(n).isNotNull();
            assertThat(byName.get(n).runs()).as(n).isEqualTo("E");
            assertThat(byName.get(n).source()).as(n).isEqualTo(EngineFunctions.SOURCE);
        }
        assertThat(byName.get("upper").runs()).isEqualTo("R");
    }
}
