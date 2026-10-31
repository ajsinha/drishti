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
package com.ash.drishti.compute.reactive.analytics;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.compute.ComputeEngine;
import com.ash.drishti.api.compute.EngineException;
import com.ash.drishti.api.compute.EngineInputs;
import com.ash.drishti.api.compute.EngineManifest;
import com.ash.drishti.api.compute.EngineProgress;
import com.ash.drishti.api.compute.EngineRequest;
import com.ash.drishti.api.compute.EngineResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The correlations of a factor set from price history (CORR, docs/guides/PACK_DEVELOPER_GUIDE.md › Part VII. Groupings and analytics, computed, section 15): the market-data
 * pack's correlation set names its factors (equities by {@code EQUITY_IDS}, FX pairs by {@code FX_IDS}); each factor's daily closes
 * ({@code HISTORY[].CLOSE}, or {@code MID} for an FX pair) are aligned on the dates every factor has, and the last {@code window}
 * daily log returns are correlated by the {@code estimator}:
 *
 * <ul>
 *   <li>{@code pearson} (the default): the sample correlation of the returns;</li>
 *   <li>{@code ledoit-wolf}: the sample covariance of the standardised returns shrunk toward the identity with Ledoit and Wolf's (2004)
 *       optimal intensity, then read back as correlations; {@code SHRINKAGE} is the intensity (0 none, 1 all the way to no correlation).</li>
 * </ul>
 *
 * A window of 0 (the default) or longer than the history uses every return the history holds. Result: one row with the set, the window
 * actually used, its dates, the average, lowest and highest pairwise correlation, every pair ({@code PAIRS}) and each factor's
 * annualised volatility ({@code FACTOR_STATS}, the square root of 252 times the daily variance). Drishti's own: it runs in the server.
 */
public final class CorrelationEngine implements ComputeEngine {

    /** Trading days a year, for the annualised volatility. */
    static final int YEAR_DAYS = 252;

    @Override
    public EngineManifest manifest() {
        return new EngineManifest("correlation", "1.0", List.of("correlation"));
    }

    @Override
    public EngineResult compute(EngineRequest req, EngineInputs inputs, EngineProgress progress) throws Exception {
        List<DataNode> sets = inputs.documents("set");
        if (sets.isEmpty()) {
            throw new EngineException("there is no correlation set " + req.id() + " (CORR lists them)");
        }
        DataNode set = sets.get(0);
        int window = Integer.parseInt(String.valueOf(req.param("window", 0)));
        String estimator = String.valueOf(req.param("estimator", "pearson")).toLowerCase(Locale.ROOT);
        if (!estimator.equals("pearson") && !estimator.equals("ledoit-wolf")) {
            throw new EngineException("the estimator is pearson or ledoit-wolf, not " + estimator);
        }
        Map<String, DataNode> byId = new LinkedHashMap<>();
        for (String input : List.of("equities", "fx")) {
            if (inputs.names().contains(input)) {
                for (DataNode d : inputs.documents(input)) {
                    String id = text(d.get("TICKER"));
                    byId.put(id.isEmpty() ? text(d.get("PAIR")) : id, d);
                }
            }
        }
        List<String> ids = new ArrayList<>();
        ids.addAll(texts(set.get("EQUITY_IDS")));
        ids.addAll(texts(set.get("FX_IDS")));
        if (ids.size() < 2) {
            throw new EngineException(req.id() + " names " + ids.size() + " factor(s): a correlation needs two or more");
        }
        List<TreeMap<String, Double>> closes = new ArrayList<>();
        for (String id : ids) {
            DataNode d = byId.get(id);
            if (d == null) {
                throw new EngineException("no price history for " + id + " (a factor of " + req.id() + ")");
            }
            closes.add(closes(d));
        }
        return EngineResult.of(correlate(text(set.get("MATRIX_ID")).isEmpty() ? req.id() : text(set.get("MATRIX_ID")), text(set.get("NAME")),
                ids, closes, window, estimator));
    }

    /**
     * The correlation of the factors' closes, aligned on their common dates: the answer's one row. Package-visible for the tests.
     */
    static Map<String, Object> correlate(String setId, String name, List<String> ids, List<TreeMap<String, Double>> closes, int window,
            String estimator) throws EngineException {
        TreeSet<String> dates = new TreeSet<>(closes.get(0).keySet());
        closes.forEach(c -> dates.retainAll(c.keySet()));
        if (dates.size() < 3) {
            throw new EngineException("the factors of " + setId + " share " + dates.size() + " dates of prices: too few to correlate");
        }
        List<String> d = new ArrayList<>(dates);
        int returns = d.size() - 1;
        int used = window <= 0 || window > returns ? returns : window;
        List<String> span = d.subList(d.size() - used - 1, d.size());
        int p = ids.size();
        double[][] r = new double[p][used];
        for (int f = 0; f < p; f++) {
            for (int t = 0; t < used; t++) {
                double a = closes.get(f).get(span.get(t));
                double b = closes.get(f).get(span.get(t + 1));
                if (a <= 0 || b <= 0) {
                    throw new EngineException(ids.get(f) + " has a price of zero or less on " + span.get(a <= 0 ? t : t + 1));
                }
                r[f][t] = Math.log(b / a);
            }
        }
        double[] mean = new double[p];
        double[] sd = new double[p];
        for (int f = 0; f < p; f++) {
            double s = 0;
            for (double x : r[f]) {
                s += x;
            }
            mean[f] = s / used;
            double v = 0;
            for (double x : r[f]) {
                v += (x - mean[f]) * (x - mean[f]);
            }
            sd[f] = Math.sqrt(v / used);
            if (sd[f] == 0) {
                throw new EngineException(ids.get(f) + " did not move over the window: its correlation is undefined");
            }
        }
        double[][] z = new double[p][used];                 // the standardised returns
        for (int f = 0; f < p; f++) {
            for (int t = 0; t < used; t++) {
                z[f][t] = (r[f][t] - mean[f]) / sd[f];
            }
        }
        double[][] s = new double[p][p];                    // their covariance: the sample correlation
        for (int i = 0; i < p; i++) {
            for (int j = 0; j < p; j++) {
                double c = 0;
                for (int t = 0; t < used; t++) {
                    c += z[i][t] * z[j][t];
                }
                s[i][j] = c / used;
            }
        }
        Double shrinkage = null;
        double[][] rho = s;
        if (estimator.equals("ledoit-wolf")) {
            double delta = ledoitWolf(z, s, used);
            shrinkage = delta;
            rho = new double[p][p];
            double m = 0;
            for (int i = 0; i < p; i++) {
                m += s[i][i];
            }
            m /= p;
            for (int i = 0; i < p; i++) {
                for (int j = 0; j < p; j++) {
                    rho[i][j] = (1 - delta) * s[i][j] + (i == j ? delta * m : 0);
                }
            }
            double[][] back = new double[p][p];             // read back as correlations
            for (int i = 0; i < p; i++) {
                for (int j = 0; j < p; j++) {
                    back[i][j] = rho[i][j] / Math.sqrt(rho[i][i] * rho[j][j]);
                }
            }
            rho = back;
        }
        List<Map<String, Object>> pairs = new ArrayList<>();
        double sum = 0;
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < p; i++) {
            for (int j = i + 1; j < p; j++) {
                double v = round(rho[i][j], 4);
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("A", ids.get(i));
                pair.put("B", ids.get(j));
                pair.put("RHO", v);
                pairs.add(pair);
                sum += v;
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
        }
        List<Map<String, Object>> stats = new ArrayList<>();
        for (int f = 0; f < p; f++) {
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("FACTOR", ids.get(f));
            st.put("LAST", closes.get(f).get(span.get(span.size() - 1)));
            st.put("RETURN", round(Math.exp(sumOf(r[f])) - 1, 6));
            st.put("VOLATILITY", round(sd[f] * Math.sqrt(YEAR_DAYS), 6));
            stats.add(st);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("MATRIX_ID", setId);
        row.put("NAME", name);
        row.put("FACTORS", ids);
        row.put("ESTIMATOR", estimator);
        row.put("WINDOW", used);
        row.put("AVAILABLE", returns);
        row.put("FROM", span.get(0));
        row.put("TO", span.get(span.size() - 1));
        row.put("AVERAGE", round(sum / pairs.size(), 4));
        row.put("MIN_RHO", lo);
        row.put("MAX_RHO", hi);
        if (shrinkage != null) {
            row.put("SHRINKAGE", round(shrinkage, 4));
        }
        row.put("PAIRS", pairs);
        row.put("FACTOR_STATS", stats);
        return row;
    }

    /**
     * Ledoit and Wolf's optimal shrinkage intensity toward the scaled identity ("A well-conditioned estimator for large-dimensional
     * covariance matrices", 2004), for the covariance {@code s} of the observations {@code x} (factors by time, mean zero): between 0
     * and 1.
     */
    static double ledoitWolf(double[][] x, double[][] s, int n) {
        int p = s.length;
        double m = 0;
        for (int i = 0; i < p; i++) {
            m += s[i][i];
        }
        m /= p;
        double d2 = 0;
        for (int i = 0; i < p; i++) {
            for (int j = 0; j < p; j++) {
                double e = s[i][j] - (i == j ? m : 0);
                d2 += e * e;
            }
        }
        d2 /= p;
        double b2bar = 0;
        for (int t = 0; t < n; t++) {
            double norm = 0;
            for (int i = 0; i < p; i++) {
                for (int j = 0; j < p; j++) {
                    double e = x[i][t] * x[j][t] - s[i][j];
                    norm += e * e;
                }
            }
            b2bar += norm / p;
        }
        b2bar /= (double) n * n;
        if (d2 == 0) {
            return 1;
        }
        double b2 = Math.min(b2bar, d2);
        return b2 / d2;
    }

    /** A history's closes by date: {@code HISTORY[]} rows of {@code DATE} and {@code CLOSE} (an equity) or {@code MID} (an FX pair). */
    static TreeMap<String, Double> closes(DataNode doc) {
        TreeMap<String, Double> out = new TreeMap<>();
        DataNode h = doc.get("HISTORY");
        for (int i = 0; i < h.size(); i++) {
            DataNode row = h.get(i);
            String date = text(row.get("DATE"));
            DataNode v = row.get("CLOSE").isNull() ? row.get("MID") : row.get("CLOSE");
            if (!date.isEmpty() && !v.isNull() && !Double.isNaN(v.asDouble())) {
                out.put(date, v.asDouble());
            }
        }
        return out;
    }

    private static List<String> texts(DataNode list) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String t = text(list.get(i));
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String text(DataNode n) {
        return n == null || n.isNull() ? "" : n.asText().trim();
    }

    private static double sumOf(double[] v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return s;
    }

    private static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
