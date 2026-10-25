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
 * @param memoryBudgetMb the most memory the hot dates' vectors may take (0: a quarter of the heap); a date that would exceed it is
 *     refused with the reason, never loaded in part
 * @param rowsPerChunk rows per chunk of the vector store
 * @param hotDates business dates kept loaded at once (the newest first; older ones are dropped)
 * @param historyDays the history and backtest window (250 business days)
 * @param histogramBins the bins of the P&amp;L distribution sent to the screen
 * @param maxParts the most parts a contribution table computes at once (more: the largest by gross exposure, and said)
 * @param kinds the kinds the engine reads (vectors, positions, hierarchy, scenario set, node history, portfolios)
 * @param access per role, the hierarchy nodes it may see and how: {@code MGMT~DESK-RATES: positions} (its numbers, children, positions
 *     and trades) or {@code aggregate} (numbers and children, never a position); a role without an entry sees every node at
 *     {@code positions} level when it may open the vector kind, else nothing
 * @param pathPage rows of the P&amp;L path table per page
 */
@ConfigurationProperties("drishti.var")
public record VarProperties(Boolean enabled, Double confidence, Double esConfidence, String rankRule, Integer componentWindow,
        Boolean componentScaleToVar, Integer threads, Integer parallelRows, Long memoryBudgetMb, Integer rowsPerChunk, Integer hotDates,
        Integer historyDays, Integer histogramBins, Integer maxParts, Kinds kinds, Map<String, Map<String, String>> access, Integer pathPage) {

    /** The kinds the engine reads, as the market-risk pack names them. */
    public record Kinds(String vector, String position, String hierarchy, String scenarioSet, String node, String portfolio) {
        public Kinds {
            vector = vector == null ? "pnl-vector" : vector;
            position = position == null ? "position" : position;
            hierarchy = hierarchy == null ? "risk-hierarchy" : hierarchy;
            scenarioSet = scenarioSet == null ? "scenario-set" : scenarioSet;
            node = node == null ? "var-node" : node;
            portfolio = portfolio == null ? "portfolio" : portfolio;
        }

        public List<String> all() {
            return List.of(vector, position, hierarchy, scenarioSet, node, portfolio);
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
        kinds = kinds == null ? new Kinds(null, null, null, null, null, null) : kinds;
        access = access == null ? Map.of() : access;
        pathPage = pathPage == null || pathPage <= 0 ? 60 : pathPage;
    }

    public com.ash.drishti.compute.var.VarMath.RankRule rule() {
        return rankRule.startsWith("interp") ? com.ash.drishti.compute.var.VarMath.RankRule.INTERPOLATED
                : com.ash.drishti.compute.var.VarMath.RankRule.KTH_WORST;
    }

    public com.ash.drishti.compute.var.VarCalc calc() {
        return new com.ash.drishti.compute.var.VarCalc(confidence, esConfidence, rule(), componentWindow, componentScaleToVar);
    }
}
