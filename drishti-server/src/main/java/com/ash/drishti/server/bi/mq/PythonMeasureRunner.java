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
package com.ash.drishti.server.bi.mq;

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.plan.SecuritySignature;
import com.ash.drishti.server.bi.python.PythonItems;
import com.ash.drishti.server.bi.transform.TransformRunner;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Python measure of a model query run by the Python runner (the console's Py-node endpoint, or the process runner until it is set): the item as the
 * reader may run it (its manifest and parameters), its inputs by their column names, the rows grouped by the answer's fields (wave B, R8).
 */
final class PythonMeasureRunner implements MqService.PythonMeasures {

    private final PythonItems items;
    private final TransformRunner runner;

    PythonMeasureRunner(PythonItems items, TransformRunner runner) {
        this.items = items;
        this.runner = runner;
    }

    @Override
    @SuppressWarnings("unchecked")
    public JsonNode run(SecuritySignature.Reader reader, Dataset.Measure m, List<String> groupBy, List<String> columns, List<List<Object>> rows) {
        Principal p = new Principal(reader.user(), List.copyOf(reader.roles()));
        Map<String, Object> desc = items.describe(p, m.python(), true);
        Map<String, Object> manifest = new LinkedHashMap<>((Map<String, Object>) desc.get("manifest"));
        manifest.put("inputs", m.inputs().stream().map(i -> i.contains(".") ? i.substring(i.indexOf('.') + 1) : i).toList());
        Map<String, Object> params = new LinkedHashMap<>();
        if (desc.get("params") instanceof Map<?, ?> pm) {
            pm.forEach((k, v) -> params.put(String.valueOf(k), v));
        }
        return runner.measure(manifest, params, groupBy, columns, rows);
    }
}
