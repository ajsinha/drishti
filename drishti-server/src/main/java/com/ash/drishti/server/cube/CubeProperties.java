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
package com.ash.drishti.server.cube;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The cubes (MR_CUBE_GUIDE.md section 9), {@code drishti.cube}: the shared core's budget and cache, and each cube's switch and settings
 * ({@code drishti.cube.mr}; the counterparty cube's {@code drishti.cube.ccr} comes with it).
 *
 * @param maxCells the most cells one view computes, its row and column totals and grand total counted (5,000): a larger view is refused
 *     with its count ({@code DRS-5602}), never cut short
 * @param busyCells a view of more cells asks busy protection first ({@code DRS-9506} while the server is short of memory or threads)
 * @param cacheMb the cell cache's own limit in MB (its aggregates by data version, viewer and lens); the one memory budget may hold it to less
 * @param provenanceKept the answers whose provenance ids are kept for the popover (the least recently asked leave first)
 * @param sparklineMax the most cells a view draws sparklines for (each sparkline date is the view computed again on that date)
 * @param mr the market-risk cube
 */
@ConfigurationProperties("drishti.cube")
public record CubeProperties(Integer maxCells, Integer busyCells, Integer cacheMb, Integer provenanceKept, Integer sparklineMax, Mr mr) {

    /**
     * The market-risk cube, {@code drishti.cube.mr} (engine {@code mr-cube}, COMPUTE_ENGINES.md: needs {@code var}, uses
     * {@code sensitivities} and {@code frtb-sa}).
     *
     * @param enabled the cube, its API ({@code /api/v1/cube/mr/...}), the {@code MCUBE} word and the page; off: nothing is built and the API
     *     answers {@code 404 DRS-5603} in words ({@code DRISHTI_MR_CUBE_ENABLED})
     * @param riskClasses the derived dimension {@code RISK_CLASS}: a class to the product groups whose positions are in it (a position's
     *     primary class until per-class vectors are delivered); a product group in none is {@code OTHER}
     * @param grids the sensitivity ladders' bucket grids: a grid to its buckets, each {@code label: from-to} in years ({@code 10-} open)
     * @param ladderLimits two-sided DV01 limits per grid bucket ({@code USD 2-5Y: 1500000}); none: the ladder shows no limit bars and says so
     * @param sbmMaxCells the most cells whose FRTB-SA SBM capital one view computes (each a run of the FRTB-SA engine on the cell's scope)
     * @param stressedMaxCells the most cells whose stressed VaR one view computes scope by scope
     */
    public record Mr(Boolean enabled, Map<String, List<String>> riskClasses, Map<String, Map<String, String>> grids,
            Map<String, Double> ladderLimits, Integer sbmMaxCells, Integer stressedMaxCells) {

        public Mr {
            enabled = enabled == null || enabled;
            riskClasses = riskClasses == null || riskClasses.isEmpty() ? defaultClasses() : Map.copyOf(riskClasses);
            grids = grids == null || grids.isEmpty() ? defaultGrids() : Map.copyOf(grids);
            ladderLimits = ladderLimits == null ? Map.of() : Map.copyOf(ladderLimits);
            sbmMaxCells = sbmMaxCells == null || sbmMaxCells < 0 ? 60 : sbmMaxCells;
            stressedMaxCells = stressedMaxCells == null || stressedMaxCells < 0 ? 100 : stressedMaxCells;
        }

        /** The demo's product groups by class: the order is the columns' order. */
        static Map<String, List<String>> defaultClasses() {
            Map<String, List<String>> m = new LinkedHashMap<>();
            m.put("IR", List.of("Rates", "Inflation", "Money market", "Securities financing"));
            m.put("CR", List.of("Credit", "Fixed income", "Structured"));
            m.put("FX", List.of("FX"));
            m.put("EQ", List.of("Equity", "Equity derivatives"));
            m.put("CM", List.of("Commodity"));
            return m;
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
            return g;
        }
    }

    public CubeProperties {
        maxCells = maxCells == null || maxCells < 1 ? 5000 : maxCells;
        busyCells = busyCells == null || busyCells < 1 ? 600 : busyCells;
        cacheMb = cacheMb == null || cacheMb < 1 ? 64 : cacheMb;
        provenanceKept = provenanceKept == null || provenanceKept < 10 ? 500 : provenanceKept;
        sparklineMax = sparklineMax == null || sparklineMax < 0 ? 600 : sparklineMax;
        mr = mr == null ? new Mr(null, null, null, null, null, null) : mr;
    }

    /** The defaults (tests, and a server without the block). */
    public static CubeProperties defaults() {
        return new CubeProperties(null, null, null, null, null, null);
    }
}
