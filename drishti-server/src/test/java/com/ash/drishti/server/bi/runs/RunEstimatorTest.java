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
import static org.assertj.core.api.Assertions.within;

import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.runs.RunEstimate.Basis;
import com.ash.drishti.server.bi.runs.RunEstimator.BuildPath;
import com.ash.drishti.server.bi.runs.RunEstimator.DateRows;
import com.ash.drishti.server.bi.runs.RunEstimator.Decision;
import com.ash.drishti.server.bi.runs.RunEstimator.Plan;
import com.ash.drishti.server.bi.runs.RunEstimator.Rates;
import com.ash.drishti.server.bi.runs.RunEstimator.Rollup;
import com.ash.drishti.server.bi.runs.RunEstimator.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The planner's estimate maths and decisions (docs/architecture/BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), sections 4 and 5; test 19.1 RunPlannerTest's pure cases): one date is
 * interactive; a year covered by a rollup is known heavy; a year at a grain no rollup has is ad-hoc heavy and asks first; a missing rollup date with
 * small raw rows stays interactive (mixed); unknown rows give basis unknown and confirm; past the engine's read limit DRS-9086; an interaction that
 * would need a run DRS-9089; the range factors narrow from measured ratios.
 */
class RunEstimatorTest {

    static final PlannerSettings S = PlannerSettings.defaults();
    static final double[] SEED = RunEstimator.factors(List.of(), S);
    static final LocalDate START = LocalDate.of(2026, 1, 2);

    static List<LocalDate> days(int n) {
        return IntStream.range(0, n).mapToObj(START::plusDays).toList();
    }

    static Table table(String name, List<DateRows> dates) {
        return new Table(name, "trade-endur", BuildPath.SCAN, dates, 4, 0);
    }

    static Plan plan(Table t, Rollup rollup) {
        return new Plan(List.of(t), Optional.ofNullable(rollup), "desk × month", List.of(), null, false);
    }

    static Decision decide(Plan p) {
        return RunEstimator.decide(p, Rates.seeds(S), SEED, false, false, S);
    }

    @Test
    void oneBuiltDateIsInteractiveFromLayers() {
        Plan p = plan(table("trade_endur", List.of(DateRows.built(START, 10_000))), null);
        RunEstimate e = RunEstimator.estimate(p, Rates.seeds(S), SEED, S);
        assertThat(e.basis()).isEqualTo(Basis.LAYERS);
        assertThat(e.readRows()).isEqualTo(10_000);
        assertThat(e.buildRows()).isZero();
        assertThat(e.bytes()).isEqualTo(10_000L * 32);
        assertThat(e.secondsLow()).isEqualTo(0.03);           // (10 000 / 50 M + 0.05) × 0.5
        assertThat(e.secondsHigh()).isEqualTo(0.1);
        assertThat(e.dates()).isEqualTo(1);
        assertThat(decide(p)).isEqualTo(new Decision(Tier.INTERACTIVE, false, Optional.empty()));
    }

    @Test
    void aYearAtAGrainNoRollupHasIsAdHocHeavyAndAsksFirst() {
        List<DateRows> dates = new ArrayList<>();
        List<LocalDate> ds = days(182);
        for (int i = 0; i < ds.size(); i++) {
            dates.add(i < 41 ? DateRows.toBuild(ds.get(i), 10_100_000, Basis.DELTA_STATS) : DateRows.built(ds.get(i), 10_100_000));
        }
        Plan p = plan(table("trade_endur", dates), null);
        RunEstimate e = RunEstimator.estimate(p, Rates.seeds(S), SEED, S);
        assertThat(e.readRows()).isEqualTo(182L * 10_100_000);
        assertThat(e.buildRows()).isEqualTo(41L * 10_100_000);
        assertThat(e.datesToBuild()).isEqualTo(41);
        assertThat(e.basis()).isEqualTo(Basis.DELTA_STATS);  // built partitions are certain; the basis names the source of what is not
        double seconds = 41.0 * 10_100_000 / 300_000 / 4 + 182.0 * 10_100_000 / 50_000_000 + 0.05;
        assertThat(e.secondsLow()).isCloseTo(seconds * 0.5, within(0.01));
        assertThat(e.secondsHigh()).isCloseTo(seconds * 2.0, within(0.01));
        assertThat(e.why()).isEqualTo("182 business dates of trade_endur (about 10.1 M rows a day; 41 dates not built yet); no rollup has grain desk × month");
        assertThat(decide(p)).isEqualTo(new Decision(Tier.AD_HOC_HEAVY, true, Optional.empty()));
        assertThat(e.toMap()).containsEntry("basis", "delta-stats").containsEntry("datesToBuild", 41).containsKey("seconds");
    }

    @Test
    void theBasisIsTheSourceBehindTheMostUnbuiltRows() {
        List<LocalDate> ds = days(3);
        Plan p = plan(table("t", List.of(DateRows.toBuild(ds.get(0), 100, Basis.DELTA_STATS), DateRows.toBuild(ds.get(1), 500, Basis.HISTORY),
                DateRows.built(ds.get(2), 10_000))), null);
        assertThat(RunEstimator.estimate(p, Rates.seeds(S), SEED, S).basis()).isEqualTo(Basis.HISTORY);
    }

    @Test
    void aYearCoveredByARollupIsKnownHeavyAndSmallRawIsInteractive() {
        List<LocalDate> ds = days(182);
        Map<LocalDate, Long> rollupRows = new HashMap<>();
        ds.forEach(d -> rollupRows.put(d, 40L));
        Rollup r = new Rollup("desk_month", rollupRows, 0);
        Plan big = plan(table("trade_endur", ds.stream().map(d -> DateRows.toBuild(d, 10_100_000, Basis.DELTA_STATS)).toList()), r);
        RunEstimate e = RunEstimator.estimate(big, Rates.seeds(S), SEED, S);
        assertThat(e.covered()).isTrue();
        assertThat(e.readRows()).isEqualTo(182L * 40);
        assertThat(e.buildRows()).isZero();
        assertThat(e.datesToBuild()).isZero();
        assertThat(e.why()).endsWith("; answered from rollup desk_month");
        assertThat(decide(big)).isEqualTo(new Decision(Tier.KNOWN_HEAVY, false, Optional.empty()));
        Plan small = plan(table("trade_endur", ds.stream().map(d -> DateRows.built(d, 1_000)).toList()), r);
        assertThat(decide(small).tier()).isEqualTo(Tier.INTERACTIVE);
    }

    @Test
    void aMissingRollupDateWithSmallRawRowsStaysInteractiveAndWithLargeOnesIsAdHoc() {
        List<LocalDate> ds = days(60);
        Map<LocalDate, Long> rollupRows = new HashMap<>();
        ds.subList(0, 59).forEach(d -> rollupRows.put(d, 40L));
        Rollup r = new Rollup("desk_month", rollupRows, 0);
        Plan small = plan(table("trade_endur", ds.stream().map(d -> DateRows.built(d, 10_000)).toList()), r);
        RunEstimate e = RunEstimator.estimate(small, Rates.seeds(S), SEED, S);
        assertThat(e.covered()).isFalse();
        assertThat(e.coveredDates()).isEqualTo(59);
        assertThat(e.readRows()).isEqualTo(59L * 40 + 10_000);
        assertThat(e.why()).isEqualTo("60 business dates of trade_endur (about 10 K rows a day); rollup desk_month lacks 1 date");
        assertThat(decide(small).tier()).isEqualTo(Tier.INTERACTIVE);
        Plan large = plan(table("trade_endur", ds.stream().map(d -> DateRows.toBuild(d, 90_000_000, Basis.DELTA_STATS)).toList()), r);
        assertThat(decide(large).tier()).isEqualTo(Tier.AD_HOC_HEAVY);
    }

    @Test
    void moreDatesToBuildThanTheInteractiveBoundIsAdHocEvenWhenSmall() {
        Plan p = plan(table("t", days(3).stream().map(d -> DateRows.toBuild(d, 10, Basis.DELTA_STATS)).toList()), null);
        Decision d = decide(p);
        assertThat(d.tier()).isEqualTo(Tier.AD_HOC_HEAVY);
        assertThat(d.confirm()).isFalse();                   // seconds and rows are below the confirm thresholds
    }

    @Test
    void unknownRowsAreNeverZeroTheyAreBasisUnknownAndAskFirst() {
        Plan p = plan(table("mystery", List.of(DateRows.built(START, 10), DateRows.unknown(START.plusDays(1)))), null);
        RunEstimate e = RunEstimator.estimate(p, Rates.seeds(S), SEED, S);
        assertThat(e.basis()).isEqualTo(Basis.UNKNOWN);
        assertThat(e.readRows()).isNull();
        assertThat(e.secondsHigh()).isNull();
        assertThat(e.why()).contains("the size of mystery is not known");
        assertThat(decide(p)).isEqualTo(new Decision(Tier.AD_HOC_HEAVY, true, Optional.empty()));
        PlannerSettings noAsk = new PlannerSettings(10, 50_000_000L, 2, 60, 500_000_000L, false, 5_000_000_000L, 0.5, 2.0, 20, 50_000_000, 300_000, 2_000,
                0.05, 32);
        assertThat(RunEstimator.decide(p, Rates.seeds(noAsk), SEED, false, false, noAsk).confirm()).isFalse();
    }

    @Test
    void pastEveryEngineIsRefusedUnlessABiggerEngineAccepts() {
        Plan p = plan(table("t", days(10).stream().map(d -> DateRows.built(d, 600_000_000)).toList()), null);
        assertThat(decide(p).refusal()).contains(ErrorCode.RUN_TOO_LARGE);
        assertThat(RunEstimator.decide(p, Rates.seeds(S), SEED, false, true, S).refusal()).isEmpty();
    }

    @Test
    void anInteractionThatWouldNeedARunIsRefusedAtOnceAndALiveOneIsInteractive() {
        Plan heavy = plan(table("t", days(100).stream().map(d -> DateRows.built(d, 5_000_000)).toList()), null);
        Decision d = RunEstimator.decide(heavy, Rates.seeds(S), SEED, true, false, S);
        assertThat(d.tier()).isEqualTo(Tier.AD_HOC_HEAVY);
        assertThat(d.refusal()).contains(ErrorCode.RUN_NEEDED);
        Plan light = plan(table("t", List.of(DateRows.built(START, 5))), null);
        assertThat(RunEstimator.decide(light, Rates.seeds(S), SEED, true, false, S).refusal()).isEmpty();
        Plan live = new Plan(heavy.tables(), Optional.empty(), null, List.of(), null, true);
        assertThat(decide(live).tier()).isEqualTo(Tier.INTERACTIVE);
    }

    @Test
    void rowsOutIsTheSmallestOfTheLimitTheGrainAndTheRowsRead() {
        Table t = table("t", List.of(DateRows.built(START, 1_000_000)));
        assertThat(RunEstimator.estimate(new Plan(List.of(t), Optional.empty(), null, List.of(12L, 360L), null, false), Rates.seeds(S), SEED, S).rowsOut())
                .isEqualTo(4_320);
        assertThat(RunEstimator.estimate(new Plan(List.of(t), Optional.empty(), null, List.of(12L, 360L), 100L, false), Rates.seeds(S), SEED, S).rowsOut())
                .isEqualTo(100);
        assertThat(RunEstimator.estimate(new Plan(List.of(t), Optional.empty(), null, List.of(), null, false), Rates.seeds(S), SEED, S).rowsOut())
                .isEqualTo(1_000_000);
        assertThat(RunEstimator.estimate(new Plan(List.of(t), Optional.empty(), null, List.of(Long.MAX_VALUE, 3L), null, false), Rates.seeds(S), SEED, S)
                .rowsOut()).isEqualTo(1_000_000);              // a product that overflows saturates, then the rows read cap it
    }

    @Test
    void measuredBuildRatesReplaceTheSeedsAndRecordReadsAreSlower() {
        Plan p = plan(table("t", List.of(DateRows.toBuild(START, 3_000_000, Basis.DELTA_STATS))), null);
        double seeded = RunEstimator.estimate(p, Rates.seeds(S), new double[] {1, 1}, S).secondsHigh();
        double measured = RunEstimator.estimate(p, new Rates(Map.of("trade-endur/scan", 3_000_000.0), S.scanRowsPerSecond()), new double[] {1, 1}, S)
                .secondsHigh();
        assertThat(seeded).isCloseTo(3_000_000.0 / 300_000 / 4 + 0.06 + 0.05, within(0.01));
        assertThat(measured).isCloseTo(3_000_000.0 / 3_000_000 / 4 + 0.06 + 0.05, within(0.01));
        Plan record = new Plan(List.of(new Table("t", "jdbc", BuildPath.RECORD, List.of(DateRows.toBuild(START, 2_000, Basis.STORE_STATS)), 1, 0)),
                Optional.empty(), null, List.of(), null, false);
        assertThat(RunEstimator.estimate(record, Rates.seeds(S), new double[] {1, 1}, S).secondsHigh()).isCloseTo(1.05, within(0.01));
    }

    @Test
    void theRangeFactorsNarrowToMeasuredPercentilesAfterTwentyRuns() {
        assertThat(RunEstimator.factors(List.of(1.0, 1.1), S)).containsExactly(0.5, 2.0);
        List<Double> ratios = IntStream.rangeClosed(0, 20).mapToObj(i -> 0.8 + i * 0.02).toList();   // 0.80 .. 1.20
        double[] f = RunEstimator.factors(ratios, S);
        assertThat(f[0]).isCloseTo(0.84, within(1e-9));
        assertThat(f[1]).isCloseTo(1.16, within(1e-9));
    }

    @Test
    void rowCountsInWords() {
        assertThat(RunEstimator.words(10_100_000)).isEqualTo("10.1 M");
        assertThat(RunEstimator.words(1_840_000_000L)).isEqualTo("1.8 B");
        assertThat(RunEstimator.words(410_000)).isEqualTo("410 K");
        assertThat(RunEstimator.words(950)).isEqualTo("950");
    }
}
