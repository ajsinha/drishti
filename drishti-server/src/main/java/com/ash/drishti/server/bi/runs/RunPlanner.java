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

import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.engine.Footprints;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The planner (docs/architecture/BI_RUNS.md section 4): a question's footprint (what it reads, from metadata only: {@code Layers.peek},
 * {@code SourcePlugin.rowEstimate}, {@link RunHistory}) becomes an honest estimate (a range with its basis, {@link RunEstimator}) under the rates in
 * force ({@link RunRates}), then a decision: the tier, whether to ask first ({@code DRS-9082}), or a refusal ({@code DRS-9086} past every engine,
 * {@code DRS-9089} for an interaction that would need a run). Pure apart from the rates; it takes microseconds and reads nothing.
 */
public final class RunPlanner {

    /** The engine every run uses in 2.6.7 (split and external are slots, section 5.4). */
    public static final String LOCAL = "local";

    /**
     * A question planned.
     *
     * @param estimate the estimate shown to the person
     * @param decision the tier, confirmation and refusal
     * @param data     the data version vector of what it reads
     * @param dates    the business dates it reads
     */
    public record Planned(RunEstimate estimate, RunEstimator.Decision decision, DataVersionVector data, List<LocalDate> dates, String buildPath) {

        public Tier tier() {
            return decision.tier();
        }

        public boolean confirm() {
            return decision.confirm();
        }

        public Optional<ErrorCode> refusal() {
            return decision.refusal();
        }
    }

    private final PlannerSettings settings;
    private final RunRates rates;
    private final boolean biggerEngine;

    public RunPlanner(PlannerSettings settings, RunRates rates) {
        this(settings, rates, false);
    }

    /** @param biggerEngine a split or external engine is configured (none in 2.6.7): the local read limit is then not a refusal */
    public RunPlanner(PlannerSettings settings, RunRates rates, boolean biggerEngine) {
        this.settings = settings;
        this.rates = rates;
        this.biggerEngine = biggerEngine;
    }

    public PlannerSettings settings() {
        return settings;
    }

    public RunRates rates() {
        return rates;
    }

    /** Plans a footprint. {@code interaction}: a slicer, cross-filter or drill (section 5.3). */
    public Planned plan(Footprints.Footprint f, boolean interaction) {
        double[] factors = rates.factors(LOCAL);
        RunEstimator.Rates now = rates.now();
        RunEstimate e = RunEstimator.estimate(f.plan(), now, factors, settings);
        RunEstimator.Decision d = RunEstimator.decide(f.plan(), now, factors, interaction, biggerEngine, settings);
        return new Planned(e, d, f.data(), f.dates(), buildPath(f.plan()));
    }

    /** The {@code connector/path} of the table with the most rows to build (what a finished run's build time teaches), or null. */
    static String buildPath(RunEstimator.Plan p) {
        String best = null;
        long most = 0;
        for (RunEstimator.Table t : p.tables()) {
            long rows = 0;
            for (RunEstimator.DateRows d : t.dates()) {
                if (!d.built() && d.rows() != null) {
                    rows += d.rows();
                }
            }
            if (rows > most) {
                most = rows;
                best = t.connector() + "/" + t.path().name().toLowerCase(java.util.Locale.ROOT);
            }
        }
        return best;
    }
}
