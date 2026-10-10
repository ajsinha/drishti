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

import com.ash.drishti.api.DataNode;
import com.ash.drishti.rachana.format.Formats;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A masked field ({@link DataNode#MASK}) stands for the whole field: what lies under it reads masked, anything computed
 * from it is masked, and a condition on it is never true, so it can be neither seen nor probed.
 */
class MaskTest {

    static final ElCompiler EL = new ElCompiler();
    static final Formats F = Formats.load(null, List.of("../config/packs/finance/config/formats.yaml"));
    static final DataNode DOC;

    static {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", "T-1");
        doc.put("notional", 100);
        doc.put("rows", List.of(Map.of("pv", 1), Map.of("pv", 2)));
        Map<String, DataNode> fields = new LinkedHashMap<>(((DataNode.Obj) DataNode.of(doc)).fields());
        fields.put("mtm", DataNode.masked());
        fields.put("counterparty", DataNode.masked());
        fields.put("legs", DataNode.of(List.of(Map.of("pv", 5), Map.of("pv", DataNode.masked()))));
        DOC = new DataNode.Obj(fields);
    }

    private static Object eval(String src) {
        return Values.simplify(EL.compile(src).eval(EvalContext.of(DOC, F)));
    }

    @Test
    void whatLiesUnderAMaskIsMasked() {
        assertThat(eval("$.counterparty.name")).isEqualTo(DataNode.MASK);
        assertThat(eval("$.counterparty['name']")).isEqualTo(DataNode.MASK);
        assertThat(DOC.at("counterparty.address.city").isMasked()).isTrue();
        assertThat(DOC.at("notional.x").isMissing()).isTrue();          // under a plain value: missing, as before
    }

    @Test
    void valuesDerivedFromAMaskAreMasked() {
        assertThat(eval("$.mtm * 2")).isEqualTo(DataNode.MASK);
        assertThat(eval("-$.mtm")).isEqualTo(DataNode.MASK);
        assertThat(eval("abs($.mtm)")).isEqualTo(DataNode.MASK);
        assertThat(eval("fmt($.mtm, 'signed0')")).isEqualTo(DataNode.MASK);
        assertThat(eval("link($.counterparty.id, 'counterparty', $.counterparty.name)")).isEqualTo(DataNode.MASK);
        assertThat(eval("sum($.legs, 'pv')")).isEqualTo(DataNode.MASK);   // never added up
        assertThat(eval("sum($.rows, 'pv')")).isEqualTo(3L);
        assertThat(((Number) eval("coalesce($.missing, $.notional, $.mtm)")).longValue()).isEqualTo(100L);
        assertThat(eval("$.mtm > 0 ? 'gain' : 'loss'")).isEqualTo(DataNode.MASK);
        assertThat(EL.template("MTM ${fmt($.mtm, 'signed0')}").render(EvalContext.of(DOC, F))).isEqualTo("MTM " + DataNode.MASK);
        assertThat(F.format("signed0", DataNode.masked())).isEqualTo(DataNode.MASK);
        assertThat(F.format("date", DataNode.MASK)).isEqualTo(DataNode.MASK);
    }

    @Test
    void aConditionOnAMaskIsNeverTrue() {
        for (String c : List.of("$.mtm > 0", "$.mtm <= 0", "!($.mtm > 0)", "$.mtm == '" + DataNode.MASK + "'", "$.counterparty.name != 'x'",
                "contains($.counterparty.name, 'Mer')", "$.mtm > 0 && $.notional > 0", "$.mtm > 0 || $.notional < 0")) {
            assertThat(Values.truthy(EL.compile(c).eval(EvalContext.of(DOC, F)))).as(c).isFalse();
        }
        assertThat(Values.truthy(eval("$.mtm > 0 || $.notional > 0"))).isTrue();   // true for a reason that is not the mask
    }
}
