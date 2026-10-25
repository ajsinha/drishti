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
package com.ash.drishti.compute.reactive.samples;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.compute.ComputeEngine;
import com.ash.drishti.api.compute.ComputeEvent;
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
import java.util.Optional;
import java.util.Set;

/**
 * The Java twin of the Python sample engine's {@code sum-vectors} (REACTIVE_CONNECTORS.md, the sample reactive connector): the
 * element-wise sum of a vector field (a P&amp;L path) over every record of an input, as a scope's path is the sum of its members'. It is
 * Drishti's own, so it may run in the server's process. Parameters: {@code input} (default {@code values}), {@code column} (default
 * {@code PNL}), {@code delay-ms} (a pause per tenth of the rows, to show progress on a screen). Result: one row ({@code ROWS},
 * {@code WIDTH}, {@code SUM}, {@code WORST}, {@code BEST}) and the table {@code total} (one row per scenario: {@code DAY}, {@code PNL}).
 * Incremental: an event whose payload carries {@code delta} (a list of numbers) is added to the previous total.
 */
public final class VectorSumEngine implements ComputeEngine {

    @Override
    public EngineManifest manifest() {
        return new EngineManifest("vector-sum", "1.0", List.of("sum-vectors"), Set.of("sum-vectors"));
    }

    @Override
    public EngineResult compute(EngineRequest req, EngineInputs inputs, EngineProgress progress) throws Exception {
        String input = String.valueOf(req.param("input", "values"));
        String column = String.valueOf(req.param("column", "PNL"));
        long delay = Long.parseLong(String.valueOf(req.param("delay-ms", 0)));
        List<DataNode> rows = inputs.documents(input);
        double[] total = null;
        int used = 0;
        int step = Math.max(1, rows.size() / 10);
        for (int r = 0; r < rows.size(); r++) {
            DataNode v = rows.get(r).get(column);
            if (v instanceof DataNode.Arr a && a.size() > 0) {
                if (total == null) {
                    total = new double[a.size()];
                }
                if (a.size() != total.length) {
                    throw new EngineException(column + " has " + a.size() + " values in one record and " + total.length + " in another");
                }
                for (int k = 0; k < total.length; k++) {
                    double x = a.get(k).asDouble();
                    total[k] += Double.isNaN(x) ? 0 : x;
                }
                used++;
            }
            if ((r + 1) % step == 0) {
                progress.partial(Map.of("done", r + 1, "of", rows.size(), "estimate", Map.of("SUM", sum(total))));
                if (delay > 0) {
                    Thread.sleep(delay);
                }
            }
        }
        if (total == null) {
            throw new EngineException("no record of " + input + " has a " + column + " path to add up");
        }
        return result(total, used);
    }

    private static EngineResult result(double[] total, int used) {
        List<Map<String, Object>> table = new ArrayList<>(total.length);
        double worst = Double.POSITIVE_INFINITY;
        double best = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < total.length; k++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("DAY", k + 1);
            m.put("PNL", total[k]);
            table.add(m);
            worst = Math.min(worst, total[k]);
            best = Math.max(best, total[k]);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ROWS", used);
        row.put("WIDTH", total.length);
        row.put("SUM", sum(total));
        row.put("WORST", worst);
        row.put("BEST", best);
        return new EngineResult(List.of(row), Map.of("total", table), Map.of("engine", "vector-sum"));
    }

    private static double sum(double[] v) {
        double s = 0;
        if (v != null) {
            for (double x : v) {
                s += x;
            }
        }
        return s;
    }

    @Override
    public Optional<EngineResult> incremental(EngineRequest req, EngineResult previous, List<ComputeEvent> events, EngineInputs inputs) {
        List<Map<String, Object>> t = previous.tables().get("total");
        if (t == null || previous.rows().isEmpty()) {
            return Optional.empty();
        }
        double[] total = new double[t.size()];
        for (int k = 0; k < total.length; k++) {
            total[k] = ((Number) t.get(k).get("PNL")).doubleValue();
        }
        for (ComputeEvent e : events) {
            if (!(e.payload().get("delta") instanceof List<?> d) || d.size() != total.length) {
                return Optional.empty();                               // not an event it can apply: compute again
            }
            for (int k = 0; k < total.length; k++) {
                total[k] += ((Number) d.get(k)).doubleValue();
            }
        }
        return Optional.of(result(total, ((Number) previous.rows().get(0).get("ROWS")).intValue()));
    }

    /** {@code same-id} affects the answer whose id is the event's entity. */
    @Override
    public Set<String> affected(ComputeEvent event, String expression) {
        return "same-id".equals(expression) && !event.entity().isBlank() ? Set.of(event.entity()) : Set.of();
    }
}
