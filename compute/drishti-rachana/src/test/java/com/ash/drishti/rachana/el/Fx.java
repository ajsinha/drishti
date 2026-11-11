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
package com.ash.drishti.rachana.el;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.rachana.format.Formats;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the function-family tests share: a trade written with the demo data's names (and a few fields made for the edges),
 * masked fields beside it, the view's business date 2026-10-16, and short ways to evaluate and to expect a problem.
 */
final class Fx {

    static final ElCompiler EL = new ElCompiler();
    static final Formats F = Formats.load(null, List.of("../../examples/packs/finance/config/formats.yaml"));
    static final DataNode DOC;

    static {
        DataNode plain = new JsonCodec().read("""
                {"TRADE_ID":"MX-20000001","PRODUCT_TYPE":"ComSwap","DESK":"DESK-COMM","TT_BOOK":"BOOK-RATES-3","QUANTITY":242000000,
                 "PRICE":0.040829,"DIRECTION":"SHORT","TRADE_DATE":"2025-11-21","START_DATE":"2025-11-25","MATURITY_DATE":"2032-06-25",
                 "INSTRUMENT":"USD 2026-10 swap","TAGS":"ir,swap,,aud","LEG_ID":"7","STRATEGY":null,"BLANK":"","TEXTNUM":"12.5",
                 "BASIS":"ACT/999","DESKS":["DESK-COMM","DESK-RATES"],
                 "LEGS":[{"LEG_ID":1,"LEG_TYPE":"FIXED","NOTIONAL":242000000,"CURRENCY":"AUD"},
                         {"LEG_ID":2,"LEG_TYPE":"FLOAT","NOTIONAL":242000000,"CURRENCY":"AUD"}],
                 "EVENTS":[{"EVENT_TYPE":"BOOKED","EVENT_DATE":"2025-11-21"},{"EVENT_TYPE":"AMENDED","EVENT_DATE":"2026-03-02"},
                           {"EVENT_TYPE":"CASH_SETTLEMENT","EVENT_DATE":"2026-01-15","AMOUNT":-1500.25}],
                 "NUMS":[3, 1.5, "2", 10],"MIXED":[1, "1", 1.0, "a", "a"],
                 "UNDATED":[{"ID":"a","D":"2026-02-01"},{"ID":"b"},{"ID":"c","D":"2026-01-01"}]}""");
        Map<String, DataNode> fields = new LinkedHashMap<>(((DataNode.Obj) plain).fields());
        fields.put("MTM", DataNode.masked());
        fields.put("MASKED_LIST", DataNode.masked());
        fields.put("PVS", DataNode.of(List.of(Map.of("PV", 5), Map.of("PV", DataNode.masked()))));
        DOC = new DataNode.Obj(fields);
    }

    private Fx() {}

    static EvalContext ctx() {
        return EvalContext.of(DOC, F).withToday(LocalDate.of(2026, 10, 16));
    }

    /** The expression's value, simplified (a list stays a node). */
    static Object eval(String src) {
        return Values.simplify(EL.compile(src).eval(ctx()));
    }

    static boolean masked(String src) {
        return Values.masked(EL.compile(src).eval(ctx()));
    }

    static BigDecimal dec(String text) {
        return new BigDecimal(text);
    }

    /** The elements of a list result, simplified. */
    static List<Object> list(String src) {
        Object v = eval(src);
        assertThat(v).as(src).isInstanceOf(DataNode.Arr.class);
        return ((DataNode.Arr) v).elements().stream().map(Values::simplify).toList();
    }

    /** Evaluating fails with DRS-2102 and words naming the problem. */
    static void evalError(String src, String... words) {
        assertThatThrownBy(() -> eval(src)).as(src).isInstanceOfSatisfying(ElException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.EL_EVAL);
            for (String w : words) {
                assertThat(e.getMessage()).contains(w);
            }
        });
    }

    /** Compiling fails with DRS-2101 and words naming the problem. */
    static void compileError(String src, String... words) {
        assertThatThrownBy(() -> EL.compile(src)).as(src).isInstanceOfSatisfying(ElException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.EL_SYNTAX);
            for (String w : words) {
                assertThat(e.getMessage()).contains(w);
            }
        });
    }
}
