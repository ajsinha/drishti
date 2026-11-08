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
package com.ash.drishti.banking.var;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The market-risk cube's settings, {@code drishti.cube.mr} (MR_CUBE_GUIDE.md section 9; engine {@code mr-cube}, COMPUTE_ENGINES.md: needs
 * {@code var} and {@code pivot}, uses {@code sensitivities} and {@code frtb-sa}). The cube is the pivot engine's VaR source with its MR
 * lens: the switch is {@code drishti.engines.mr-cube.enabled} ({@code config/engines/mr-cube.yaml}); the pivot engine's budget, busy
 * protection and partial-state cache ({@code drishti.pivot.*}) apply to it as to every source.
 *
 * @param riskClasses the derived dimension {@code RISK_CLASS}: a class to the product groups whose positions are in it (a position's primary
 *     class until per-class vectors are delivered); a product group in none is {@code OTHER}
 * @param grids the sensitivity ladders' bucket grids: a grid to its buckets, each {@code label: from-to} in years ({@code 10-} open)
 * @param ladderLimits two-sided DV01 limits per currency and grid bucket ({@code USD 2-5Y: 1500000}); none: the ladder shows no limit bars and
 *     says so
 * @param sbmMaxCells the cells of a view that get FRTB-SA SBM capital (each a run of the FRTB-SA engine on the cell's scope): its first in the
 *     grid's order (VarPivotProvider.cap)
 * @param stressedMaxCells the cells of a view that get stressed VaR, computed scope by scope: its first in the grid's order
 * @param sparklineDates the business dates a sparkline reads (each date is the view computed again, from the pivot engine's cache when it can)
 * @param sparklineMaxCells the most cells a view draws sparklines for
 * @param whatIfRows the most rows the what-if side by side computes (each a what-if on the row's scope), the total always added
 */
@ConfigurationProperties("drishti.cube.mr")
public record MrCubeProperties(Map<String, List<String>> riskClasses, Map<String, Map<String, String>> grids, Map<String, Double> ladderLimits,
        Integer sbmMaxCells, Integer stressedMaxCells, Integer sparklineDates, Integer sparklineMaxCells, Integer whatIfRows) {

    public MrCubeProperties {
        riskClasses = riskClasses == null || riskClasses.isEmpty() ? defaultClasses() : copy(riskClasses);
        grids = grids == null || grids.isEmpty() ? defaultGrids() : copy(grids);
        ladderLimits = ladderLimits == null ? Map.of() : Map.copyOf(ladderLimits);
        sbmMaxCells = sbmMaxCells == null || sbmMaxCells < 0 ? 60 : sbmMaxCells;
        stressedMaxCells = stressedMaxCells == null || stressedMaxCells < 0 ? 100 : stressedMaxCells;
        sparklineDates = sparklineDates == null || sparklineDates < 2 ? 20 : Math.min(sparklineDates, 60);
        sparklineMaxCells = sparklineMaxCells == null || sparklineMaxCells < 0 ? 600 : sparklineMaxCells;
        whatIfRows = whatIfRows == null || whatIfRows < 1 ? 40 : whatIfRows;
    }

    /** The defaults (tests, and a server without the block). */
    public static MrCubeProperties defaults() {
        return new MrCubeProperties(null, null, null, null, null, null, null, null);
    }

    private static <V> Map<String, V> copy(Map<String, V> m) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(m));
    }

    /** The demo's product groups by class: the order is the columns' order. */
    static Map<String, List<String>> defaultClasses() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("IR", List.of("Rates", "Inflation", "Money market", "Securities financing"));
        m.put("CR", List.of("Credit", "Fixed income", "Structured"));
        m.put("FX", List.of("FX"));
        m.put("EQ", List.of("Equity", "Equity derivatives"));
        m.put("CM", List.of("Commodity"));
        return java.util.Collections.unmodifiableMap(m);
    }

    /** The bank's DV01 ladder and the FRTB GIRR vertices. */
    static Map<String, Map<String, String>> defaultGrids() {
        Map<String, Map<String, String>> g = new LinkedHashMap<>();
        Map<String, String> bank = new LinkedHashMap<>();
        bank.put("0-2Y", "0-2");
        bank.put("2-5Y", "2-5");
        bank.put("5-10Y", "5-10");
        bank.put("10Y+", "10-");
        g.put("BANK-IR-TENOR", bank);
        Map<String, String> frtb = new LinkedHashMap<>();
        frtb.put("0.25Y", "0-0.375");
        frtb.put("0.5Y", "0.375-0.75");
        frtb.put("1Y", "0.75-1.5");
        frtb.put("2Y", "1.5-2.5");
        frtb.put("3Y", "2.5-4");
        frtb.put("5Y", "4-7.5");
        frtb.put("10Y", "7.5-12.5");
        frtb.put("15Y", "12.5-17.5");
        frtb.put("20Y", "17.5-25");
        frtb.put("30Y", "25-");
        g.put("FRTB-GIRR", frtb);
        return java.util.Collections.unmodifiableMap(g);
    }
}
