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
import static org.assertj.core.api.Assertions.within;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.JsonCodec;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the tests of the 2.9.11.e functions share (finance, distributions, MATLAB's lists): a document holding the lists of Excel's and
 * MATLAB's documented examples (Rachana has no list literal, so an example's list is read from the document), masked values beside them,
 * and the view's business date 2026-10-16.
 */
final class Fy {

    static final DataNode DOC;

    static {
        StringBuilder bond = new StringBuilder("[");
        StringBuilder times = new StringBuilder("[");
        for (int k = 1; k <= 59; k++) {                                   // 8 % semi-annual coupons, 2018-07-01 to 2048-01-01, per 100
            bond.append(k == 59 ? "104" : "4").append(k == 59 ? "]" : ",");
            times.append(k / 2.0).append(k == 59 ? "]" : ",");
        }
        DataNode plain = new JsonCodec().read("""
                {"NPV":[-10000,3000,4200,6800],
                 "XF":[-10000,2750,4250,3250,2750],"XD":["2008-01-01","2008-03-01","2008-10-30","2009-02-15","2009-04-01"],
                 "IRR1":[-70000,12000,15000,18000,21000],"IRR2":[-70000,12000,15000,18000,21000,26000],"IRR3":[-70000,12000,15000],
                 "MIRR":[-120000,39000,30000,21000,37000,46000],"FVS":[0.09,0.11,0.1],"POS":[1,2,3],"GAP":[-100,null,50],
                 "TXT":[-100,"abc"],"BACK":["2008-01-01","2007-12-31"],
                 "BOND":%s,"TIMES":%s,"BDATES":["2027-10-16","2028-10-16"],"B2":[5,105],"T12":[1,2],
                 "A":[4,8,6,-1,-2,-3,-1,3,4,5],"OUT":[57,59,60,100,59,58,57,58,300,61,62,60,62,58,57],
                 "SQ":[1,4,9,16,25],"P":[3,2,1],"PX":[5,7,9],"B":[2,4,-1,10,6,3,0,-16],"ONE23":[1,2,3],
                 "XS":[1,2,3],"VS":[10,20,30],"GAPS":[null,2,null,4,null],"QX":[0,1.5,2.5,4],
                 "PFX":[0,1,2,3,4],"PFY":[1,0,3,10,21],"SAME":[2,2,2],
                 "S1":["USD","EUR",1,2],"S2":["EUR",2,"GBP",2.0],"EMPTY":[],
                 "TRADES":[{"DESK":"B","N":3},{"DESK":"A","N":2},{"DESK":"B","N":1},{"DESK":"A","N":5},{"N":0}]}"""
                .formatted(bond, times));
        Map<String, DataNode> fields = new LinkedHashMap<>(((DataNode.Obj) plain).fields());
        fields.put("MTM", DataNode.masked());
        List<DataNode> hidden = new ArrayList<>(List.of(DataNode.of(-100), DataNode.masked(), DataNode.of(60)));
        fields.put("HIDDEN", new DataNode.Arr(List.copyOf(hidden)));
        DOC = new DataNode.Obj(fields);
    }

    private Fy() {}

    static EvalContext ctx() {
        return EvalContext.of(DOC, Fx.F).withToday(LocalDate.of(2026, 10, 16));
    }

    static Object eval(String src) {
        return Values.simplify(Fx.EL.compile(src).eval(ctx()));
    }

    static boolean masked(String src) {
        return Values.masked(Fx.EL.compile(src).eval(ctx()));
    }

    static double num(String src) {
        Object v = eval(src);
        assertThat(v).as(src).isInstanceOf(Number.class);
        return ((Number) v).doubleValue();
    }

    /** The expression's value is within {@code tol} of {@code expected}. */
    static void near(String src, double expected, double tol) {
        assertThat(num(src)).as(src).isCloseTo(expected, within(tol));
    }

    static List<Object> list(String src) {
        Object v = eval(src);
        assertThat(v).as(src).isInstanceOf(DataNode.Arr.class);
        return ((DataNode.Arr) v).elements().stream().map(Values::simplify).toList();
    }

    static void evalError(String src, String... words) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> eval(src)).as(src).isInstanceOfSatisfying(ElException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(com.ash.drishti.common.ErrorCode.EL_EVAL);
            for (String w : words) {
                assertThat(e.getMessage()).contains(w);
            }
        });
    }
}
