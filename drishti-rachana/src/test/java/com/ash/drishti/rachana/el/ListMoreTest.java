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

import static com.ash.drishti.rachana.el.Fy.eval;
import static com.ash.drishti.rachana.el.Fy.evalError;
import static com.ash.drishti.rachana.el.Fy.list;
import static com.ash.drishti.rachana.el.Fy.masked;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * MATLAB's and Octave's list functions that are not aggregates (FUNCTION_CATALOGUE.md section 6): fillmissing, interp1, polyval, trapz,
 * cumtrapz, the set functions and sort by several keys (sortrows), on MATLAB's documented examples where it has one.
 */
class ListMoreTest {

    @Test
    void fillmissingByEachMethod() {
        assertThat(list("fillmissing($.GAPS, 'linear')")).containsExactly(1L, 2L, 3L, 4L, 5L);   // the ends extended in a line
        assertThat(list("fillmissing($.GAPS, 'previous')")).containsExactly(null, 2L, 2L, 4L, 4L);
        assertThat(list("fillmissing($.GAPS, 'next')")).containsExactly(2L, 2L, 4L, 4L, null);
        assertThat(list("fillmissing($.GAPS, 'nearest')")).containsExactly(2L, 2L, 4L, 4L, 4L);
        assertThat(list("fillmissing($.GAPS, 'constant', 0)")).containsExactly(0L, 2L, 0L, 4L, 0L);
        Fx.compileError("fillmissing($.GAPS, 'spline')", "unknown method 'spline'");
        evalError("fillmissing($.GAPS, concat('spl', 'ine'))", "fillmissing", "unknown method 'spline'");
        evalError("fillmissing($.GAPS, 'constant')", "fillmissing", "needs the value");
        assertThat(eval("fillmissing($.NOPE, 'linear')")).isNull();
    }

    @Test
    void interp1AndPolyval() {
        assertThat(eval("interp1($.XS, $.VS, 2.5)")).isEqualTo(25L);
        assertThat(list("interp1($.XS, $.VS, $.QX)")).containsExactly(null, 15L, 25L, null);       // outside the points: empty
        assertThat(list("interp1($.XS, $.VS, $.QX, 'previous')")).containsExactly(null, 10L, 20L, null);
        assertThat(list("interp1($.XS, $.VS, $.QX, 'next')")).containsExactly(null, 20L, 30L, null);
        assertThat(list("interp1($.XS, $.VS, $.QX, 'nearest')")).containsExactly(null, 20L, 30L, null);   // a tie to the next
        evalError("interp1($.SAME, $.XS, 2)", "interp1", "two points share x = 2");
        evalError("interp1($.XS, $.SQ, 2)", "interp1", "differ in length");
        assertThat(list("polyval($.P, $.PX)")).containsExactly(86L, 162L, 262L);                   // MATLAB: polyval([3 2 1], [5 7 9])
        assertThat(eval("polyval($.P, 2)")).isEqualTo(17L);
    }

    @Test
    void trapezoids() {
        assertThat(eval("trapz($.SQ)")).isEqualTo(42L);                                            // MATLAB: trapz([1 4 9 16 25])
        assertThat(list("cumtrapz($.SQ)")).containsExactly(0L, 2.5, 9L, 21.5, 42L);                // MATLAB: cumtrapz
        assertThat(eval("trapz($.PFX, $.PFX)")).isEqualTo(8L);                                     // ∫₀⁴ x dx
        assertThat(eval("trapz($.GAPS)")).isNull();                                                // an area over a gap is not guessed
        assertThat(masked("trapz($.HIDDEN)")).isTrue();
    }

    @Test
    void setFunctionsCompareAsEqualityDoesAndSort() {
        assertThat(list("union($.S1, $.S2)")).containsExactly(1L, 2L, "EUR", "GBP", "USD");         // 2 and 2.0 are one value
        assertThat(list("intersect($.S1, $.S2)")).containsExactly(2L, "EUR");
        assertThat(list("difference($.S1, $.S2)")).containsExactly(1L, "USD");
        assertThat(eval("is_member('EUR', $.S1)")).isEqualTo(true);
        assertThat(eval("is_member(2.0, $.S1)")).isEqualTo(true);
        assertThat(eval("is_member('JPY', $.S1)")).isEqualTo(false);
        assertThat(eval("is_member('JPY', $.EMPTY)")).isEqualTo(false);                            // an empty list holds nothing
        assertThat(list("is_member($.S2, $.S1)")).containsExactly(true, true, false, true);
        assertThat(eval("union($.NOPE, $.S1)")).isNull();
        assertThat(masked("union($.HIDDEN, $.S1)")).isTrue();
    }

    @Test
    void sortBySeveralKeys() {
        List<Object> desks = list("map(sort($.TRADES, @.DESK, false, @.N, true), concat(@.DESK, @.N))");
        assertThat(desks).containsExactly("A5", "A2", "B3", "B1", "0");                         // an empty key last
        assertThat(list("map(sort($.TRADES, @.N), @.N)")).containsExactly(0L, 1L, 2L, 3L, 5L);   // one key, as before
        assertThat(Arrays.asList(list("sort($.A)").toArray())).first().isEqualTo(-3L);
    }
}
