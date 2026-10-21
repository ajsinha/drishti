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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ash.drishti.server.bi.engine.Footprints;
import com.ash.drishti.server.bi.report.BiReportService;
import com.ash.drishti.server.security.Principal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * BI_RUNS 18.2b: a report visual's finished answer is shared between viewers with the same access profile (one computation, the second reused), never
 * with a viewer of another profile (a mask, a row rule) in either order, and a visual that depends on the viewer (no shared profile) stays per person.
 */
class RunSourcesReportTest {

    @TempDir
    Path data;
    RunService runs;
    RunSources sources;
    BiReportService reports;
    final Map<String, AtomicInteger> computed = new ConcurrentHashMap<>();
    /** The access profile of each viewer (none: the visual depends on who views it). */
    final Map<String, String> profiles = new ConcurrentHashMap<>(Map.of("rita", "s_risk", "rob", "s_risk", "ann", "s_masked", "hana", "s_desk-a"));

    @BeforeEach
    void setUp() {
        RunsProperties p = RunsProperties.defaults(data);
        runs = new RunService(p, new RunRecords.Memory(), new RunStore(p), Clock.systemUTC(), "java-2", RunMetrics.NONE);
        DataVersionVector dv = new DataVersionVector().put("lake", "trade", LocalDate.of(2026, 10, 8), "v7");
        RunPlanner.Planned planned = new RunPlanner.Planned(new RunEstimate(24L, 0L, 768L, 24L, 0.01, 0.1, RunEstimate.Basis.LAYERS, 1, 0, false, 0, "test"),
                new RunEstimator.Decision(Tier.INTERACTIVE, false, Optional.empty()), dv, List.of(LocalDate.of(2026, 10, 8)), null, List.of());
        RunPlanner planner = mock(RunPlanner.class);
        when(planner.plan(any(), anyBoolean())).thenReturn(planned);
        runs.planner(planner);
        reports = mock(BiReportService.class);
        when(reports.footprint(any(), anyString(), anyString(), anyString(), any())).thenReturn(Optional.of(new Footprints.Footprint(null, dv, List.of(), null)));
        when(reports.titles(any(), anyString(), anyString(), anyString())).thenReturn(new BiReportService.Titles("Report", "Visual"));
        when(reports.accessProfile(any(), anyString(), anyString(), anyString(), any())).thenAnswer(i -> {
            String sig = profiles.get(((Principal) i.getArgument(0)).user());
            return Optional.ofNullable(sig).map(s -> new BiReportService.Shared(s, "def-1"));
        });
        when(reports.query(any(), anyString(), any(), anyString(), anyString(), any(), eq(false))).thenAnswer(i -> {
            Principal who = i.getArgument(0);
            computed.computeIfAbsent(who.user(), u -> new AtomicInteger()).incrementAndGet();
            Object mtm = "ann".equals(who.user()) ? null : 300.0;                // ann's MTM is masked
            List<List<Object>> rows = "hana".equals(who.user()) ? List.of(java.util.Arrays.asList("DESK-A", 100.0, mtm))
                    : List.of(java.util.Arrays.asList("DESK-A", 100.0, mtm), java.util.Arrays.asList("DESK-B", 200.0, mtm));
            return Map.of("rowCount", rows.size(), "rows", rows, "who", who.user());
        });
        sources = new RunSources(runs, null, null, null, reports);
    }

    @AfterEach
    void tearDown() {
        runs.close();
    }

    RunService.Outcome ask(String user, String role) {
        return ask(user, role, "by-desk");
    }

    RunService.Outcome ask(String user, String role, String visual) {
        return runs.submit(sources.report(new Principal(user, List.of(role)), "rep-1", "main", visual, null, false, null, null),
                new RunService.Options(Duration.ofSeconds(10), false));
    }

    /** The first asker: a background run, waited for until it is done and its answer stored (an inline answer this fast is not kept). */
    Run first(String user, String role, String visual) throws InterruptedException {
        RunService.Outcome o = runs.submit(sources.report(new Principal(user, List.of(role)), "rep-1", "main", visual, null, false, null, null),
                new RunService.Options(Duration.ZERO, true));
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            Optional<Run> r = runs.get(o.run().id());
            if (r.isPresent() && r.get().state() == RunState.DONE) {
                return r.get();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("run never finished");
    }

    Run first(String user, String role) throws InterruptedException {
        return first(user, role, "by-desk");
    }

    int computed(String user) {
        return computed.getOrDefault(user, new AtomicInteger()).get();
    }

    @Test
    void twoViewersWithTheSameAccessProfileShareOneComputation() throws Exception {
        Run rita = first("rita", "bi-risk");
        RunService.Outcome rob = ask("rob", "bi-risk");
        assertThat(rob.run().state()).isEqualTo(RunState.REUSED);
        assertThat(computed("rita")).isEqualTo(1);
        assertThat(computed("rob")).isZero();
        assertThat(rob.run().reuseOf()).isEqualTo(rita.id());
        assertThat(rob.answer().body().get("rows")).isEqualTo(runs.result(rita, "s_risk").get("rows"));
    }

    @Test
    void aViewerWithAnotherMaskOrRowRuleNeverGetsTheAnswerInEitherOrder() throws Exception {
        // rita first, then ann (masked) and hana (one desk)
        first("rita", "bi-risk");
        RunService.Outcome ann = ask("ann", "bi-analyst");
        RunService.Outcome hana = ask("hana", "desk-head");
        assertThat(ann.run().state()).isNotEqualTo(RunState.REUSED);
        assertThat(hana.run().state()).isNotEqualTo(RunState.REUSED);
        assertThat(ann.answer().body()).containsEntry("who", "ann");
        assertThat(hana.answer().body()).containsEntry("who", "hana");
        assertThat(computed("ann")).isEqualTo(1);
        assertThat(computed("hana")).isEqualTo(1);
        // and the other order on another visual: ann (masked) first, then rita computes her own and rob reuses rita's, never ann's
        first("ann", "bi-analyst", "total");
        Run rita = first("rita", "bi-risk", "total");
        assertThat(rita.reuseOf()).isNull();
        assertThat(runs.result(rita, "s_risk")).containsEntry("who", "rita");
        RunService.Outcome rob = ask("rob", "bi-risk", "total");
        assertThat(rob.run().state()).isEqualTo(RunState.REUSED);
        assertThat(rob.answer().body()).containsEntry("who", "rita");
    }

    @Test
    void aVisualThatDependsOnTheViewerStaysTheirs() throws Exception {
        profiles.clear();                                                       // no shared profile: the visual names the viewer
        first("rita", "bi-risk");
        RunService.Outcome rob = ask("rob", "bi-risk");
        assertThat(rob.run().state()).isNotEqualTo(RunState.REUSED);
        assertThat(rob.answer().body()).containsEntry("who", "rob");
        assertThat(computed("rita")).isEqualTo(1);
        assertThat(computed("rob")).isEqualTo(1);
    }

    @Test
    void aFreshRunIsComputedAnewThenReusedAgain() throws Exception {
        Run rita = first("rita", "bi-risk");
        RunService.Outcome fresh = runs.submit(sources.report(new Principal("rita", List.of("bi-risk")), "rep-1", "main", "by-desk", null, false, null, null),
                new RunService.Options(Duration.ofSeconds(10), false, false, null, true));
        assertThat(fresh.run().state()).isNotEqualTo(RunState.REUSED);
        assertThat(computed("rita")).isEqualTo(2);
        assertThat(ask("rob", "bi-risk").run().state()).isEqualTo(RunState.REUSED);
        assertThat(rita.id()).isNotNull();
    }

    @Test
    void aRerunPinsTheSameDateOrDropsItForTheLatest() {
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        var mq = m.createObjectNode();
        mq.putObject("time").put("as-of", "latest");
        RunSources.dated(mq, "time", "same", "2026-10-08", true);
        assertThat(mq.path("time").path("as-of").asText()).isEqualTo("2026-10-08");
        RunSources.dated(mq, "time", "latest", "2026-10-08", true);
        assertThat(mq.has("time")).isFalse();
        var state = m.createObjectNode();
        RunSources.dated(state, "dates", "same", "2026-10-08", false);
        assertThat(state.path("dates").path("from").asText()).isEqualTo("2026-10-08");
        assertThat(state.path("dates").path("to").asText()).isEqualTo("2026-10-08");
        RunSources.dated(state, "dates", "latest", "2026-10-08", false);
        assertThat(state.has("dates")).isFalse();
        var range = m.createObjectNode();
        range.putObject("time").put("from", "2026-10-01").put("to", "2026-10-08");
        RunSources.dated(range, "time", "latest", "2026-10-08", true);
        assertThat(range.path("time").path("from").asText()).as("a range is asked as it was").isEqualTo("2026-10-01");
        assertThat(RunSources.oneDate("2026-10-08")).isEqualTo("2026-10-08");
        assertThat(RunSources.oneDate("[\"2026-10-07\",\"2026-10-08\"]")).isNull();
    }
}
