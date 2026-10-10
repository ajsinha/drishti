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
package com.ash.drishti.rachana;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.rachana.format.Tones;
import org.junit.jupiter.api.Test;

class FormatsTest {

    final Formats f = Formats.load(null, java.util.List.of("../config/packs/finance/config/formats.yaml"));

    @Test
    void numbersSignsPercentsAndCompact() {
        assertThat(f.format("amount0", 50000000)).isEqualTo("50,000,000");
        assertThat(f.format("signed2", -1962430.56)).isEqualTo("−1,962,430.56");
        assertThat(f.format("signed0", 22310)).isEqualTo("+22,310");
        assertThat(f.format("signed0", 0)).isEqualTo("0");
        assertThat(f.format("pct4", 0.0385)).isEqualTo("3.8500%");
        assertThat(f.format("pct0", 0.65)).isEqualTo("65%");
        assertThat(f.format("rate5", 1.174)).isEqualTo("1.17400");
        assertThat(f.format("pips1", 55.8)).isEqualTo("+55.8");
        assertThat(f.format("compact", 4100000)).isEqualTo("4.1m");
        assertThat(f.format("compact", 15000000)).isEqualTo("15.0m");
        assertThat(f.format("date", "2026-10-02")).isEqualTo("2026-10-02");
        assertThat(f.format("dmy", "2026-10-02")).isEqualTo("02 Oct 2026");
        assertThat(f.format("nosuch", 12)).isEqualTo("12");
        assertThat(f.format("amount0", null)).isEmpty();
    }

    @Test
    void tones() {
        assertThat(Tones.resolve("sign", -3)).isEqualTo("neg");
        assertThat(Tones.resolve("sign", 3)).isEqualTo("pos");
        assertThat(Tones.resolve("sign", 0)).isNull();
        assertThat(Tones.resolve("status", "Confirmed")).isEqualTo("ok");
        assertThat(Tones.resolve("status", "Disputed")).isEqualTo("bad");
    }
}
