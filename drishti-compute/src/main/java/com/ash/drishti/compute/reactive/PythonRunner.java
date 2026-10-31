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
package com.ash.drishti.compute.reactive;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.compute.EngineInputs;
import com.ash.drishti.api.compute.EngineProgress;
import com.ash.drishti.api.compute.EngineRequest;
import com.ash.drishti.api.compute.EngineResult;
import com.ash.drishti.compute.ComputeException;
import com.ash.drishti.compute.ComputeHandle;
import com.ash.drishti.compute.ComputeRequest;
import com.ash.drishti.compute.ComputeResult;
import com.ash.drishti.compute.ComputeService;
import com.ash.drishti.compute.RequestClass;
import com.ash.drishti.compute.arrow.ArrowTable;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The worker bridge (DATA_ARCHITECTURE.md › Reactive connectors, section 18, R2): a Python engine run in a pool of {@code config/compute/compute.yaml}. The
 * inputs, already read and masked as the viewer, go to the worker as Arrow tables; the result comes back as rows and tables. With
 * {@code engine.split} the request is cut by that input into chunks spread over every worker of the pool on this node and on the other
 * Drishti*net nodes that serve it ({@link ComputeService#fanOut}): the number of workers is configuration, never assumed; the chunks'
 * answers are gathered in order and, with {@code engine.reduce}, added up into one.
 */
public final class PythonRunner implements EngineRunner {

    private final Supplier<ComputeService> compute;
    private final ReactiveSpec.EngineSpec spec;
    private final Reduce reduce;

    public PythonRunner(Supplier<ComputeService> compute, ReactiveSpec.EngineSpec spec, Reduce reduce) {
        this.compute = compute;
        this.spec = spec;
        this.reduce = reduce;
    }

    @Override
    public String identity() {
        return "python:" + spec.entry() + "@" + spec.version() + "/" + spec.pool();
    }

    @Override
    public Optional<String> unavailable() {
        ComputeService c = compute.get();
        if (c == null) {
            return Optional.of("this server has no compute workers");
        }
        if (!c.enabled()) {
            return Optional.of("compute is off on this server (compute.enabled)");
        }
        if (!c.settings().pools().containsKey(spec.pool())) {
            return Optional.of("there is no compute pool '" + spec.pool() + "' (config/compute/compute.yaml)");
        }
        return c.ready(c.settings().pool(spec.pool())).map(w -> "compute pool " + spec.pool() + ": " + w);
    }

    @Override
    public Running start(EngineRequest request, EngineInputs inputs, RequestClass cls, Duration budget, EngineProgress progress) {
        ComputeService c = compute.get();
        if (c == null) {
            throw new ComputeException(ComputeException.Code.OFF, "this server has no compute workers");
        }
        ComputeRequest.Builder b = ComputeRequest.on(spec.pool(), request.kind(), request.viewer().user())
                .id(request.requestId())
                .engine(spec.entry())
                .viewer(new ComputeRequest.Viewer(request.viewer().user(), request.viewer().roles(), request.viewer().viewSignature()))
                .cls(cls)
                .timeout(budget)
                .seed(request.seed());
        request.params().forEach((k, v) -> b.param(k, plain(v)));
        b.param("id", request.id());
        b.param("businessDate", request.asOf().businessDate() == null ? null : request.asOf().businessDate().toString());
        for (Map.Entry<String, List<DataNode>> e : inputs.asMap().entrySet()) {
            b.input(e.getKey(), ArrowRows.table(e.getValue()));
        }
        ComputeRequest req = b.build();
        ComputeHandle h = spec.split() != null && inputs.names().contains(spec.split())
                ? c.fanOut(req, spec.split(), spec.chunkRows(), (id, data) -> progress.partial(data))
                : c.submit(req, (id, data) -> progress.partial(data));
        return new Running(h.result().thenApply(this::result), h::cancel);
    }

    private static Object plain(Object v) {
        if (v instanceof BigDecimal d) {
            return d.scale() <= 0 ? (Object) d.longValue() : (Object) d.doubleValue();
        }
        return v;
    }

    private EngineResult result(ComputeResult r) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : r.rows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            row.forEach((k, v) -> m.put(k, ArrowRows.plain(v)));
            rows.add(m);
        }
        Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
        for (Map.Entry<String, ArrowTable> t : r.tables().entrySet()) {
            tables.put(t.getKey(), ArrowRows.rows(t.getValue()));
        }
        Map<String, Object> meta = new LinkedHashMap<>(r.meta());
        meta.put("worker", r.worker());
        meta.put("node", r.node());
        meta.put("queuedMs", r.queuedMs());
        meta.put("runMs", r.runMs());
        EngineResult out = new EngineResult(rows, tables, meta);
        return reduce == null ? out : reduce.apply(out);
    }
}
