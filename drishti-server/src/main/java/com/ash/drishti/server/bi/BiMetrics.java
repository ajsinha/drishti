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
package com.ash.drishti.server.bi;

import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.mq.MqService;
import com.ash.drishti.server.bi.mq.StandingQueries;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The BI metrics (docs/architecture/BI_DESIGN.md section 6.8), through Micrometer to Prometheus ({@code /actuator/prometheus}):
 * {@code drishti_bi_query_seconds{model,layer}}, {@code drishti_bi_query_rows}, {@code drishti_bi_cache_requests_total{result}},
 * {@code drishti_bi_admission_wait_seconds}, {@code drishti_bi_refusals_total{code}}, {@code drishti_bi_fragment_seconds{tier,connector}},
 * {@code drishti_bi_sq_active}, {@code drishti_bi_sq_lag_seconds}, {@code drishti_bi_sq_mode{mode}}, {@code drishti_bi_python_seconds{item}},
 * {@code drishti_bi_layer_rebuild_seconds}. Model tags are the model's layer key (one per published model version: bounded).
 */
public final class BiMetrics implements QueryService.Events {

    private final MeterRegistry registry;

    public BiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Registers the meters and becomes the engine's, the model query's and the standing queries' listener. */
    public static BiMetrics attach(MeterRegistry registry, QueryService queries, MqService mq, StandingQueries standing) {
        BiMetrics m = new BiMetrics(registry);
        if (queries != null) {
            queries.events(m);
        }
        if (mq != null) {
            FunctionCounter.builder("drishti.bi.cache.requests", mq, x -> x.results().hits()).tag("result", "hit").description("model query answers served from the L1 cache")
                    .register(registry);
            FunctionCounter.builder("drishti.bi.cache.requests", mq, x -> x.results().misses()).tag("result", "miss").register(registry);
            mq.pythonSeconds((item, seconds) -> Timer.builder("drishti.bi.python").tag("item", item).description("Python node calls on the console").register(registry)
                    .record(Duration.ofNanos((long) (seconds * 1e9))));
        }
        if (standing != null) {
            Gauge.builder("drishti.bi.sq.active", standing, s -> ((Number) s.stats().get("subscriptions")).doubleValue()).description("open standing queries").register(registry);
            Gauge.builder("drishti.bi.sq.lag.seconds", standing, StandingQueries::maxLagSeconds).description("the longest a group took from a change to its answer").register(registry);
            for (String mode : List.of("incremental", "recompute", "tick")) {
                Gauge.builder("drishti.bi.sq.mode", standing, s -> modeCount(s, mode)).tag("mode", mode).description("standing-query groups by how they are kept").register(registry);
            }
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static double modeCount(StandingQueries s, String mode) {
        Object modes = s.stats().get("modes");
        return modes instanceof Map<?, ?> m && m.get(mode) instanceof Number n ? n.doubleValue() : 0;
    }

    @Override
    public void query(String model, String layer, double seconds, int rows) {
        Timer.builder("drishti.bi.query").tag("model", model).tag("layer", layer == null ? "" : layer).description("BI queries run by the engine")
                .publishPercentiles(0.5, 0.95, 0.99).register(registry).record(Duration.ofNanos((long) (seconds * 1e9)));
        DistributionSummary.builder("drishti.bi.query.rows").description("rows a BI query answered").register(registry).record(rows);
    }

    @Override
    public void admission(double seconds) {
        Timer.builder("drishti.bi.admission.wait").description("how long queries waited for a place").register(registry).record(Duration.ofNanos((long) (seconds * 1e9)));
    }

    @Override
    public void refusal(String code) {
        Counter.builder("drishti.bi.refusals").tag("code", code).description("BI queries refused, by code").register(registry).increment();
    }

    @Override
    public void fragment(String connector, String tier, double seconds) {
        Timer.builder("drishti.bi.fragment").tag("tier", tier).tag("connector", connector).description("source fragments by connector").register(registry)
                .record(Duration.ofNanos((long) (seconds * 1e9)));
    }

    @Override
    public void rebuild(double seconds) {
        Timer.builder("drishti.bi.layer.rebuild").description("layer rebuilds (refresh, loads)").register(registry).record(Duration.ofNanos((long) (seconds * 1e9)));
    }
}
