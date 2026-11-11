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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Returns, volatility and beta worked out from a price history when it is read (PXH analytics, docs/guides/GROUPINGS_ANALYTICS_GUIDE.md
 * section 17): nothing is stored, and the figures are as internal as the prices they come from. From the daily closes (rows of
 * {@code date} and {@code close}, oldest first):
 *
 * <ul>
 *   <li>{@code return1m}, {@code return3m}, {@code return6m}: the simple return over the last 21, 63 and 126 trading days (empty when
 *       the history is shorter); {@code returnPeriod}: over the whole history;</li>
 *   <li>{@code volatility}: the standard deviation of the daily log returns, annualised by the square root of 252;</li>
 *   <li>{@code maxDrawdown}: the largest fall from a running high to a later close, as a negative fraction;</li>
 *   <li>{@code beta} and {@code correlation} against the benchmark (its closes on the same dates): the covariance of the daily log
 *       returns over the benchmark's variance, and their correlation; empty without a benchmark or with fewer than 20 common returns.</li>
 * </ul>
 */
final class PriceAnalytics {

    static final int YEAR_DAYS = 252;
    static final int MIN_COMMON = 20;

    private PriceAnalytics() {
    }

    /** The analytics of {@code history} (and of it against {@code benchmark}, which may be null); keys as above. */
    static Map<String, Object> of(List<Map<String, Object>> history, List<Map<String, Object>> benchmark, String benchmarkSymbol) {
        TreeMap<String, Double> px = closes(history);
        Map<String, Object> out = new LinkedHashMap<>();
        List<Double> c = new ArrayList<>(px.values());
        out.put("observations", c.size());
        out.put("return1m", back(c, 21));
        out.put("return3m", back(c, 63));
        out.put("return6m", back(c, 126));
        out.put("returnPeriod", c.size() < 2 ? null : round(c.get(c.size() - 1) / c.get(0) - 1));
        List<Double> r = logReturns(c);
        out.put("volatility", r.size() < 2 ? null : round(Math.sqrt(variance(r)) * Math.sqrt(YEAR_DAYS)));
        double high = Double.NEGATIVE_INFINITY;
        double worst = 0;
        for (double x : c) {
            high = Math.max(high, x);
            worst = Math.min(worst, x / high - 1);
        }
        out.put("maxDrawdown", c.isEmpty() ? null : round(worst));
        out.put("benchmark", benchmarkSymbol == null ? "" : benchmarkSymbol);
        Double beta = null;
        Double corr = null;
        if (benchmark != null) {
            TreeMap<String, Double> bx = closes(benchmark);
            List<String> common = new ArrayList<>(px.keySet());
            common.retainAll(bx.keySet());
            if (common.size() > MIN_COMMON) {
                List<Double> a = new ArrayList<>();
                List<Double> b = new ArrayList<>();
                for (String d : common) {
                    a.add(px.get(d));
                    b.add(bx.get(d));
                }
                List<Double> ra = logReturns(a);
                List<Double> rb = logReturns(b);
                double vb = variance(rb);
                double va = variance(ra);
                double cov = covariance(ra, rb);
                beta = vb == 0 ? null : round(cov / vb);
                corr = vb == 0 || va == 0 ? null : round(cov / Math.sqrt(va * vb));
            }
        }
        out.put("beta", beta);
        out.put("correlation", corr);
        return out;
    }

    private static TreeMap<String, Double> closes(List<Map<String, Object>> rows) {
        TreeMap<String, Double> out = new TreeMap<>();
        if (rows == null) {
            return out;
        }
        for (Map<String, Object> row : rows) {
            Object d = row.get("date");
            Object v = row.get("close");
            if (d != null && v instanceof Number n && n.doubleValue() > 0) {
                String day = String.valueOf(d);
                out.put(day.length() >= 10 ? day.substring(0, 10) : day, n.doubleValue());
            }
        }
        return out;
    }

    private static Double back(List<Double> c, int days) {
        return c.size() <= days ? null : round(c.get(c.size() - 1) / c.get(c.size() - 1 - days) - 1);
    }

    private static List<Double> logReturns(List<Double> c) {
        List<Double> out = new ArrayList<>();
        for (int i = 1; i < c.size(); i++) {
            out.add(Math.log(c.get(i) / c.get(i - 1)));
        }
        return out;
    }

    /** The sample variance (n - 1). */
    private static double variance(List<Double> x) {
        return covariance(x, x);
    }

    private static double covariance(List<Double> x, List<Double> y) {
        int n = Math.min(x.size(), y.size());
        if (n < 2) {
            return 0;
        }
        double mx = 0;
        double my = 0;
        for (int i = 0; i < n; i++) {
            mx += x.get(i);
            my += y.get(i);
        }
        mx /= n;
        my /= n;
        double s = 0;
        for (int i = 0; i < n; i++) {
            s += (x.get(i) - mx) * (y.get(i) - my);
        }
        return s / (n - 1);
    }

    private static double round(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
