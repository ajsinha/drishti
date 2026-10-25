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
package com.ash.drishti.compute.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Exceptions against the previous day's VaR, the Basel traffic light, and Kupiec's and Christoffersen's statistics. */
class BacktestTest {

    @Test
    void anExceptionIsALossBeyondThePreviousDaysVar() {
        double[] var = new double[251];
        Arrays.fill(var, 100);
        double[] pnl = new double[251];
        pnl[10] = -101;                       // an exception
        pnl[11] = -100;                       // equal to VaR: not one
        pnl[0] = -500;                        // the first day has no previous VaR
        Backtest b = Backtest.of(pnl, var, 0.99);
        assertThat(b.days()).isEqualTo(250);
        assertThat(b.exceptions()).isEqualTo(1);
        assertThat(b.exceptionFlags()[10]).isTrue();
        assertThat(b.light()).isEqualTo("green");
    }

    @Test
    void trafficLightZones() {
        assertThat(Backtest.light(4, 250)).isEqualTo("green");
        assertThat(Backtest.light(5, 250)).isEqualTo("amber");
        assertThat(Backtest.light(9, 250)).isEqualTo("amber");
        assertThat(Backtest.light(10, 250)).isEqualTo("red");
        assertThat(Backtest.light(2, 60)).isEqualTo("amber");       // a short history: limits scaled (1 green, 2 amber at 60 days)
    }

    @Test
    void kupiecMatchesItsFormula() {
        // n = 250, x = 7, p = 0.01: LR = -2 ln[(0.99^243 0.01^7) / ((243/250)^243 (7/250)^7)] = 5.4993...
        double lr = Backtest.kupiec(250, 7, 0.01);
        double want = -2 * (243 * Math.log(0.99) + 7 * Math.log(0.01) - 243 * Math.log(243 / 250.0) - 7 * Math.log(7 / 250.0));
        assertThat(lr).isCloseTo(want, within(1e-9));
        assertThat(Backtest.chi2PValue(3.841459)).isCloseTo(0.05, within(1e-4));       // the 95% critical value of chi-squared(1)
        assertThat(Backtest.kupiec(250, 2, 0.01)).isLessThan(1.0);                     // about the expected 2.5
    }

    @Test
    void clusteredExceptionsFailIndependence() {
        boolean[] spread = new boolean[250];
        boolean[] cluster = new boolean[250];
        for (int i = 0; i < 6; i++) {
            spread[20 + 40 * i] = true;
            cluster[100 + i] = true;
        }
        assertThat(Backtest.christoffersen(cluster)).isGreaterThan(Backtest.christoffersen(spread));
        assertThat(Backtest.chi2PValue(Backtest.christoffersen(cluster))).isLessThan(0.05);
    }
}
