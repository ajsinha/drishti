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
package com.ash.drishti.server.var;

import com.ash.drishti.compute.var.PositionRules;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The VaR engine (VAR_ENGINE.md): value at risk for any set of trades from the stored P&amp;L vectors, in the server.
 *
 * @param enabled the engine and its API are on
 * @param confidence VaR's confidence (0.99)
 * @param esConfidence ES's confidence (0.975)
 * @param rankRule {@code kth-worst} (k = ceil(N (1 - a)): the 6th worst of 509) or {@code interpolated}
 * @param componentWindow the scenarios around the VaR rank component VaR averages over (5)
 * @param componentScaleToVar components scaled so they add up to the VaR exactly (true), else to the smoothed VaR
 * @param threads the cores the vector sums use (0: every core)
 * @param parallelRows the fewest rows worth splitting across threads
 * @param memoryBudgetMb the most memory one date's vectors may take (0: a quarter of the heap); a date that would exceed it is refused
 *     with the reason (503, DRS-5204), never loaded in part
 * @param rowsPerChunk rows per chunk of the vector store
 * @param hotDates business dates kept loaded at once (the newest first; older ones are dropped)
 * @param historyDays the history and backtest window (250 business days)
 * @param histogramBins the bins of the P&amp;L distribution sent to the screen
 * @param maxParts the most parts a contribution table computes at once (more: the largest by gross size, and said)
 * @param kinds the kinds the engine reads (vectors, hierarchy, scenario set, portfolios) and serves (positions)
 * @param access per role, the hierarchy nodes it may see and how: {@code MGMT~DESK-RATES: positions} (its numbers, children, positions
 *     and trades) or {@code aggregate} (numbers and children, never a position); a role without an entry sees every node at
 *     {@code positions} level when it may open the vector kind, else nothing
 * @param pathPage rows of the P&amp;L path table per page
 * @param positions how positions are derived from the trades when no loaded pack says (a pack's {@code positions:} section wins)
 * @param official the official end-of-day captures and their published results
 * @param tier where a loaded date's vectors are kept: the memory-mapped hot file (default) or the heap (VAR.md section 7.3)
 */
@ConfigurationProperties("drishti.var")
public record VarProperties(Boolean enabled, Double confidence, Double esConfidence, String rankRule, Integer componentWindow,
        Boolean componentScaleToVar, Integer threads, Integer parallelRows, Long memoryBudgetMb, Integer rowsPerChunk, Integer hotDates,
        Integer historyDays, Integer histogramBins, Integer maxParts, Kinds kinds, Map<String, Map<String, String>> access, Integer pathPage,
        Positions positions, Official official, Tier tier) {

    /** The kinds the engine reads, as the market-risk pack names them; {@code position} is the kind its derived positions are served as. */
    public record Kinds(String vector, String position, String hierarchy, String scenarioSet, String portfolio) {
        public Kinds {
            vector = vector == null ? "pnl-vector" : vector;
            position = position == null ? "position" : position;
            hierarchy = hierarchy == null ? "risk-hierarchy" : hierarchy;
            scenarioSet = scenarioSet == null ? "scenario-set" : scenarioSet;
            portfolio = portfolio == null ? "portfolio" : portfolio;
        }

        public List<String> all() {
            return List.of(vector, position, hierarchy, scenarioSet, portfolio);
        }

        /** The kinds whose load changes a date's answers (the data-load signal reloads the date): the inputs. */
        public List<String> inputs() {
            return List.of(vector, hierarchy, scenarioSet, portfolio);
        }
    }

    /**
     * The server's own position rules (VAR.md section 3.7), used when no loaded pack has a {@code positions:} section.
     *
     * @param liveStatuses the trade statuses that count on their date (default NEW, VALIDATED, AMENDED)
     * @param rules first match wins: {@code name}, {@code groups} (PRODUCT_GROUP), {@code types} (PRODUCT_TYPE), {@code key} (the fields
     *     whose first present value keys the position); none: every trade is a position of its own
     */
    public record Positions(List<String> liveStatuses, List<Rule> rules) {
        public record Rule(String name, List<String> groups, List<String> types, List<String> key) {}

        public PositionRules toRules() {
            List<PositionRules.Rule> out = new ArrayList<>();
            int n = 0;
            for (Rule r : rules == null ? List.<Rule>of() : rules) {
                n++;
                out.add(new PositionRules.Rule(r.name() == null ? "rule-" + n : r.name(), set(r.groups()), set(r.types()),
                        r.key() == null ? List.of() : r.key()));
            }
            return new PositionRules(liveStatuses, out);
        }

        private static java.util.Set<String> set(List<String> l) {
            return l == null ? java.util.Set.of() : new LinkedHashSet<>(l);
        }
    }

    /**
     * The official end-of-day captures (VAR.md section 9.6).
     *
     * @param enabled capture every node of every hierarchy when a date's inputs are complete (after the data-load signal)
     * @param publish also write them to {@code <root>/risk-results/var-official} for other systems
     * @param root the Delta root the results are published under (the risk lake's root)
     * @param retentionYears the years the published table keeps its log and removed files (time travel), 7 by default
     * @param onStart at start, capture the newest date when it has no official record yet (a lake built while the server was down)
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
     * The tiered memory (VAR.md section 7.3): where a loaded date's vectors live.
     *
     * @param mode {@code mapped} (default): each loaded date becomes a local hot file (float32 position rows in hierarchy order, the
     *     aggregates in float64) mapped into memory, the heap keeping the aggregates, columns and links; {@code heap}: every trade's vector
     *     in float64 on the heap (as before 2.8.0)
     * @param dir the hot files' folder ({@code <data folder>/var/hot}), one folder per business date
     * @param diskDates the most hot files kept on disk (the least recently used beyond it are deleted); a date not open is mapped
     *     again from its file when asked, without reading the lake
     * @param keepOnShutdown keep the hot files when the server stops (false: they are deleted at shutdown, and at start any left over)
     * @param closeGraceSeconds how long an evicted date stays mapped for the requests still reading it before it is unmapped
     */
    public record Tier(String mode, String dir, Integer diskDates, Boolean keepOnShutdown, Integer closeGraceSeconds) {
        public Tier {
            mode = mode == null || mode.isBlank() ? "mapped" : mode.trim().toLowerCase(Locale.ROOT);
            mode = mode.equals("heap") ? "heap" : "mapped";
            dir = dir == null || dir.isBlank() ? com.ash.drishti.api.DataDir.under("var/hot") : dir;
            diskDates = diskDates == null || diskDates < 1 ? 60 : diskDates;
            keepOnShutdown = keepOnShutdown != null && keepOnShutdown;
            closeGraceSeconds = closeGraceSeconds == null || closeGraceSeconds < 0 ? 60 : closeGraceSeconds;
        }

        public boolean mapped() {
            return mode.equals("mapped");
        }
    }

    public VarProperties {
        enabled = enabled == null || enabled;
        confidence = confidence == null || confidence <= 0.5 || confidence >= 1 ? 0.99 : confidence;
        esConfidence = esConfidence == null || esConfidence <= 0.5 || esConfidence >= 1 ? 0.975 : esConfidence;
        rankRule = rankRule == null || rankRule.isBlank() ? "kth-worst" : rankRule.trim().toLowerCase(Locale.ROOT);
        componentWindow = componentWindow == null || componentWindow < 1 ? 5 : componentWindow;
        componentScaleToVar = componentScaleToVar == null || componentScaleToVar;
        threads = threads == null || threads <= 0 ? Runtime.getRuntime().availableProcessors() : threads;
        parallelRows = parallelRows == null || parallelRows <= 0 ? 4096 : parallelRows;
        memoryBudgetMb = memoryBudgetMb == null || memoryBudgetMb <= 0 ? Runtime.getRuntime().maxMemory() / 4 / (1024 * 1024) : memoryBudgetMb;
        rowsPerChunk = rowsPerChunk == null || rowsPerChunk <= 0 ? 65_536 : rowsPerChunk;
        hotDates = hotDates == null || hotDates <= 0 ? 2 : hotDates;
        historyDays = historyDays == null || historyDays <= 1 ? 250 : historyDays;
        histogramBins = histogramBins == null || histogramBins < 5 ? 42 : histogramBins;
        maxParts = maxParts == null || maxParts <= 0 ? 5000 : maxParts;
        kinds = kinds == null ? new Kinds(null, null, null, null, null) : kinds;
        access = access == null ? Map.of() : access;
        pathPage = pathPage == null || pathPage <= 0 ? 60 : pathPage;
        positions = positions == null ? new Positions(null, null) : positions;
        official = official == null ? new Official(null, null, null, null, null) : official;
        tier = tier == null ? new Tier(null, null, null, null, null) : tier;
    }

    public com.ash.drishti.compute.var.VarMath.RankRule rule() {
        return rankRule.startsWith("interp") ? com.ash.drishti.compute.var.VarMath.RankRule.INTERPOLATED
                : com.ash.drishti.compute.var.VarMath.RankRule.KTH_WORST;
    }

    public com.ash.drishti.compute.var.VarCalc calc() {
        return new com.ash.drishti.compute.var.VarCalc(confidence, esConfidence, rule(), componentWindow, componentScaleToVar);
    }
}
