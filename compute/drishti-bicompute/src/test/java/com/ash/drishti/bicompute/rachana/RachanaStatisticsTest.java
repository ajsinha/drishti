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
import static org.assertj.core.api.Assertions.within;

import com.ash.drishti.api.DataNode;
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
import org.junit.jupiter.api.Test;

/**
 * The engine's statistics reached from Rachana over a document's lists (FUNCTION_CATALOGUE.md section 4): the same implementation a pivot
 * cell runs, on Excel's documented examples, with nulls, on nothing, masked, and refused on a wrong argument.
 */
class RachanaStatisticsTest {

    static final ElCompiler EL = new ElCompiler();
    static final Formats F = Formats.load(null, List.of("../../examples/packs/finance/config/formats.yaml"));
    static final DataNode DOC;

    static {
        DataNode plain = new JsonCodec().read("""
                {"S":[3,4,5,2,3,4,5,6,4,7],"X":[3,2,4,5,6],"Y":[9,7,12,15,17],"T":[4,5,6,7,2,3,4,5,1,2,3],"G":[4,5,8,7,11,4,3],
                 "SHORT":[1,2,3],
                 "LEGS":[{"CURRENCY":"USD","PRICE":2,"QUANTITY":6,"IDX":null},{"CURRENCY":"EUR","PRICE":3,"QUANTITY":5},
                         {"CURRENCY":"USD","PRICE":9,"QUANTITY":11,"IDX":"SOFR"},{"CURRENCY":"GBP","PRICE":1,"QUANTITY":7},
                         {"CURRENCY":"EUR","PRICE":8,"QUANTITY":5},{"CURRENCY":"USD","PRICE":7,"QUANTITY":4},{"PRICE":5,"QUANTITY":4}]}""");
        Map<String, DataNode> fields = new LinkedHashMap<>(((DataNode.Obj) plain).fields());
        fields.put("MASKED", DataNode.of(List.of(Map.of("P", 5), Map.of("P", DataNode.masked()), Map.of("P", 6))));
        DOC = new DataNode.Obj(fields);
    }

    static Object eval(String src) {
        return Values.simplify(EL.compile(src).eval(EvalContext.of(DOC, F)));
    }

    static double d(String src) {
        return ((Number) eval(src)).doubleValue();
    }

    @Test
    void theStatisticsAreRegisteredFromTheEngine() {
        for (String n : List.of("nmiss", "range", "mode", "iqr", "stderr", "cv", "skewness", "kurtosis", "geomean", "harmean", "trimmean",
                "corr", "covar", "slope", "intercept", "rsq", "kth_largest", "kth_smallest", "string_agg")) {
            Functions.Entry e = Functions.catalogue().stream().filter(x -> x.name().equals(n)).findFirst().orElseThrow();
            assertThat(e.source()).as(n).isEqualTo(EngineFunctions.SOURCE);
            assertThat(e.runs()).as(n).isEqualTo("E");
            assertThat(e.form()).as(n).isIn("mergeable", "holistic");
        }
    }

    @Test
    void excelsExamplesOverAList() {
        assertThat(d("skewness($.S)")).isCloseTo(0.359543071, within(1e-9));
        assertThat(d("kurtosis($.S[*])")).isCloseTo(-0.151799637, within(1e-9));
        assertThat(d("corr($.X, $.Y)")).isCloseTo(0.997054486, within(1e-9));
        assertThat(d("slope($.LEGS, 'PRICE', 'QUANTITY')")).isCloseTo(0.305555556, within(1e-9));       // y = PRICE, x = QUANTITY
        assertThat(d("rsq($.LEGS, 'PRICE', 'QUANTITY')")).isCloseTo(0.057950192, within(1e-9));
        assertThat(d("trimmean($.T, 0.2)")).isCloseTo(3.777777778, within(1e-9));
        assertThat(d("geomean($.G)")).isCloseTo(5.476986970, within(1e-9));
        assertThat(d("harmean($.G)")).isCloseTo(5.028375962, within(1e-9));
        assertThat(d("kth_largest($.S, 3)")).isEqualTo(5.0);
        assertThat(d("kth_smallest($.S, 4)")).isEqualTo(4.0);
        assertThat(d("range($.LEGS, 'PRICE')")).isEqualTo(8.0);
        assertThat(d("iqr($.LEGS[*].PRICE)")).isEqualTo(5.0);
        assertThat(d("stderr($.S)")).isCloseTo(0.472581563, within(1e-9));
        assertThat(d("cv($.S)")).isCloseTo(0.347542818, within(1e-9));
        assertThat(d("covar($.X, $.Y)")).isCloseTo(6.5, within(1e-9));
        assertThat(d("intercept($.Y, $.X)")).isCloseTo(1.6, within(1e-9));
    }

    @Test
    void namesNullsAndText() {
        assertThat(eval("mode($.LEGS, 'CURRENCY')")).isEqualTo("USD");
        assertThat(eval("mode($.S)")).isEqualTo(4L);                                                      // a number, normalised
        assertThat(eval("string_agg($.LEGS[*].CURRENCY, ', ', true)")).isEqualTo("EUR, GBP, USD");
        assertThat(eval("string_agg($.LEGS, 'CURRENCY', '/')")).isEqualTo("EUR/EUR/GBP/USD/USD/USD");
        assertThat(eval("nmiss($.LEGS, 'IDX')")).isEqualTo(6L);                                         // null and absent both count
        assertThat(eval("nmiss($.LEGS, 'CURRENCY')")).isEqualTo(1L);
    }

    @Test
    void nothingAndTooLittle() {
        assertThat(eval("nmiss($.NOPE)")).isEqualTo(0L);
        assertThat(eval("skewness($.NOPE)")).isNull();
        assertThat(eval("string_agg($.NOPE, ', ')")).isNull();
        assertThat(eval("kurtosis($.SHORT)")).isNull();
        assertThat(eval("kth_largest($.SHORT, 4)")).isNull();
        assertThat(eval("mode($.SHORT)")).isNull();
    }

    @Test
    void aMaskedValueMasksTheResult() {
        assertThat(eval("range($.MASKED, 'P')")).isEqualTo(DataNode.MASK);
        assertThat(eval("string_agg($.MASKED, 'P', ', ')")).isEqualTo(DataNode.MASK);
    }

    @Test
    void aWrongArgumentIsRefusedInWords() {
        assertThatThrownBy(() -> eval("kth_largest($.S, 0)")).isInstanceOf(ElException.class).hasMessageContaining("a whole k");
        assertThatThrownBy(() -> eval("trimmean($.S, 1.5)")).isInstanceOf(ElException.class).hasMessageContaining("trimmean");
        assertThatThrownBy(() -> eval("corr($.X, $.SHORT)")).isInstanceOf(ElException.class).hasMessageContaining("differ in length");
        assertThatThrownBy(() -> eval("corr($.X)")).isInstanceOf(ElException.class);
        assertThatThrownBy(() -> eval("string_agg($.S)")).isInstanceOf(ElException.class).hasMessageContaining("2-4 argument");
        assertThatThrownBy(() -> eval("string_agg($.S, true)")).isInstanceOf(ElException.class).hasMessageContaining("separator");
    }
}
