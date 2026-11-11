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

import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.rachana.format.Formats;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** GRAM-01/GRAM-03: pathological expressions are located compile errors (DRS-2101), never a StackOverflowError. */
class ElLimitsTest {

    static final ElCompiler EL = new ElCompiler();

    static String parens(int n) {
        return "(".repeat(n) + "$.mtm" + ")".repeat(n);
    }

    static String chain(int n) {
        return String.join(" + ", Collections.nCopies(n, "$.mtm"));
    }

    static Object eval(ElCompiler el, String src, String json) {
        return Values.simplify(el.compile(src).eval(EvalContext.of(new JsonCodec().read(json), Formats.defaults())));
    }

    @Test
    void deeplyNestedParenthesesAreALocatedCompileError() {
        assertThatThrownBy(() -> EL.compile(parens(3000))).isInstanceOf(ElException.class)
                .hasMessageStartingWith("DRS-2101").hasMessageContaining("nested deeper than 200");
        assertThat(eval(EL, parens(150), "{\"mtm\":3}")).isEqualTo(3L);
    }

    @Test
    void longChainsThatWouldOverflowEvaluationAreRefusedAtCompileTime() {
        assertThatThrownBy(() -> EL.compile(chain(5000))).isInstanceOf(ElException.class).hasMessageStartingWith("DRS-2101");
        assertThatThrownBy(() -> EL.compile("-".repeat(100_000) + "1")).isInstanceOf(ElException.class).hasMessageStartingWith("DRS-2101");
        assertThatThrownBy(() -> EL.compile("$.a[".repeat(5000) + "0" + "]".repeat(5000))).isInstanceOf(ElException.class)
                .hasMessageStartingWith("DRS-2101");
        assertThatThrownBy(() -> EL.compile("coalesce(".repeat(5000) + "1" + ")".repeat(5000))).isInstanceOf(ElException.class)
                .hasMessageStartingWith("DRS-2101");
        assertThatThrownBy(() -> EL.compile("1 ? ".repeat(5000) + "1" + " : 2".repeat(5000))).isInstanceOf(ElException.class)
                .hasMessageStartingWith("DRS-2101");
        assertThatThrownBy(() -> EL.compile("$" + ".a".repeat(5000))).isInstanceOf(ElException.class).hasMessageStartingWith("DRS-2101");
        assertThat(eval(EL, chain(100), "{\"mtm\":1}")).isEqualTo(100L);
    }

    @Test
    void theLimitsAreSettingsAndLengthIsBoundedToo() {
        ElCompiler strict = new ElCompiler(100, new ElLimits(10, 50));
        assertThatThrownBy(() -> strict.compile(parens(11))).hasMessageContaining("nested deeper than 10");
        assertThatThrownBy(() -> strict.compile("'" + "x".repeat(60) + "'")).isInstanceOf(ElException.class)
                .hasMessageStartingWith("DRS-2101").hasMessageContaining("longer than 50");
        assertThatThrownBy(() -> strict.template("a ${" + chain(20) + "} b")).isInstanceOf(ElException.class);
        assertThat(strict.compile(parens(5))).isNotNull();
        assertThatThrownBy(() -> new ElLimits(0, 10)).isInstanceOf(IllegalArgumentException.class);
    }
}
