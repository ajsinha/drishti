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
package com.ash.drishti.server.bi.runs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What {@code GET /api/v1/bi/admin/runs} answers (docs/architecture/BI_RUNS.md section 17.2; the shape the console's Admin → BI → Runs page reads,
 * section 14.5): the slots now by class, the queue, the store, how honest the estimates are, how often the inline wait was enough, the settings,
 * the nodes and the rollup suggestions. A part not built yet is null. State, sizes and counts only: never a value of any result.
 */
final class RunAdmin {

    private RunAdmin() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> of(RunService runs, Map<String, Object> admission, Double admissionWaitP95Ms, List<Map<String, Object>> running) {
        RunsProperties props = runs.properties();
        Map<String, Object> raw = runs.admin();
        Map<String, Object> m = new LinkedHashMap<>();

        Map<String, Object> now = new LinkedHashMap<>();
        Map<String, Object> slots = new LinkedHashMap<>();
        Map<String, Object> byClass = admission == null ? Map.of() : (Map<String, Object>) admission.getOrDefault("byClass", Map.of());
        for (String c : List.of("interactive", "standing", "background")) {
            Object v = byClass.get(c);
            if (v instanceof Map<?, ?> x) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("used", x.get("running"));
                s.put("cap", x.get("cap"));
                slots.put(c, s);
            } else {
                slots.put(c, null);
            }
        }
        now.put("slots", slots);
        now.put("admissionWaitP95Ms", admissionWaitP95Ms);
        m.put("now", now);

        Map<String, Object> q = (Map<String, Object>) raw.get("queue");
        Map<String, Object> queue = new LinkedHashMap<>();
        queue.put("byClass", q.get("byClass"));
        queue.put("byUser", q.get("byUser"));
        queue.put("oldestSeconds", q.get("oldestSeconds"));
        queue.put("oldestUser", q.get("oldestUser"));
        queue.put("queued", q.get("queued"));
        queue.put("workers", q.get("workers"));
        m.put("queue", queue);
        m.put("running", running);

        Map<String, Object> usage = runs.store().usage();
        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("resultsBytes", usage.get("bytes"));
        storage.put("maxBytes", props.store().maxBytes());
        storage.put("results", usage.get("results"));
        storage.put("oldest", usage.get("oldest"));
        storage.put("snapshotsBytes", null);
        storage.put("snapshots", null);
        storage.put("topUsers", usage.get("topUsers"));
        m.put("storage", storage);

        RunPlanner planner = runs.planner();
        List<Double> ratios = planner == null ? List.of() : new ArrayList<>(planner.rates().ratios(RunPlanner.LOCAL));
        Map<String, Object> accuracy = new LinkedHashMap<>();
        if (ratios.isEmpty()) {
            accuracy.put("medianRatio", null);
            accuracy.put("p10", null);
            accuracy.put("p90", null);
        } else {
            ratios.sort(Double::compare);
            accuracy.put("medianRatio", round(RunEstimator.percentile(ratios, 0.5)));
            accuracy.put("p10", round(RunEstimator.percentile(ratios, 0.1)));
            accuracy.put("p90", round(RunEstimator.percentile(ratios, 0.9)));
        }
        accuracy.put("samples", ratios.size());
        accuracy.put("basis", null);
        long planned = runs.stats.planned.get();
        accuracy.put("unknownShare", planned == 0 ? null : round((double) runs.stats.unknown.get() / planned));
        m.put("accuracy", accuracy);

        Map<String, Object> inline = new LinkedHashMap<>();
        long in = runs.stats.inlined.get();
        long jobs = runs.stats.jobs.get();
        inline.put("inlineShare", in + jobs == 0 ? null : round((double) in / (in + jobs)));
        List<Double> js = new ArrayList<>(runs.stats.jobSeconds());
        js.sort(Double::compare);
        inline.put("jobMedianSeconds", js.isEmpty() ? null : round(RunEstimator.percentile(js, 0.5)));
        m.put("inline", inline);

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("file", String.valueOf(props.origin()));
        settings.put("inlineWait", props.inline().inlineWait().toString());
        settings.put("confirmAboveSeconds", props.planner().confirmAboveSeconds());
        settings.put("retentionDays", props.retention().days());
        m.put("settings", settings);

        m.put("nodes", List.of(Map.of("node", runs.node(), "alive", true)));
        m.put("suggestions", null);                                            // rollup suggestions from repeated heavy runs: not built yet (8.3a)
        return m;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
