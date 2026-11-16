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
 * MATLAB's list analytics provided by the engine (FUNCTION_CATALOGUE.md section 6) and the new aggregates reached over a Rachana list
 * (section 4), on MATLAB's and Excel's documented examples: the same engine functions a pivot cell runs, with missing elements, masks and
 * wrong arguments.
 */
class RachanaListAnalyticsTest {

    static final ElCompiler EL = new ElCompiler();
    static final Formats F = Formats.load(null, List.of("../../examples/packs/finance/config/formats.yaml"));
    static final DataNode DOC;

    static {
        DataNode plain = new JsonCodec().read("""
                {"A":[4,8,6,-1,-2,-3,-1,3,4,5],"OUT":[57,59,60,100,59,58,57,58,300,61,62,60,62,58,57],
                 "C":[1,2,3,4,5],"G":[1,null,3],"M":[8,9,1,10,6,1,3,6,10,2],"B":[2,4,-1,10,6,3,0,-16],"ONE23":[1,2,3],
                 "PFX":[0,1,2,3,4],"PFY":[1,0,3,10,21],"LINE":[1,3,5,7],"L3":[3,5,7],"SAME":[2,2,2],"R":[0.1,0.2,-0.1],
                 "MM":[1,2,3,4,3,2,1,2,3,5,6,1],"TEMPS":[89,88,92,101,94,97,95],"PR":[13,12,11,8,4,3,2,1,1,1],
                 "LEGS":[{"N":3,"R":2},{"N":4,"R":7},{"N":8,"R":6},{"N":6,"R":7},{"N":1,"R":5},{"N":9,"R":3}]}""");
        Map<String, DataNode> fields = new LinkedHashMap<>(((DataNode.Obj) plain).fields());
        fields.put("HIDDEN", new DataNode.Arr(List.of(DataNode.of(1), DataNode.masked(), DataNode.of(3))));
        DOC = new DataNode.Obj(fields);
    }

    static Object eval(String src) {
        return Values.simplify(EL.compile(src).eval(EvalContext.of(DOC, F)));
    }

    static List<Object> list(String src) {
        Object v = eval(src);
        assertThat(v).as(src).isInstanceOf(DataNode.Arr.class);
        return ((DataNode.Arr) v).elements().stream().map(Values::simplify).toList();
    }

    static double[] doubles(String src) {
        return list(src).stream().mapToDouble(o -> ((Number) o).doubleValue()).toArray();
    }

    @Test
    void theyAreTheEnginesListFunctions() {
        for (String n : List.of("cumsum", "cumprod", "cummax", "cummin", "movsum", "movmean", "movstd", "movmedian", "maxk", "mink", "bounds",
                "zscore", "rescale", "isoutlier", "polyfit")) {
            Functions.Entry e = Functions.catalogue().stream().filter(x -> x.name().equals(n)).findFirst().orElseThrow();
            assertThat(e.source()).as(n).isEqualTo(EngineFunctions.SOURCE);
            assertThat(e.family()).as(n).isEqualTo("lists");
            assertThat(e.form()).as(n).isEqualTo("list");
        }
    }

    @Test
    void runningAndMovingFormsMatchMatlab() {
        assertThat(list("cumsum($.C)")).containsExactly(1L, 3L, 6L, 10L, 15L);                         // MATLAB: cumsum(1:5)
        assertThat(list("cumprod($.C)")).containsExactly(1L, 2L, 6L, 24L, 120L);                       // MATLAB: cumprod(1:5)
        assertThat(list("cummax($.M)")).containsExactly(8L, 9L, 9L, 10L, 10L, 10L, 10L, 10L, 10L, 10L);
        assertThat(list("cummin($.M)")).containsExactly(8L, 8L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L);
        assertThat(list("cumsum($.G)")).containsExactly(1L, null, 4L);                                 // a missing place stays empty
        assertThat(doubles("movmean($.A, 3)")).containsExactly(new double[] {6, 6, 4.3333333333, 1, -2, -2, -0.3333333333, 2, 4, 4.5},
                within(1e-9));                                                                          // MATLAB: movmean(A, 3)
        assertThat(list("movsum($.A, 3)")).containsExactly(12L, 18L, 13L, 3L, -6L, -6L, -1L, 6L, 12L, 9L);   // MATLAB: movsum(A, 3)
        assertThat(list("movmedian($.A, 3)")).containsExactly(6L, 6L, 6L, -1L, -2L, -2L, -1L, 3L, 4L, 4.5);  // MATLAB: movmedian(A, 3)
        assertThat(list("movsum($.A, 2, 0)")).containsExactly(4L, 12L, 18L, 13L, 3L, -6L, -6L, -1L, 6L, 12L);   // a trailing window [2 0]
        assertThat(doubles("movstd($.C, 3)")).containsExactly(new double[] {0.7071067812, 1, 1, 1, 0.7071067812}, within(1e-9));
        assertThat(list("movmean($.G, 1)")).containsExactly(1L, null, 3L);
    }

    @Test
    void topBoundsAndScaling() {
        assertThat(list("maxk($.M, 3)")).containsExactly(10L, 10L, 9L);
        assertThat(list("mink($.M, 3)")).containsExactly(1L, 1L, 2L);
        assertThat(list("maxk($.ONE23, 5)")).containsExactly(3L, 2L, 1L);                              // fewer when fewer
        assertThat(list("bounds($.B)")).containsExactly(-16L, 10L);                                    // MATLAB: bounds
        assertThat(list("zscore($.ONE23)")).containsExactly(-1L, 0L, 1L);
        assertThat(eval("zscore($.SAME)")).isNull();                                                   // no spread
        assertThat(list("rescale($.ONE23)")).containsExactly(0L, 0.5, 1L);
        assertThat(list("rescale($.ONE23, -1, 1)")).containsExactly(-1L, 0L, 1L);
        assertThat(list("rescale($.SAME)")).containsExactly(0L, 0L, 0L);
        List<Object> out = list("isoutlier($.OUT)");                                                   // MATLAB's isoutlier example
        for (int i = 0; i < out.size(); i++) {
            assertThat(out.get(i)).as("element " + (i + 1)).isEqualTo(i == 3 || i == 8);
        }
        assertThat(list("isoutlier($.G)")).containsExactly(false, false, false);
    }

    @Test
    void polyfitRecoversAPolynomialAndRefusesInWords() {
        assertThat(doubles("polyfit($.PFX, $.PFY, 2)")).containsExactly(new double[] {2, -3, 1}, within(1e-10));   // y = 2x² − 3x + 1
        assertThat(doubles("polyfit($.ONE23, $.L3, 1)")).containsExactly(new double[] {2, 1}, within(1e-12));         // y = 2x + 1
    }

    @Test
    void theNewAggregatesOverAList() {
        assertThat(eval("sumproduct($.LEGS, 'N', 'R')")).isEqualTo(156L);                             // SUMPRODUCT
        assertThat(eval("sumsq($.C)")).isEqualTo(55L);
        assertThat(eval("product($.C)")).isEqualTo(120L);
        assertThat((Double) eval("product(cumsum($.R))")).isCloseTo(0.1 * 0.3 * 0.2, within(1e-15));
        assertThat(eval("devsq($.C)")).isEqualTo(10L);
        assertThat(eval("rank_avg($.TEMPS, 94)")).isEqualTo(4L);                                       // RANK.AVG
        assertThat(eval("percent_rank_of($.PR, 5)")).isEqualTo(0.583);                                // PERCENTRANK.INC
        assertThat(eval("percentile_exc($.C, 0.5)")).isEqualTo(3L);
        assertThat(list("mode_multi($.MM)")).containsExactly(1L, 2L, 3L);                             // MODE.MULT, as a list
        assertThat(eval("mode_multi($.C)")).isNull();
        assertThat(eval("sumproduct($.NOPE, $.C)")).isEqualTo(0L);
    }

    @Test
    void theReferencesExamplesOnTheDemoTrade() {
        // RACHANA_REFERENCE.md, "The Excel, SAS and MATLAB set": each example's result on the demo trade MX-20000001
        DataNode trade = new JsonCodec().read("""
                {"TRADE_ID":"MX-20000001","QUANTITY":242000000,"TRADE_DATE":"2025-11-21","STRATEGY":null,
                 "LEGS":[{"LEG_ID":1,"NOTIONAL":242000000},{"LEG_ID":2,"NOTIONAL":242000000}]}""");
        EvalContext c = EvalContext.of(trade, F);
        java.util.function.Function<String, Object> ev = src -> Values.simplify(EL.compile(src).eval(c));
        assertThat(ev.apply("round($.QUANTITY / 1e6, 1)")).isEqualTo(242L);
        assertThat(ev.apply("is_blank($.STRATEGY)")).isEqualTo(true);
        assertThat(ev.apply("text($.QUANTITY, '#,##0')")).isEqualTo("242,000,000");
        assertThat(ev.apply("shift_date($.TRADE_DATE, 2)")).isEqualTo("2025-11-25");
        assertThat(((DataNode.Arr) ev.apply("cumsum($.LEGS[*].NOTIONAL)")).elements().stream().map(Values::simplify).toList())
                .containsExactly(242000000L, 484000000L);
        assertThat(ev.apply("sumproduct($.LEGS, 'NOTIONAL', 'LEG_ID')")).isEqualTo(726000000L);
        assertThat(ev.apply("norm_s_inv(0.99)")).isEqualTo(2.3263478740408408);
    }

    @Test
    void masksAndWrongArguments() {
        assertThat(eval("cumsum($.HIDDEN)")).isEqualTo(DataNode.MASK);
        assertThat(eval("movmean($.HIDDEN, 3)")).isEqualTo(DataNode.MASK);
        assertThat(eval("zscore($.HIDDEN)")).isEqualTo(DataNode.MASK);
        assertThat(eval("product($.HIDDEN)")).isEqualTo(DataNode.MASK);
        assertThat(eval("cumsum($.NOPE)")).isNull();
        assertThatThrownBy(() -> eval("movmean($.A, 0)")).isInstanceOf(ElException.class).hasMessageContaining("the window");
        assertThatThrownBy(() -> eval("maxk($.A, 1.5)")).isInstanceOf(ElException.class).hasMessageContaining("whole number");
        assertThatThrownBy(() -> eval("cumsum('abc')")).isInstanceOf(ElException.class).hasMessageContaining("is not a list");
        assertThatThrownBy(() -> eval("polyfit($.SAME, $.ONE23, 1)")).isInstanceOf(ElException.class)
                .hasMessageContaining("needs 2 different x values");
        assertThatThrownBy(() -> eval("polyfit($.PFX, $.PFY, 7)")).isInstanceOf(ElException.class).hasMessageContaining("degrees up to 6");
        assertThatThrownBy(() -> eval("polyfit($.PFX, $.LINE, 1)")).isInstanceOf(ElException.class).hasMessageContaining("differ in length");
        assertThatThrownBy(() -> eval("rescale($.C, 2, 1)")).isInstanceOf(ElException.class).hasMessageContaining("above the high end");
    }
}
