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
package com.ash.drishti.server.frtb;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Position sensitivities and FRTB-SA on the server (FRTB_SA_GUIDE.md section 9), {@code drishti.frtb}.
 *
 * @param enabled the engine, its API and the playground
 * @param parametersDir the folder of the parameter files {@code frtb-sa-sbm.yaml} and {@code frtb-sa-drc-rrao.yaml} (the market-risk pack's
 *     {@code capital/})
 * @param sensitivityKind the kind of the position sensitivities ({@code position-sensitivity})
 * @param factorKind the kind of the factor dictionary ({@code risk-factor})
 * @param defaultRiskKind the kind of the DRC inputs ({@code default-risk}); none served: the DRC is missing, never 0
 * @param residualRiskKind the kind of the RRAO inputs ({@code residual-risk}); none served: derived from the positions' product types and
 *     notionals when {@code rraoFromPositions}
 * @param rraoFromPositions derive the RRAO from each position's {@code PRODUCT_TYPE} (the parameter file's mapping) and its notional
 * @param maxFactors the most factors one playground grid and workbook holds (beyond it: a class at a time)
 * @param recipesDir where saved playground recipes are kept
 * @param cacheMinutes how long a date's sensitivities stay in memory after they are read
 * @param playgroundTemplate a bank's workbook template folder (blank: the built-in {@code playground/frtb-sa/template/})
 */
@ConfigurationProperties("drishti.frtb")
public record FrtbProperties(Boolean enabled, String parametersDir, String sensitivityKind, String factorKind, String defaultRiskKind,
        String residualRiskKind, Boolean rraoFromPositions, Integer maxFactors, String recipesDir, Integer cacheMinutes,
        String playgroundTemplate) {

    public FrtbProperties {
        enabled = enabled == null || enabled;
        parametersDir = blank(parametersDir) ? "./config/packs/market-risk/capital" : parametersDir.strip();
        sensitivityKind = blank(sensitivityKind) ? "position-sensitivity" : sensitivityKind.strip();
        factorKind = blank(factorKind) ? "risk-factor" : factorKind.strip();
        defaultRiskKind = blank(defaultRiskKind) ? "default-risk" : defaultRiskKind.strip();
        residualRiskKind = blank(residualRiskKind) ? "residual-risk" : residualRiskKind.strip();
        rraoFromPositions = rraoFromPositions == null || rraoFromPositions;
        maxFactors = maxFactors == null || maxFactors < 10 ? 2000 : maxFactors;
        recipesDir = blank(recipesDir) ? "./data/frtb/recipes" : recipesDir.strip();
        cacheMinutes = cacheMinutes == null || cacheMinutes < 1 ? 10 : cacheMinutes;
        playgroundTemplate = playgroundTemplate == null ? "" : playgroundTemplate.strip();
    }

    /** The defaults (tests, and a server without the block). */
    public static FrtbProperties defaults() {
        return new FrtbProperties(null, null, null, null, null, null, null, null, null, null, null);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
