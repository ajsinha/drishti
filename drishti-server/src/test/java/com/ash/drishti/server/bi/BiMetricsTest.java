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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.UserAttributes;
import com.ash.drishti.server.bi.engine.DuckDbEngine;
import com.ash.drishti.server.bi.engine.Layers;
import com.ash.drishti.server.bi.engine.QueryContext;
import com.ash.drishti.server.bi.engine.QueryProperties;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.engine.SqlCompiler;
import com.ash.drishti.server.bi.engine.TestLake;
import com.ash.drishti.server.bi.mq.MqProperties;
import com.ash.drishti.server.bi.mq.MqService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The metrics of section 6.8 are registered and move: query time and rows by model and layer, admission waits, refusals by code, cache requests. */
class BiMetricsTest {

    @TempDir Path store;

    @Test
    void aQueryAndARefusalMoveTheirMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueryProperties props = QueryProperties.defaults(store.toString());
        try (QueryService service = new QueryService(props, new DuckDbEngine(props), new TestLake(true), Clock.systemUTC())) {
            BiMetrics.attach(registry, service, new MqService(MqProperties.defaults()), null);
            QueryService.Loaded ds = new QueryService.Loaded("file:dataset:m", Layers.key("file:dataset:m"), DatasetParser.parse(TestLake.yaml()).dataset(), "h");
            QueryService.Caller rita = new QueryService.Caller("rita", Set.of("risk"), true, UserAttributes.of("rita", List.of("risk"), "", null), SqlCompiler.Masks.NONE);
            QueryRequest q = new QueryRequest(List.of("trades.desk"), List.of("Notional"), List.of(), TestLake.DAY2, TestLake.DAY2, List.of(), null, "columns");
            service.query(ds, q, rita, false);
            assertThatThrownBy(() -> QueryContext.with(new QueryContext.Ctx(QueryContext.WorkClass.INTERACTIVE, 1L, null),
                    () -> service.query(ds, new QueryRequest(List.of("trades.cpty"), List.of("Notional"), List.of(), TestLake.DAY2, TestLake.DAY2, List.of(), null, "columns"),
                            rita, false))).hasMessageContaining("DRS-9029");
        }
        assertThat(registry.find("drishti.bi.query").tag("layer", "columns").timer()).isNotNull();
        assertThat(registry.find("drishti.bi.query").timer().count()).isEqualTo(1);
        assertThat(registry.find("drishti.bi.query.rows").summary().totalAmount()).isEqualTo(3);
        assertThat(registry.find("drishti.bi.admission.wait").timer().count()).isEqualTo(2);
        assertThat(registry.find("drishti.bi.refusals").tag("code", "DRS-9029").counter().count()).isEqualTo(1);
        assertThat(registry.find("drishti.bi.cache.requests").tag("result", "hit").functionCounter()).isNotNull();
    }
}
