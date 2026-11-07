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
package com.ash.drishti.server.bi.engine;

import com.ash.drishti.server.bi.Layered;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.UserAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The history of a live table: its measures sampled on a timer into a bounded, time-indexed table that is queried like any table of the dataset
 * ({@code history.ts}, the groups, the sampled values) and charted by the generated {@code <measure> history} measures. Masks follow the underlying columns.
 */
class LiveHistoryTest {

    private static final String LIVE = "\n    live: { rows: 100, history: { every: 1s, keep: 3, by: [desk], measures: [MTM, Trades] } }";

    @TempDir Path store;
    LiveTablesTest.LiveLake lake;
    LiveTablesTest.TestClock clock;
    QueryService service;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lake = new LiveTablesTest.LiveLake();
        clock = new LiveTablesTest.TestClock();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    QueryService.Loaded start() {
        return start(LIVE);
    }

    QueryService.Loaded start(String liveSettings) {
        QueryProperties props = QueryProperties.defaults(store.toString());
        BiLiveProperties live = BiLiveProperties.defaults().fast(Duration.ofMillis(20), Duration.ofHours(1));
        service = new QueryService(props, live, new DuckDbEngine(props), lake, clock);
        String yaml = TestLake.yaml().replace("source: { connector: trading-lake, kind: trade }", "source: { connector: trading-lake, kind: trade }\n    mode: live" + liveSettings);
        DatasetParser.Result r = Layered.dataset(yaml);
        assertThat(r.problems().stream().filter(DatasetParser.Problem::error).toList()).isEmpty();
        return new QueryService.Loaded("file:dataset:live-test", Layers.key("file:dataset:live-test"), r.dataset(), "h1");
    }

    QueryService.Caller who(boolean raw, String desk, String... roles) {
        SqlCompiler.Masks masks = (t, c) -> !raw && t.equals("trades") && c.equals("mtm");
        return new QueryService.Caller("u", Set.of(roles), true, UserAttributes.of("u", List.of(roles), desk, null), masks);
    }

    QueryService.Result run(QueryService.Loaded ds, String body, QueryService.Caller who) {
        try {
            return service.query(ds, QueryRequest.of(json.readTree(body)), who, false);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void theModelGetsAHistoryTableAndGeneratedMeasures() {
        QueryService.Loaded ds = start();
        var h = ds.dataset().tables().get("history");
        assertThat(h).isNotNull();
        assertThat(h.isHistory()).isTrue();
        assertThat(h.of()).isEqualTo("trades");
        assertThat(h.columns().keySet()).containsExactly("ts", "desk", "MTM", "Trades");
        assertThat(h.columns().get("desk").type()).isEqualTo("string");
        assertThat(ds.dataset().measures()).containsKeys("MTM history", "Trades history");
        assertThat(ds.dataset().measures().get("MTM history").expr()).isEqualTo("sum(history.MTM)");
    }

    @Test
    void samplesAccumulateMatchTheLiveTableAndStayBounded() throws Exception {
        QueryService.Loaded ds = start();
        QueryService.Caller risk = who(true, "", "risk");
        double now = ((Number) run(ds, "{\"measures\":[\"MTM\"]}", risk).columns().get(0).values()[0]).doubleValue();
        LiveTablesTest.eventually(() -> {
            clock.advance(Duration.ofSeconds(1));                                               // the test clock stamps the samples: let it move
            return run(ds, "{\"fields\":[\"history.ts\"],\"measures\":[\"MTM history\"]}", risk).rows() >= 2;
        });
        QueryService.Result r = run(ds, "{\"fields\":[\"history.ts\"],\"measures\":[\"MTM history\",\"Trades history\"]}", risk);
        for (int i = 0; i < r.rows(); i++) {
            assertThat(((Number) r.columns().get(1).values()[i]).doubleValue()).as("the sum of the groups at each sample is the live table's total").isEqualTo(now);
            assertThat(((Number) r.columns().get(2).values()[i]).doubleValue()).isEqualTo(20.0);
        }
        // by group: one row per desk per sample
        QueryService.Result byDesk = run(ds, "{\"fields\":[\"history.ts\",\"history.desk\"],\"measures\":[\"Trades history\"]}", risk);
        assertThat(byDesk.rows()).isEqualTo(r.rows() * 3);
        // bounded: only the newest 3 samples are ever held
        for (int i = 0; i < 40; i++) {
            Thread.sleep(100);
            clock.advance(Duration.ofSeconds(1));
        }
        assertThat(run(ds, "{\"fields\":[\"history.ts\"],\"measures\":[\"MTM history\"]}", risk).rows()).isEqualTo(3);
        LiveTable t = service.live().find(ds.key(), "trades").orElseThrow();
        assertThat(t.history().orElseThrow().samples()).isGreaterThan(3);
        assertThat(service.live().tick(ds.key()).toString()).contains("history").contains("keep=3");
    }

    @Test
    void aMeasureMaskedForTheReaderIsMaskedInItsHistory() throws Exception {
        QueryService.Loaded ds = start();
        QueryService.Caller risk = who(true, "", "risk");
        run(ds, "{\"measures\":[\"Trades\"]}", risk);
        LiveTablesTest.eventually(() -> run(ds, "{\"fields\":[\"history.ts\"],\"measures\":[\"MTM history\"]}", risk).rows() >= 1);
        QueryService.Result masked = run(ds, "{\"fields\":[\"history.ts\"],\"measures\":[\"MTM history\",\"Trades history\"]}", who(false, "", "risk"));
        assertThat(masked.columns().get(1).values()).containsOnlyNulls();                       // MTM reads trades.mtm, which is masked for this reader
        assertThat(((Number) masked.columns().get(2).values()[0]).doubleValue()).isEqualTo(20.0);
    }

    @Test
    void aRowRuleOnTheLiveTableLimitsTheHistoryToTheGroupsTheReaderSeesNeverShowingEverything() throws Exception {
        QueryService.Loaded ds = start();
        QueryService.Caller risk = who(true, "", "risk");
        run(ds, "{\"measures\":[\"Trades\"]}", risk);
        String byDesk = "{\"fields\":[\"history.ts\",\"history.desk\"],\"measures\":[\"Trades history\"]}";
        LiveTablesTest.eventually(() -> {
            clock.advance(Duration.ofSeconds(1));
            return run(ds, byDesk, risk).rows() >= 3;
        });
        // the desk-head's rule on the live trades reaches its history (BI_DESIGN.md 8.5, the owner's decision of 2026-11-07): her desk's samples only
        QueryService.Result mine = run(ds, byDesk, who(true, "DESK-A", "desk-head"));
        assertThat(mine.rows()).isPositive();
        assertThat(mine.columns().get(1).values()).containsOnly("DESK-A");
        // a desk-head with no desks sees no samples; a total over the samples is hers alone, never everyone's
        assertThat(run(ds, byDesk, who(true, "", "desk-head")).rows()).isZero();
        assertThat(run(ds, "{\"fields\":[\"history.desk\"],\"measures\":[\"Trades history\"]}", who(true, "DESK-A", "desk-head")).columns().get(0).values())
                .containsOnly("DESK-A");
    }

    @Test
    void aHistoryWithNoGroupsCannotHonourTheRuleAndRefusesRatherThanShowingEverything() {
        QueryService.Loaded ds = start(LIVE.replace("by: [desk]", "by: []"));
        run(ds, "{\"measures\":[\"Trades\"]}", who(true, "", "risk"));
        assertThatThrownBy(() -> run(ds, "{\"fields\":[\"history.ts\"],\"measures\":[\"Trades history\"]}", who(true, "DESK-A", "desk-head")))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("related_rows");
    }
}
