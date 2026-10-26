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
package com.ash.drishti.server.exposure;

import com.ash.drishti.compute.ccr.ExposureMath;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The exposure engine (docs/contracts/EXP.md section 6.1): exposure profiles (EE, ENE, EEE, PFE, EPE, EEPE) for any counterparty scope
 * from the upstream exposure paths, in the server.
 *
 * @param enabled the engine and its API are on
 * @param confidence the PFE confidences computed for every answer (any other may be asked per request)
 * @param limitConfidence the PFE a credit limit is set on (LIM reads it; the default {@code q} of an answer)
 * @param rankRule {@code kth} (the ceil(P q)-th smallest exposure) or {@code interpolated}
 * @param collateralModel {@code lagged} (called at the look-back point, the margin period of risk earlier), {@code current} (today's
 *     collateral held on every path) or {@code none}
 * @param imModel {@code current} (the initial margin held today on every path) or {@code none}
 * @param alpha the IMM multiplier: EAD_IMM = alpha x EEPE (informational)
 * @param contributionWindow paths ranked around the PFE quantile a PFE contribution averages over
 * @param hotDates business dates kept open at once (the newest first)
 * @param memoryBudgetGb the most memory one date's netting-set matrices may take while it loads (0: a quarter of the heap)
 * @param hotFolder the hot files' folder ({@code <data folder>/ccr/hot}), one folder per business date
 * @param tier {@code mapped} (each date a float32 hot file, memory-mapped) or {@code heap} (float64 on the heap)
 * @param diskDates the most hot files kept on disk (the least recently used beyond are deleted, unless retention keeps them)
 * @param keepOnShutdown keep the hot files when the server stops
 * @param closeGraceSeconds how long an evicted date stays mapped for the requests still reading it
 * @param maxScopeNettingSets the most whole netting sets an interactive answer sums (more: 503 DRS-5443, the official capture computes it)
 * @param maxParts the most parts a contribution table computes at once
 * @param histogramBins the bins of an exposure distribution
 * @param t0Tolerance a trade's paths at the first grid date are said to depart from its MtM today beyond this share of the MtM (or 5%
 *     of its notional, the larger)
 * @param kinds the kinds the engine reads (the counterparty-risk pack's)
 * @param access per role, per counterparty, group or legal entity: {@code trades} (figures, netting sets and trades), {@code aggregate}
 *     (figures only) or {@code none}; a role without an entry sees everything at {@code trades} when it may open the paths
 * @param official the official end-of-day captures and their published results
 * @param retention what is kept how long (EXP.md section 12.3, decided 2026-10-26)
 */
@ConfigurationProperties("drishti.ccr.exposure")
public record ExposureProperties(Boolean enabled, List<Double> confidence, Double limitConfidence, String rankRule, String collateralModel,
        String imModel, Double alpha, Integer contributionWindow, Integer hotDates, Double memoryBudgetGb, String hotFolder, String tier,
        Integer diskDates, Boolean keepOnShutdown, Integer closeGraceSeconds, Integer maxScopeNettingSets, Integer maxParts, Integer histogramBins,
        Double t0Tolerance, Kinds kinds, Map<String, Map<String, String>> access, Official official, Retention retention) {

    /** The kinds the engine reads, as the counterparty-risk pack names them. */
    public record Kinds(String run, String paths, String nettingSet, String csa, String collateral, String counterparty, String group,
            String hierarchy) {
        public Kinds {
            run = run == null ? "simulation-run" : run;
            paths = paths == null ? "exposure-path" : paths;
            nettingSet = nettingSet == null ? "netting-set" : nettingSet;
            csa = csa == null ? "csa" : csa;
            collateral = collateral == null ? "collateral-balance" : collateral;
            counterparty = counterparty == null ? "counterparty" : counterparty;
            group = group == null ? "counterparty-group" : group;
            hierarchy = hierarchy == null ? "risk-hierarchy" : hierarchy;
        }

        /** The kinds whose data-load signal reloads a date's answers. */
        public List<String> inputs() {
            return List.of(run, paths, nettingSet, csa, collateral);
        }

        public List<String> all() {
            return List.of(run, paths, nettingSet, csa, collateral, counterparty, group, hierarchy);
        }
    }

    /**
     * The official end-of-day captures (EXP.md section 9.6).
     *
     * @param enabled capture every netting set, counterparty, group and hierarchy node when a date's inputs are complete
     * @param publish also append them to {@code <root>/risk-results/exposure-official} and {@code exposure-official-cpty}
     * @param root the Delta root the results are published under (the risk lake's)
     * @param retentionYears the years the published tables keep their log and removed files (time travel)
     * @param onStart at start, capture the newest date when it has no official record yet
     */
    public record Official(Boolean enabled, Boolean publish, String root, Integer retentionYears, Boolean onStart) {
        public Official {
            enabled = enabled == null || enabled;
            publish = publish == null || publish;
            root = root == null || root.isBlank() ? null : root;
            retentionYears = retentionYears == null || retentionYears < 1 ? 7 : retentionYears;
            onStart = onStart == null || onStart;
        }
    }

    /**
     * Retention of exposure data (EXP.md section 12.3, the product owner's decision of 2026-10-26): full trade paths for
     * {@code tradePathsDays} business days and each month-end for {@code tradePathsMonthEndYears}; netting-set matrices (the hot files)
     * every date for {@code setMatricesYears} and each month-end for {@code setMatricesMonthEndYears}; statistics (the official records)
     * and trade contributions every date for {@code statisticsYears} and {@code tradeContributionsYears}.
     */
    public record Retention(Integer tradePathsDays, Integer tradePathsMonthEndYears, Integer setMatricesYears, Integer setMatricesMonthEndYears,
            Integer statisticsYears, Integer tradeContributionsYears) {
        public Retention {
            tradePathsDays = tradePathsDays == null || tradePathsDays < 1 ? 20 : tradePathsDays;
            tradePathsMonthEndYears = tradePathsMonthEndYears == null || tradePathsMonthEndYears < 0 ? 1 : tradePathsMonthEndYears;
            setMatricesYears = setMatricesYears == null || setMatricesYears < 0 ? 1 : setMatricesYears;
            setMatricesMonthEndYears = setMatricesMonthEndYears == null || setMatricesMonthEndYears < 0 ? 7 : setMatricesMonthEndYears;
            statisticsYears = statisticsYears == null || statisticsYears < 1 ? 7 : statisticsYears;
            tradeContributionsYears = tradeContributionsYears == null || tradeContributionsYears < 1 ? 7 : tradeContributionsYears;
        }
    }

    public ExposureProperties {
        enabled = enabled == null || enabled;
        confidence = confidence == null || confidence.isEmpty() ? List.of(0.95, 0.99)
                : confidence.stream().filter(q -> q != null && q > 0.5 && q < 1).sorted().distinct().toList();
        limitConfidence = limitConfidence == null || limitConfidence <= 0.5 || limitConfidence >= 1 ? 0.95 : limitConfidence;
        rankRule = rankRule == null || rankRule.isBlank() ? "kth" : rankRule.trim().toLowerCase(Locale.ROOT);
        collateralModel = collateralModel == null || collateralModel.isBlank() ? "lagged" : collateralModel.trim().toLowerCase(Locale.ROOT);
        ExposureMath.Model.of(collateralModel);
        imModel = imModel == null || imModel.isBlank() ? "current" : imModel.trim().toLowerCase(Locale.ROOT);
        alpha = alpha == null || alpha <= 0 ? 1.4 : alpha;
        contributionWindow = contributionWindow == null || contributionWindow < 1 ? 21 : contributionWindow;
        hotDates = hotDates == null || hotDates <= 0 ? 2 : hotDates;
        memoryBudgetGb = memoryBudgetGb == null || memoryBudgetGb <= 0 ? Runtime.getRuntime().maxMemory() / 4.0 / (1L << 30) : memoryBudgetGb;
        hotFolder = hotFolder == null || hotFolder.isBlank() ? com.ash.drishti.api.DataDir.under("ccr/hot") : hotFolder;
        tier = tier == null || tier.isBlank() ? "mapped" : tier.trim().toLowerCase(Locale.ROOT).equals("heap") ? "heap" : "mapped";
        diskDates = diskDates == null || diskDates < 1 ? 60 : diskDates;
        keepOnShutdown = keepOnShutdown != null && keepOnShutdown;
        closeGraceSeconds = closeGraceSeconds == null || closeGraceSeconds < 0 ? 60 : closeGraceSeconds;
        maxScopeNettingSets = maxScopeNettingSets == null || maxScopeNettingSets <= 0 ? 20_000 : maxScopeNettingSets;
        maxParts = maxParts == null || maxParts <= 0 ? 5000 : maxParts;
        histogramBins = histogramBins == null || histogramBins < 5 ? 42 : histogramBins;
        t0Tolerance = t0Tolerance == null || t0Tolerance <= 0 ? 0.10 : t0Tolerance;
        kinds = kinds == null ? new Kinds(null, null, null, null, null, null, null, null) : kinds;
        access = access == null ? Map.of() : access;
        official = official == null ? new Official(null, null, null, null, null) : official;
        retention = retention == null ? new Retention(null, null, null, null, null, null) : retention;
    }

    public ExposureMath.Rule rule() {
        return rankRule.startsWith("interp") ? ExposureMath.Rule.INTERPOLATED : ExposureMath.Rule.KTH;
    }

    public ExposureMath.Model model() {
        return ExposureMath.Model.of(collateralModel);
    }

    public boolean mapped() {
        return tier.equals("mapped");
    }

    public double[] confidences() {
        return confidence.stream().mapToDouble(Double::doubleValue).toArray();
    }
}
