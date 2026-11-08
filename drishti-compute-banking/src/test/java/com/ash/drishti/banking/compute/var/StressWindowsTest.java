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
package com.ash.drishti.banking.compute.var;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** The stress window selection's arithmetic (VAR_GUIDE.md section 18.9). */
class StressWindowsTest {

    static final VarMath.RankRule K = VarMath.RankRule.KTH_WORST;

    @Test
    void every_window_is_the_var_of_its_own_days_to_the_bit() {
        double[] pnl = new SplittableRandom(7).doubles(600, -1e6, 1e6).toArray();
        double[] r = StressWindows.rolling(pnl, 250, 0.99, K);
        assertEquals(351, r.length);
        for (int s : new int[] {0, 1, 100, 350}) {
            assertEquals(VarMath.var(Arrays.copyOfRange(pnl, s, s + 250), 0.99), r[s], 0.0);
        }
        assertEquals(0, StressWindows.rolling(new double[10], 250, 0.99, K).length);
        assertThrows(IllegalArgumentException.class, () -> StressWindows.rolling(pnl, 0, 0.99, K));
    }

    @Test
    void the_third_worst_of_250_at_99_percent() {
        double[] w = new double[250];
        w[10] = -500;
        w[20] = -400;
        w[30] = -300;
        w[40] = -200;
        assertEquals(300.0, StressWindows.rolling(w, 250, 0.99, K)[0], 0.0);
    }

    @Test
    void candidates_are_the_largest_windows_apart_from_one_another() {
        double[] r = {1, 5, 9, 8, 2, 7, 3, 9.5, 1};
        assertArrayEquals(new int[] {7, 2}, StressWindows.candidates(r, 3, 5));
        int[] c = StressWindows.candidates(r, 2, 5);
        assertArrayEquals(new int[] {7, 2, 5, 0}, c);
        for (int i = 0; i < c.length; i++) {
            for (int j = i + 1; j < c.length; j++) {
                assertTrue(Math.abs(c[i] - c[j]) >= 2);
            }
        }
        assertArrayEquals(new int[] {7}, StressWindows.candidates(r, 2, 1));
        assertArrayEquals(new int[] {0, 2}, StressWindows.candidates(new double[] {4, 4, 4}, 2, 5));       // ties: the earlier start
    }

    @Test
    void worst_day_index_and_tail() {
        double[] pnl = {5, -1, -7, 3, -7, 2};
        assertEquals(2, StressWindows.worstDay(pnl, 1, 4));
        assertEquals(4, StressWindows.worstDay(pnl, 3, 3));
        String[] dates = {"2008-06-30", "2008-07-01", "2008-07-02"};
        assertEquals(1, StressWindows.indexOf(dates, "2008-07-01"));
        assertEquals(1, StressWindows.indexOf(dates, "2008-06-30T"));
        assertEquals(-1, StressWindows.indexOf(dates, "2009-01-01"));
        assertEquals(VarMath.var(new double[] {3, -7, 2}, 0.99), StressWindows.tail(pnl, 3, 0.99, K), 0.0);
        assertTrue(Double.isNaN(StressWindows.tail(pnl, 7, 0.99, K)));
    }
}
