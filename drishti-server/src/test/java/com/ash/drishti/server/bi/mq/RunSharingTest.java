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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.bi.engine.DuckDbEngine;
import com.ash.drishti.server.bi.engine.QueryProperties;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.engine.TestLake;
import com.ash.drishti.server.bi.runs.Run;
import com.ash.drishti.server.bi.runs.RunMetrics;
import com.ash.drishti.server.bi.runs.RunRecords;
import com.ash.drishti.server.bi.runs.RunService;
import com.ash.drishti.server.bi.runs.RunShares;
import com.ash.drishti.server.bi.runs.RunSharing;
import com.ash.drishti.server.bi.runs.RunState;
import com.ash.drishti.server.bi.runs.RunStore;
import com.ash.drishti.server.bi.runs.RunsProperties;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Masks on shared runs (docs/architecture/BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), sections 9.3 and 19.3), on the engine's small lake with the desk rules and the masked counterparty:
 * the same rights read the stored result; a reader with more masks reads it re-masked, equal to their own direct query (a masked grouping field collapses
 * its groups exactly as the engine does); other row rules, fewer masks or a measure that does not add up are recomputed as the recipient; a share to a
 * role recomputes once per distinct signature; a notice never carries a value; a recipient whose rights narrowed since the share never reads the wider
 * answer.
 */
class RunSharingTest {

    static final String BY_CPTY = "{ mq: 1, model: desk-risk, select: { fields: [trades.cpty], measures: [Trades, MTM] } }";
    static final String BY_DESK = "{ mq: 1, model: desk-risk, select: { fields: [trades.desk], measures: [Trades, MTM] } }";
    static final String AVG_BY_CPTY = "{ mq: 1, model: desk-risk, select: { fields: [trades.cpty], measures: [AvgMTM] } }";

    @TempDir Path store;
    @TempDir Path data;
    TestLake lake;
    QueryService service;
    MqService mq;
    MqHarness h;
    ModelView model;
    RunService runs;
    RunSharing sharing;
    final Map<String, MqHarness.Reader> people = new HashMap<>();
    final AtomicInteger computed = new AtomicInteger();
    final List<String> told = new CopyOnWriteArrayList<>();
    final Map<String, List<String>> roles = new HashMap<>();

    @BeforeEach
    void setUp() {
        lake = new TestLake(true);
        QueryProperties props = QueryProperties.defaults(store.toString());
        service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC());
        mq = new MqService(MqProperties.defaults());
        h = new MqHarness(service, TestLake.DAY2);
        model = MqHarness.model(MqEngineTest.MODEL, MqEngineTest.TRADES, MqEngineTest.DESKS, MqEngineTest.LIMITS);
        people.put("rita", MqHarness.RITA);                                             // risk, raw: every row, nothing masked (the "admin")
        people.put("ann", MqHarness.ANN);                                               // analyst: every row, the counterparty masked (the "viewer")
        people.put("amy", new MqHarness.Reader("amy", false, "", List.of("analyst")));  // the same rights as ann
        people.put("hana", MqHarness.deskHead("DESK-A"));                               // her desk's rows only
        RunsProperties p = RunsProperties.defaults(data);
        runs = new RunService(p, new RunRecords.Memory(), new RunStore(p), Clock.systemUTC(), "java-1", RunMetrics.NONE);
        runs.notifier((u, r, s) -> told.add(u + ": " + s));
        sharing = new RunSharing(runs, new RunShares.Memory(), (who, m) -> bound(who.user()), mq, u -> new Principal(u, people.get(u).roles()),
                r -> roles.getOrDefault(r, List.of()), (run, as) -> Optional.of(question(as.user(), mqOf(run))), Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        runs.close();
        service.close();
    }

    MqService.Bound bound(String user) {
        MqHarness.Reader r = people.get(user);
        if (r == null) {
            throw new DrishtiException(com.ash.drishti.common.ErrorCode.BI_FORBIDDEN, "no access");
        }
        return h.bind(model, r, "m");
    }

    static String mqOf(Run r) {
        try {
            return new ObjectMapper().readTree(r.question()).path("mq").toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    RunService.Question question(String user, String yaml) {
        MqService.Bound b = bound(user);
        ModelQuery q = MqParser.parse(yaml);
        Object raw;
        try {
            raw = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).readValue(yaml, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String canon = new ObjectMapper().valueToTree(Map.of("type", "mq", "model", "desk-risk", "mq", raw)).toString();
        return new RunService.Question(new Principal(user, people.get(user).roles()), "mq", "desk-risk", null, "Trades by counterparty", null, "desk-risk",
                canon, null, b.signature(), "v1", false, null, () -> {
                    computed.incrementAndGet();
                    MqService.Answer a = mq.ask(b, q, false);
                    return new RunService.Answer(a.body(), a.result().columns(), a.result().rows());
                });
    }

    /** A finished, stored run of {@code yaml} as {@code user}. */
    Run ran(String user, String yaml) throws Exception {
        RunService.Outcome o = runs.submit(question(user, yaml), new RunService.Options(Duration.ZERO, true, true));
        return waitDone(o.run().id());
    }

    Run waitDone(String id) throws Exception {
        for (int i = 0; i < 300; i++) {
            Optional<Run> r = runs.get(id);
            if (r.isPresent() && (r.get().state() == RunState.DONE || r.get().state() == RunState.REUSED)) {
                return r.get();
            }
            assertThat(r.map(Run::state).orElse(RunState.QUEUED)).isNotIn(RunState.FAILED, RunState.CANCELLED);
            Thread.sleep(20);
        }
        throw new AssertionError("run " + id + " did not finish");
    }

    Map<String, Object> direct(String user, String yaml) {
        return mq.ask(bound(user), MqParser.parse(yaml), false).body();
    }

    @SuppressWarnings("unchecked")
    static Map<String, List<Object>> byFirst(Map<String, Object> answer) {
        Map<String, List<Object>> out = new java.util.TreeMap<>();
        for (List<Object> r : (List<List<Object>>) answer.get("rows")) {
            out.put(String.valueOf(r.get(0)), r.subList(1, r.size()).stream().map(v -> v instanceof Number n ? (Object) (Math.round(n.doubleValue() * 1000) / 1000.0) : v)
                    .map(Object.class::cast).toList());
        }
        return out;
    }

    @Test
    void theSameRightsReadTheStoredResult() throws Exception {
        Run r = ran("ann", BY_CPTY);
        assertThat(RunSharing.decide(bound("ann"), bound("amy"), MqParser.parse(BY_CPTY), runs.result(r, r.signature()), mq).mode())
                .isEqualTo(RunSharing.Mode.SAME);
        List<Map<String, Object>> s = sharing.share(r, new Principal("ann", List.of("analyst")), List.of("amy"));
        assertThat(s).singleElement().satisfies(e -> assertThat(e).containsEntry("mode", "same").containsEntry("recipientRun", null));
        assertThat(sharing.read(r, new Principal("amy", List.of("analyst")))).isEqualTo(runs.result(r, r.signature()));
    }

    @Test
    void anAdminSharingWithAViewerIsReMaskedAndEqualsTheViewersOwnAnswer() throws Exception {
        Run r = ran("rita", BY_CPTY);
        Map<String, Object> ritas = runs.result(r, r.signature());
        assertThat(byFirst(ritas).keySet()).noneMatch(DataNode.MASK::equals);       // the runner sees the counterparties
        int before = computed.get();
        List<Map<String, Object>> s = sharing.share(r, new Principal("rita", List.of("risk")), List.of("ann"));
        assertThat(s).singleElement().satisfies(e -> assertThat(e).containsEntry("mode", "re-masked"));
        assertThat(computed.get()).isEqualTo(before);                                   // re-masked: nothing computed again
        Map<String, Object> annSees = sharing.read(r, new Principal("ann", List.of("analyst")));
        @SuppressWarnings("unchecked")
        List<List<Object>> rows = (List<List<Object>>) annSees.get("rows");
        assertThat(rows).allSatisfy(row -> assertThat(row.get(0)).isEqualTo(DataNode.MASK));   // cell by cell: the mask, never a counterparty
        for (Object cpty : byFirst(ritas).keySet()) {
            assertThat(annSees.toString()).doesNotContain("=" + cpty + ",").doesNotContain("[" + cpty + ",");
        }
        assertThat(byFirst(annSees)).isEqualTo(byFirst(direct("ann", BY_CPTY)));       // exactly what a direct query as ann answers
        assertThat(runs.result(r, r.signature())).isEqualTo(ritas);                     // the runner's view is unchanged
    }

    @Test
    void aMaskedGroupingFieldUnderAMeasureThatDoesNotAddUpIsRecomputed() throws Exception {
        Run r = ran("rita", AVG_BY_CPTY);
        RunSharing.Decision d = RunSharing.decide(bound("rita"), bound("ann"), MqParser.parse(AVG_BY_CPTY), runs.result(r, r.signature()), mq);
        assertThat(d.mode()).isEqualTo(RunSharing.Mode.RECOMPUTED);
        List<Map<String, Object>> s = sharing.share(r, new Principal("rita", List.of("risk")), List.of("ann"));
        String annRun = (String) s.get(0).get("recipientRun");
        assertThat(s.get(0)).containsEntry("mode", "recomputed");
        Run mine = waitDone(annRun);
        assertThat(mine.runner()).isEqualTo("ann");
        assertThat(mine.sharedFrom()).isEqualTo(r.id());
        assertThat(byFirst(sharing.read(r, new Principal("ann", List.of("analyst"))))).isEqualTo(byFirst(direct("ann", AVG_BY_CPTY)));
    }

    @Test
    void otherRowRulesAreRecomputedAsTheRecipientAndNoRowOfTheRunnersDesksAppears() throws Exception {
        Run r = ran("rita", BY_DESK);
        assertThat(byFirst(runs.result(r, r.signature())).keySet()).hasSizeGreaterThan(1);
        List<Map<String, Object>> s = sharing.share(r, new Principal("rita", List.of("risk")), List.of("hana"));
        assertThat(s.get(0)).containsEntry("mode", "recomputed");
        waitDone((String) s.get(0).get("recipientRun"));
        Map<String, Object> hanas = sharing.read(r, new Principal("hana", List.of("desk-head")));
        assertThat(byFirst(hanas).keySet()).containsOnly("DESK-A");
        assertThat(byFirst(hanas)).isEqualTo(byFirst(direct("hana", BY_DESK)));
    }

    @Test
    void aViewerSharingWithAnAdminIsRecomputedAsTheAdminNeverServedTheViewersMaskAsTheirs() throws Exception {
        Run r = ran("ann", BY_CPTY);
        assertThat(RunSharing.decide(bound("ann"), bound("rita"), MqParser.parse(BY_CPTY), runs.result(r, r.signature()), mq).mode())
                .isEqualTo(RunSharing.Mode.RECOMPUTED);                                  // fewer masks: the stored masked answer is not theirs
        List<Map<String, Object>> s = sharing.share(r, new Principal("ann", List.of("analyst")), List.of("rita"));
        assertThat(s.get(0)).containsEntry("mode", "recomputed");
        waitDone((String) s.get(0).get("recipientRun"));
        Map<String, Object> ritas = sharing.read(r, new Principal("rita", List.of("risk")));
        assertThat(byFirst(ritas)).isEqualTo(byFirst(direct("rita", BY_CPTY)));      // the admin's own rights, never wider than a direct query
    }

    @Test
    void aShareToARoleExpandsToItsMembersAndEqualSignaturesComputeOnce() throws Exception {
        roles.put("desk-team", List.of("ann", "amy", "hana"));
        Run r = ran("rita", AVG_BY_CPTY);
        int before = computed.get();
        List<Map<String, Object>> s = sharing.share(r, new Principal("rita", List.of("risk")), List.of("role:desk-team"));
        assertThat(s).extracting(e -> e.get("recipient")).containsExactly("ann", "amy", "hana");
        assertThat(s).allSatisfy(e -> assertThat(e).containsEntry("mode", "recomputed"));
        for (Map<String, Object> e : s) {
            waitDone((String) e.get("recipientRun"));
        }
        assertThat(computed.get() - before).isEqualTo(2);                               // ann and amy share one computation; hana has her own
    }

    @Test
    void aNoticeCarriesNoValueAndAPersonWhoMayNotReadTheModelGetsNoShare() throws Exception {
        Run r = ran("rita", BY_CPTY);
        Map<String, Object> ritas = runs.result(r, r.signature());
        List<Map<String, Object>> s = sharing.share(r, new Principal("rita", List.of("risk")), List.of("ann", "mallory"));
        assertThat(s.get(1)).containsEntry("mode", null).containsKey("code");
        assertThat(sharing.shareOf(r.id(), "mallory")).isEmpty();
        assertThat(told).anySatisfy(t -> assertThat(t).startsWith("ann: rita shared a run with you"));
        for (String t : told) {
            for (Object cpty : byFirst(ritas).keySet()) {
                assertThat(t).doesNotContain(String.valueOf(cpty));
            }
        }
    }

    @Test
    void aRecipientWhoseRightsNarrowedNeverReadsTheWiderAnswer() throws Exception {
        Run r = ran("rita", BY_DESK);
        sharing.share(r, new Principal("rita", List.of("risk")), List.of("amy"));        // amy: analyst, every row: re-masked (no masked column shown)
        assertThat(sharing.shareOf(r.id(), "amy")).get().extracting(RunShares.Share::mode).isEqualTo("re-masked");
        people.put("amy", MqHarness.deskHead("DESK-B"));                               // amy is now a desk head: fewer rows
        assertThatThrownBy(() -> sharing.read(r, new Principal("amy", List.of("desk-head")))).isInstanceOf(DrishtiException.class)
                .hasMessageContaining("re-run it");
    }
}
