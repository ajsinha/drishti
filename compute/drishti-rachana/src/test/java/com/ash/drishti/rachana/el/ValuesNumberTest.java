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

import org.junit.jupiter.api.Test;

class ValuesNumberTest {

    @Test
    void textThatIsNotANumberIsRejectedWithoutParsingAndNumbersStillRead() {
        for (String no : new String[] {"2026-09-09", "CP-0042", "ENDUR", "", "  ", "1-2", "MX-50000042", "2026-09-09T10:00:00Z"}) {
            assertThat(Values.couldBeNumber(no)).as(no).isFalse();
            assertThat(Values.number(no)).as(no).isNaN();
        }
        assertThat(Values.number("42")).isEqualTo(42.0);
        assertThat(Values.number(" -1.5e-3 ")).isEqualTo(-1.5e-3);
        assertThat(Values.number("+7")).isEqualTo(7.0);
        assertThat(Values.number(".5")).isEqualTo(0.5);
        assertThat(Values.number("1E+6")).isEqualTo(1e6);
        assertThat(Values.number("Infinity")).isInfinite();
        assertThat(Values.number("1.5x")).isNaN();                 // still parsed, still rejected
    }
}
