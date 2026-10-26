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
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * An equity's beta against its index from their price histories (EQB, docs/guides/GROUPINGS_ANALYTICS_GUIDE.md section 17): the equity's
 * daily closes ({@code HISTORY[].CLOSE}) and its index's ({@code EQUITY_INDEX}'s), aligned on the dates both have; over the last
 * {@code window} daily log returns (0, the default, every one) the ordinary least squares regression of the equity's returns on the
 * index's: {@code BETA = cov(r, r_i) / var(r_i)}, {@code ALPHA} (daily), {@code R_SQUARED} and {@code CORRELATION}, with both annualised
 * volatilities. Replaces the security master's stored {@code BETA} (retired in 2.9.2). Drishti's own: it runs in the server.
 */
public final class BetaEngine implements ComputeEngine {

    @Override
    public EngineManifest manifest() {
        return new EngineManifest("beta", "1.0", List.of("beta"));
    }

    @Override
    public EngineResult compute(EngineRequest req, EngineInputs inputs, EngineProgress progress) throws Exception {
        List<DataNode> eq = inputs.documents("equity");
        if (eq.isEmpty()) {
            throw new EngineException("there is no equity " + req.id());
        }
        DataNode equity = eq.get(0);
        String index = text(equity.get("EQUITY_INDEX"));
        if (index.isEmpty()) {
            throw new EngineException(req.id() + " names no index (EQUITY_INDEX): its beta has nothing to be measured against");
        }
        List<DataNode> ix = inputs.documents("index");
        if (ix.isEmpty()) {
            throw new EngineException("no price history for the index " + index + " of " + req.id());
        }
        int window = Integer.parseInt(String.valueOf(req.param("window", 0)));
        return EngineResult.of(beta(req.id(), index, CorrelationEngine.closes(equity), CorrelationEngine.closes(ix.get(0)), window));
    }

    /** The regression of the equity's daily log returns on the index's: the answer's one row. Package-visible for the tests. */
    static Map<String, Object> beta(String equity, String index, TreeMap<String, Double> e, TreeMap<String, Double> i, int window) throws EngineException {
        TreeSet<String> dates = new TreeSet<>(e.keySet());
        dates.retainAll(i.keySet());
        if (dates.size() < 3) {
            throw new EngineException(equity + " and " + index + " share " + dates.size() + " dates of prices: too few for a beta");
        }
        List<String> d = new ArrayList<>(dates);
        int returns = d.size() - 1;
        int used = window <= 0 || window > returns ? returns : window;
        List<String> span = d.subList(d.size() - used - 1, d.size());
        double[] y = new double[used];
        double[] x = new double[used];
        for (int t = 0; t < used; t++) {
            y[t] = Math.log(e.get(span.get(t + 1)) / e.get(span.get(t)));
            x[t] = Math.log(i.get(span.get(t + 1)) / i.get(span.get(t)));
        }
        double my = mean(y);
        double mx = mean(x);
        double sxx = 0;
        double syy = 0;
        double sxy = 0;
        for (int t = 0; t < used; t++) {
            sxx += (x[t] - mx) * (x[t] - mx);
            syy += (y[t] - my) * (y[t] - my);
            sxy += (x[t] - mx) * (y[t] - my);
        }
        if (sxx == 0) {
            throw new EngineException(index + " did not move over the window: the beta is undefined");
        }
        double beta = sxy / sxx;
        double corr = syy == 0 ? 0 : sxy / Math.sqrt(sxx * syy);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("EQUITY", equity);
        row.put("INDEX", index);
        row.put("WINDOW", used);
        row.put("AVAILABLE", returns);
        row.put("FROM", span.get(0));
        row.put("TO", span.get(span.size() - 1));
        row.put("BETA", round(beta, 4));
        row.put("ALPHA", round(my - beta * mx, 6));
        row.put("CORRELATION", round(corr, 4));
        row.put("R_SQUARED", round(corr * corr, 4));
        row.put("VOLATILITY", round(Math.sqrt(syy / (used - 1)) * Math.sqrt(CorrelationEngine.YEAR_DAYS), 4));
        row.put("INDEX_VOLATILITY", round(Math.sqrt(sxx / (used - 1)) * Math.sqrt(CorrelationEngine.YEAR_DAYS), 4));
        return row;
    }

    private static double mean(double[] v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return s / v.length;
    }

    private static String text(DataNode n) {
        return n == null || n.isNull() ? "" : n.asText().trim();
    }

    private static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
