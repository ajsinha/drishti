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
package com.ash.drishti.plugin.yfinance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** PXH analytics: returns, volatility, drawdown and beta from a price history, never stored. */
class PriceAnalyticsTest {

    private static List<Map<String, Object>> history(double... closes) {
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 2);
        for (double c : closes) {
            out.add(Map.of("date", d.toString(), "close", c));
            d = d.plusDays(1);
        }
        return out;
    }

    @Test
    void returnsVolatilityAndDrawdownFromTheCloses() {
        double[] c = new double[130];
        for (int i = 0; i < c.length; i++) {
            c[i] = 100 + i;                                  // a steady rise: 100 to 229
        }
        c[60] = 80;                                          // one fall: from 159's high to 80
        Map<String, Object> a = PriceAnalytics.of(history(c), null, "");
        assertThat(a.get("observations")).isEqualTo(130);
        assertThat((double) a.get("return1m")).isCloseTo(229.0 / 208 - 1, offset(1e-6));
        assertThat((double) a.get("return6m")).isCloseTo(229.0 / 103 - 1, offset(1e-6));
        assertThat((double) a.get("returnPeriod")).isCloseTo(1.29, offset(1e-6));
        assertThat((double) a.get("maxDrawdown")).isCloseTo(80.0 / 159 - 1, offset(1e-6));
        assertThat((double) a.get("volatility")).isPositive();
        assertThat(a.get("beta")).isNull();                  // no benchmark
    }

    @Test
    void betaIsTheCovarianceOverTheBenchmarksVariance() {
        double[] bench = new double[40];
        double[] twice = new double[40];
        bench[0] = 100;
        twice[0] = 50;
        for (int i = 1; i < 40; i++) {
            double r = (i % 3 == 0 ? -0.01 : 0.008) + (i % 7) * 0.001;
            bench[i] = bench[i - 1] * Math.exp(r);
            twice[i] = twice[i - 1] * Math.exp(2 * r);       // twice the benchmark's log return every day
        }
        Map<String, Object> a = PriceAnalytics.of(history(twice), history(bench), "SPY");
        assertThat((double) a.get("beta")).isCloseTo(2.0, offset(1e-5));
        assertThat((double) a.get("correlation")).isCloseTo(1.0, offset(1e-5));
        assertThat(a.get("benchmark")).isEqualTo("SPY");
        assertThat(a.get("return3m")).isNull();              // forty closes: no three-month return
    }
}
