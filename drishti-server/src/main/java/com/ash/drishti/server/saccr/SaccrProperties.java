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
package com.ash.drishti.server.saccr;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SA-CCR on the server (docs/guides/SACCR_GUIDE.md section 9), {@code drishti.ccr.saccr}. The engine's switch is
 * {@code drishti.engines.sa-ccr.enabled} (config/engines/sa-ccr.yaml).
 *
 * @param parametersFile a regulation's parameter file (blank: the shipped Basel file, saccr/saccr-bcbs.yaml in drishti-compute)
 * @param tradeKind the classification rows ({@code saccr-trade})
 * @param nettingSetKind the netting sets ({@code netting-set}): counterparty, legal entity, CSA, collateral balance, enforceability
 * @param csaKind the CSAs ({@code csa}): TH, MTA, MPOR, margin frequency, disputes, illiquid collateral
 * @param collateralKind the collateral balances ({@code collateral-balance}): C (net, after haircut) and NICA
 * @param valuationKind the trade valuations ({@code trade-valuation}): each live member's MTM and its netting set
 * @param excludedGroups product groups that are not derivatives under CRE52 (securities financing, cash): a live member of these without a
 *     row is counted excluded, any other without a row unclassified (its set incomplete)
 * @param excludedProducts product types that are not derivatives (funded notes, loans, spot, physical): counted excluded likewise
 * @param riskWeight the RWA hook's risk weight when none is configured per counterparty (1.0: 100 %; informational)
 * @param maxSets the most netting sets of one counterparty the playground holds side by side
 * @param maxTrades the most classification rows one playground workbook holds
 * @param browserTrades the most trade rows the playground grid shows in the browser
 * @param cacheMinutes how long a date's inputs stay in memory after they are read
 * @param playgroundTemplate a bank's workbook template folder (blank: the built-in playground/saccr/template/)
 */
@ConfigurationProperties("drishti.ccr.saccr")
public record SaccrProperties(String parametersFile, String tradeKind, String nettingSetKind, String csaKind, String collateralKind, String valuationKind,
        java.util.List<String> excludedGroups, java.util.List<String> excludedProducts, Double riskWeight, Integer maxSets, Integer maxTrades, Integer browserTrades, Integer cacheMinutes, String playgroundTemplate) {

    public SaccrProperties {
        parametersFile = parametersFile == null ? "" : parametersFile.strip();
        tradeKind = blank(tradeKind) ? "saccr-trade" : tradeKind.strip();
        nettingSetKind = blank(nettingSetKind) ? "netting-set" : nettingSetKind.strip();
        csaKind = blank(csaKind) ? "csa" : csaKind.strip();
        collateralKind = blank(collateralKind) ? "collateral-balance" : collateralKind.strip();
        valuationKind = blank(valuationKind) ? "trade-valuation" : valuationKind.strip();
        excludedGroups = excludedGroups == null || excludedGroups.isEmpty() ? EXCLUDED_GROUPS : java.util.List.copyOf(excludedGroups);
        excludedProducts = excludedProducts == null || excludedProducts.isEmpty() ? EXCLUDED_PRODUCTS : java.util.List.copyOf(excludedProducts);
        riskWeight = riskWeight == null || !(riskWeight >= 0) ? 1.0 : riskWeight;
        maxSets = maxSets == null || maxSets < 1 ? 20 : maxSets;
        maxTrades = maxTrades == null || maxTrades < 10 ? 5000 : maxTrades;
        browserTrades = browserTrades == null || browserTrades < 10 ? 500 : browserTrades;
        cacheMinutes = cacheMinutes == null || cacheMinutes < 1 ? 10 : cacheMinutes;
        playgroundTemplate = playgroundTemplate == null ? "" : playgroundTemplate.strip();
    }

    /** The defaults (tests, and a server without the block). */
    public static SaccrProperties defaults() {
        return new SaccrProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** The product groups that are not derivatives by default: securities financing (CRE22, not SA-CCR), money market, fixed income. */
    public static final java.util.List<String> EXCLUDED_GROUPS = java.util.List.of("Securities financing", "Money market", "Fixed income");
    /** The product types that are not derivatives by default (the demo's booking systems' names). */
    public static final java.util.List<String> EXCLUDED_PRODUCTS = java.util.List.of("CLN", "REVOLVER", "TERM_LOAN", "RISK_PARTICIPATION",
            "CONVERTIBLE_BOND", "EQ_CASH", "AUTOCALLABLE", "REVERSE_CONVERTIBLE", "INFL_LINKED_BOND", "Cash", "CallNot", "Commodity", "FX_SPOT",
            "CAPITAL_PROTECTED_NOTE", "CREDIT_BASKET_NOTE", "HYBRID_FX_RATES", "RANGE_ACCRUAL_NOTE", "WORST_OF_NOTE");

    /** True when a member of this product group or type is not a derivative (counted excluded, never unclassified). */
    public boolean excluded(String productGroup, String productType) {
        return productGroup != null && excludedGroups.stream().anyMatch(g -> g.equalsIgnoreCase(productGroup.strip()))
                || productType != null && excludedProducts.stream().anyMatch(g -> g.equalsIgnoreCase(productType.strip()));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
