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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.engine.DuckDbEngine;
import com.ash.drishti.server.bi.engine.ExecProperties;
import com.ash.drishti.server.bi.engine.Footprints;
import com.ash.drishti.server.bi.engine.Layers;
import com.ash.drishti.server.bi.engine.QueryProperties;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.engine.TestLake;
import com.ash.drishti.server.bi.engine.SqlCompiler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The planner over the engine's small lake (docs/architecture/BI_RUNS.md sections 4, 5 and 19.1): tiers from the estimate, confirmation and refusal
 * at plan time, the basis of each estimate, and the rule that <b>an estimate reads no data</b> (counted connector reads and builds are 0). Thresholds
 * are scaled to the lake (40 trades a day) so every tier is reached without heavy data.
 */
class RunPlannerTest {

    /** interactive: at most 50 rows read and 1 date to build; ask first above 60 rows; the local engine reads at most 1,000. */
    static final PlannerSettings SMALL = new PlannerSettings(10, 50, 1, 60, 60, true, 1_000, 0.5, 2.0, 20, 50_000_000, 300_000, 2_000, 0.05, 32);

    @TempDir Path store;
    TestLake lake;
    QueryService service;
    QueryService.Loaded ds;
    RunHistory history;
    final ObjectMapper json = new ObjectMapper();
    final QueryService.Caller risk = new QueryService.Caller("rita", Set.of("risk"), true, Map.of(), SqlCompiler.Masks.NONE);

    @BeforeEach
    void setUp() {
        lake = new TestLake(true);
        QueryProperties props = QueryProperties.defaults(store.toString());
        service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC());
        history = new RunHistory();
        service.history(history);
        ds = new QueryService.Loaded("file:dataset:lake-test", Layers.key("file:dataset:lake-test"), DatasetParser.parse(TestLake.yaml()).dataset(), "h1");
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    QueryRequest q(String body) {
        try {
            return QueryRequest.of(json.readTree(body));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    static final String ONE_DAY = "{\"fields\":[\"trades.currency\"],\"measures\":[\"Names\"],\"dates\":{\"from\":\"2026-10-09\"}}";
    static final String TWO_DAYS = "{\"fields\":[\"trades.currency\"],\"measures\":[\"Names\"],\"dates\":{\"from\":\"2026-10-08\",\"to\":\"2026-10-09\"}}";
    static final String TWO_DAYS_BY_DESK = "{\"fields\":[\"trades.desk\"],\"measures\":[\"Notional\"],\"dates\":{\"from\":\"2026-10-08\",\"to\":\"2026-10-09\"}}";

    RunPlanner.Planned plan(String body, boolean interaction, PlannerSettings s) {
        Footprints.Footprint f = service.footprint(ds, q(body), risk);
        return new RunPlanner(s, new RunRates(s)).plan(f, interaction);
    }

    long storeFiles() throws Exception {
        try (var w = Files.walk(store)) {
            return w.filter(Files::isRegularFile).count();
        }
    }

    void counts() {
        lake.rowCounts.put(TestLake.DAY1, 40L);
        lake.rowCounts.put(TestLake.DAY2, 40L);
    }

    @Test
    void oneDateIsInteractiveFromTheSourcesStatisticsAndReadsNothing() throws Exception {
        counts();
        RunPlanner.Planned p = plan(ONE_DAY, false, SMALL);
        assertThat(p.tier()).isEqualTo(Tier.INTERACTIVE);
        assertThat(p.estimate().basis()).isEqualTo(RunEstimate.Basis.DELTA_STATS);
        assertThat(p.estimate().readRows()).isEqualTo(40);
        assertThat(p.estimate().datesToBuild()).isEqualTo(1);
        assertThat(p.confirm()).isFalse();
        assertThat(p.refusal()).isEmpty();
        // an estimate reads no data: no scan, no record read, nothing built
        assertThat(lake.scans.get()).isZero();
        assertThat(lake.recordReads.get()).isZero();
        assertThat(storeFiles()).isZero();
    }

    @Test
    void builtPartitionsGiveTheLayersBasisAndTeachTheHistory() {
        service.query(ds, q(ONE_DAY), risk, false);
        int scans = lake.scans.get();
        RunPlanner.Planned p = plan(ONE_DAY, false, SMALL);
        assertThat(p.estimate().basis()).isEqualTo(RunEstimate.Basis.LAYERS);
        assertThat(p.estimate().readRows()).isEqualTo(40);
        assertThat(p.estimate().datesToBuild()).isZero();
        assertThat(lake.scans.get()).isEqualTo(scans);
        // the connector cannot count (no rowCounts): the other date is sized from the learnt rows per day
        RunPlanner.Planned two = plan(TWO_DAYS, false, SMALL);
        assertThat(two.estimate().basis()).isEqualTo(RunEstimate.Basis.HISTORY);
        assertThat(two.estimate().readRows()).isEqualTo(80);
    }

    @Test
    void manyDatesAtAGrainNoRollupHasAreAdHocHeavyAndAskFirst() {
        counts();
        RunPlanner.Planned p = plan(TWO_DAYS, false, SMALL);
        assertThat(p.tier()).isEqualTo(Tier.AD_HOC_HEAVY);
        assertThat(p.estimate().readRows()).isEqualTo(80);
        assertThat(p.confirm()).isTrue();                                       // 80 rows > confirm.above-rows 60
        assertThat(p.estimate().why()).contains("2 business dates of trades");
        // under the confirm threshold it runs in the background without asking
        PlannerSettings noAsk = new PlannerSettings(10, 50, 1, 60, 1_000, true, 1_000, 0.5, 2.0, 20, 50_000_000, 300_000, 2_000, 0.05, 32);
        assertThat(plan(TWO_DAYS, false, noAsk).confirm()).isFalse();
        assertThat(lake.scans.get()).isZero();
    }

    @Test
    void anUnknownSizeIsAdHocHeavyAndAsksFirst() {
        RunPlanner.Planned p = plan(ONE_DAY, false, SMALL);
        assertThat(p.estimate().basis()).isEqualTo(RunEstimate.Basis.UNKNOWN);
        assertThat(p.estimate().readRows()).isNull();
        assertThat(p.tier()).isEqualTo(Tier.AD_HOC_HEAVY);
        assertThat(p.confirm()).isTrue();
    }

    @Test
    void pastTheLocalEnginesLimitItIsRefusedAndAnInteractionIsNeverAJob() {
        counts();
        PlannerSettings tiny = new PlannerSettings(10, 50, 1, 60, 60, true, 70, 0.5, 2.0, 20, 50_000_000, 300_000, 2_000, 0.05, 32);
        assertThat(plan(TWO_DAYS, false, tiny).refusal()).contains(ErrorCode.RUN_TOO_LARGE);
        assertThat(plan(TWO_DAYS, true, SMALL).refusal()).contains(ErrorCode.RUN_NEEDED);
        assertThat(plan(ONE_DAY, true, SMALL).refusal()).isEmpty();
    }

    @Test
    void aQuestionTheRollupCoversIsKnownHeavy() {
        service.query(ds, q(TWO_DAYS_BY_DESK), risk, false);                    // builds both dates and the byDesk rollup
        RunPlanner.Planned p = plan(TWO_DAYS_BY_DESK, false, SMALL);
        assertThat(p.estimate().covered()).isTrue();
        assertThat(p.tier()).isEqualTo(Tier.KNOWN_HEAVY);                       // 80 raw rows > 50, answered from the rollup's rows
        assertThat(p.estimate().readRows()).isLessThan(80);
        assertThat(p.estimate().why()).contains("answered from rollup byDesk");
    }

    @Test
    void theDataVersionVectorNamesEveryDateAndMovesWhenADateIsRestated() {
        assertThat(plan(TWO_DAYS, false, SMALL).data().complete()).isFalse();   // the connector cannot tell
        lake.versions.put(TestLake.DAY1, "v1");
        lake.versions.put(TestLake.DAY2, "v1");
        DataVersionVector a = plan(TWO_DAYS, false, SMALL).data();
        assertThat(a.complete()).isTrue();
        assertThat(a.parts()).hasSize(2);
        assertThat(plan(TWO_DAYS, false, SMALL).data().hash()).isEqualTo(a.hash());
        lake.versions.put(TestLake.DAY2, "v2");
        assertThat(plan(TWO_DAYS, false, SMALL).data().hash()).isNotEqualTo(a.hash());
        assertThat(plan(ONE_DAY, false, SMALL).data().hash()).isNotEqualTo(a.hash());
    }

    @Test
    void theSynchronousCostRefusalComesBeforeAnyBuild() throws Exception {
        counts();
        service.exec(new ExecProperties(null, Duration.ofMillis(2000), null, null, 50L, null, null, null, null, null, null, null, null, null));
        assertThatThrownBy(() -> service.query(ds, q(TWO_DAYS), risk, false)).isInstanceOf(DrishtiException.class)
                .extracting(e -> ((DrishtiException) e).errorCode()).isEqualTo(ErrorCode.QUERY_COST);
        assertThat(lake.scans.get()).isZero();                                  // refused from the metadata: nothing was read or built
        assertThat(storeFiles()).isZero();
        assertThat(service.query(ds, q(ONE_DAY), risk, false).rows()).isPositive();   // 40 rows fit the budget of 50
    }
}
