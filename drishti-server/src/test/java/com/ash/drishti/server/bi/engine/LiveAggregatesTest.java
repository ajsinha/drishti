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

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.UserAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Incremental live measures: the rollups of a live table are kept by group as rows arrive, change, move between groups, leave and are evicted, and every answer
 * read from them is compared with the same query computed over the live rows (layer {@code columns}), for sums, counts, averages, minimums and maximums.
 */
class LiveAggregatesTest {

    static final String MEASURES = "\"Notional\",\"MTM\",\"Trades\",\"AvgMTM\",\"Biggest\",\"MTMperM\"";

    @TempDir Path store;
    LiveTablesTest.LiveLake lake;
    QueryService service;
    final ObjectMapper json = new ObjectMapper();
    long generation = 100;

    @BeforeEach
    void setUp() {
        lake = new LiveTablesTest.LiveLake();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    QueryService.Loaded start(boolean incremental, String liveBlock) {
        QueryProperties props = QueryProperties.defaults(store.toString());
        BiLiveProperties live = BiLiveProperties.defaults().fast(Duration.ofMillis(20), Duration.ofHours(1)).withIncrementalAggregates(incremental);
        service = new QueryService(props, live, new DuckDbEngine(props), lake, new LiveTablesTest.TestClock());
        String yaml = TestLake.yaml().replace("source: { connector: trading-lake, kind: trade }", "source: { connector: trading-lake, kind: trade }\n    mode: live" + liveBlock);
        DatasetParser.Result r = DatasetParser.parse(yaml);
        assertThat(r.problems().stream().filter(DatasetParser.Problem::error).toList()).isEmpty();
        return new QueryService.Loaded("file:dataset:live-agg", Layers.key("file:dataset:live-agg"), r.dataset(), "h1");
    }

    QueryService.Caller who(boolean raw, String desk, String... roles) {
        SqlCompiler.Masks masks = (t, c) -> !raw && (c.equals("cpty") || t.equals("trades") && c.equals("mtm"));
        return new QueryService.Caller("rita", Set.of(roles), true, UserAttributes.of("rita", List.of(roles), desk, null), masks);
    }

    QueryService.Result run(QueryService.Loaded ds, String body, QueryService.Caller who) {
        try {
            return service.query(ds, QueryRequest.of(json.readTree(body)), who, false);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    String body(String layer, String measures) {
        return "{\"fields\":[\"trades.desk\"],\"measures\":[" + measures + "],\"orderBy\":[{\"by\":\"trades.desk\"}],\"layer\":\"" + layer + "\"}";
    }

    void add(LiveTable t, String id, String desk, double notional, double mtm) {
        lake.add(id, desk, notional, mtm);
        t.apply(List.of(lake.docs.get(EntityRef.of("trade", id))));
    }

    void delete(LiveTable t, String id) {
        EntityRef ref = EntityRef.of("trade", id);
        t.apply(List.of(EntityDocument.deleted(ref, new Provenance("test", ++generation, Instant.now(), true))));
        lake.docs.remove(ref);
    }

    /** The answer read from the kept aggregates equals the answer computed over the live rows. */
    void sameAsRecompute(QueryService.Loaded ds, QueryService.Caller who, String measures) {
        QueryService.Result kept = run(ds, body("auto", measures), who);
        QueryService.Result rows = run(ds, body("columns", measures), who);
        assertThat(kept.meta().rollup()).as("answered from the kept aggregates").isEqualTo("byDesk");
        assertThat(rows.meta().rollup()).isNull();
        assertThat(kept.rows()).isEqualTo(rows.rows());
        for (int c = 0; c < kept.columns().size(); c++) {
            for (int i = 0; i < kept.rows(); i++) {
                Object a = kept.columns().get(c).values()[i];
                Object b = rows.columns().get(c).values()[i];
                if (a instanceof Number x && b instanceof Number y) {
                    assertThat(x.doubleValue()).as("column " + c + " row " + i).isCloseTo(y.doubleValue(), org.assertj.core.data.Percentage.withPercentage(1e-9));
                } else {
                    assertThat(a).as("column " + c + " row " + i).isEqualTo(b);
                }
            }
        }
    }

    @Test
    void theFirstAnswerComesFromTheKeptAggregatesAndEqualsTheRecompute() {
        QueryService.Loaded ds = start(true, "");
        QueryService.Caller risk = who(true, "", "risk");
        sameAsRecompute(ds, risk, MEASURES);
        QueryService.Result r = run(ds, body("auto", MEASURES), risk);
        assertThat(r.meta().layer()).isEqualTo("live");
        LiveTable t = service.live().find(ds.key(), "trades").orElseThrow();
        assertThat(t.aggregateStats()).hasSize(1);
        assertThat((long) t.aggregateStats().get(0).get("groups")).isGreaterThanOrEqualTo(3L);
    }

    @Test
    void insertsReplacesMovesBetweenGroupsAndDeletesKeepEveryMeasureEqualToTheRecompute() {
        QueryService.Loaded ds = start(true, "");
        QueryService.Caller risk = who(true, "", "risk");
        run(ds, body("auto", MEASURES), risk);
        LiveTable t = service.live().find(ds.key(), "trades").orElseThrow();
        add(t, "N-1", "DESK-A", 5_000_000, 321.5);                       // a new row that is the new maximum of DESK-A
        sameAsRecompute(ds, risk, MEASURES);
        add(t, "N-1", "DESK-B", 5_000_000, 321.5);                       // the same row moves to DESK-B: DESK-A loses its maximum, DESK-B gains it
        sameAsRecompute(ds, risk, MEASURES);
        add(t, "N-1", "DESK-B", 10.0, -4000.25);                         // the maximum shrinks in place
        sameAsRecompute(ds, risk, MEASURES);
        delete(t, "N-1");
        sameAsRecompute(ds, risk, MEASURES);
        String first = lake.docs.keySet().stream().sorted(java.util.Comparator.comparing(EntityRef::id)).findFirst().orElseThrow().id();
        delete(t, first);
        sameAsRecompute(ds, risk, MEASURES);
        add(t, "N-2", "DESK-D", 77.0, 1.0);                              // a group that did not exist
        sameAsRecompute(ds, risk, MEASURES);
        delete(t, "N-2");                                                // and a group that empties and disappears
        sameAsRecompute(ds, risk, MEASURES);
        assertThat((long) t.aggregateStats().get(0).get("incremental")).isGreaterThan(0L);
        assertThat((long) t.aggregateStats().get(0).get("recomputedGroups")).isGreaterThan(0L);     // a group that lost its extreme was read again, alone
    }

    @Test
    void aRandomRunOfChangesStaysEqualToTheRecompute() {
        QueryService.Loaded ds = start(true, "");
        QueryService.Caller risk = who(true, "", "risk");
        run(ds, body("auto", MEASURES), risk);
        LiveTable t = service.live().find(ds.key(), "trades").orElseThrow();
        Random rnd = new Random(7);
        List<String> ids = new ArrayList<>(lake.docs.keySet().stream().map(EntityRef::id).toList());
        for (int step = 0; step < 60; step++) {
            int op = rnd.nextInt(4);
            if (op == 0 || ids.isEmpty()) {
                String id = "R-" + step;
                add(t, id, TestLake.DESKS.get(rnd.nextInt(3)), Math.round(rnd.nextDouble() * 1e6) / 100.0, Math.round((rnd.nextDouble() - 0.5) * 1e5) / 100.0);
                ids.add(id);
            } else if (op == 1) {
                add(t, ids.get(rnd.nextInt(ids.size())), TestLake.DESKS.get(rnd.nextInt(3)), Math.round(rnd.nextDouble() * 1e6) / 100.0, Math.round((rnd.nextDouble() - 0.5) * 1e5) / 100.0);
            } else if (op == 2 && ids.size() > 4) {
                delete(t, ids.remove(rnd.nextInt(ids.size())));
            } else {
                add(t, ids.get(rnd.nextInt(ids.size())), "DESK-A", 1000.0, 5.0);
            }
            if (step % 6 == 0) {
                sameAsRecompute(ds, risk, MEASURES);
            }
        }
        sameAsRecompute(ds, risk, MEASURES);
    }

    @Test
    void retentionEvictionTakesTheEvictedRowsOutOfTheirGroups() {
        QueryService.Loaded ds = start(true, "\n    live: { rows: 15 }");
        QueryService.Caller risk = who(true, "", "risk");
        run(ds, body("auto", MEASURES), risk);
        LiveTable t = service.live().find(ds.key(), "trades").orElseThrow();
        for (int i = 0; i < 12; i++) {
            add(t, "E-" + i, TestLake.DESKS.get(i % 3), 100.0 + i, i - 5.0);
        }
        assertThat(t.status().rows()).isLessThanOrEqualTo(15L);
        assertThat(t.status().evicted()).isGreaterThan(0L);
        sameAsRecompute(ds, risk, MEASURES);
    }

    @Test
    void theReadersRowRulesAndMasksApplyOnTopOfTheKeptAggregates() {
        QueryService.Loaded ds = start(true, "");
        QueryService.Caller head = who(true, "DESK-B", "desk-head");
        sameAsRecompute(ds, head, MEASURES);
        QueryService.Result own = run(ds, body("auto", MEASURES), head);
        assertThat(own.columns().get(0).values()).containsExactly("DESK-B");
        QueryService.Result masked = run(ds, body("auto", "\"MTM\",\"Notional\""), who(false, "", "risk"));
        assertThat(masked.columns().get(1).values()).containsOnlyNulls();                 // MTM is masked for this reader, whatever the source of the figure
        assertThat(masked.columns().get(2).values()).doesNotContainNull();
    }

    @Test
    void ameasureThatIsNotIncrementalAndTheSwitchedOffSettingFallBackToRecomputing() {
        QueryService.Loaded ds = start(true, "");
        QueryService.Caller risk = who(true, "", "risk");
        QueryService.Result median = run(ds, body("auto", "\"MTM\",\"P50\""), risk);               // a percentile cannot be kept by group
        assertThat(median.meta().rollup()).isNull();
        service.close();
        lake = new LiveTablesTest.LiveLake();
        QueryService.Loaded off = start(false, "");
        QueryService.Result r = run(off, body("auto", MEASURES), risk);
        assertThat(r.meta().rollup()).isNull();
        assertThat(r.meta().layer()).isEqualTo("live");
        assertThat(service.live().find(off.key(), "trades").orElseThrow().aggregateStats()).isEmpty();
    }
}
