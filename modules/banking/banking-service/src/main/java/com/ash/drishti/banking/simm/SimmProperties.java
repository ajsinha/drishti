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
package com.ash.drishti.banking.simm;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ISDA SIMM on the server (docs/guides/SIMM_GUIDE.md section 9), {@code drishti.simm}.
 *
 * @param enabled the engine, its API, the SIMM screen and the playground (off: every request answers it is switched off)
 * @param parametersDir the folder of the licensed parameter files, one YAML a SIMM version (none shipped: ISDA's numbers are licensed)
 * @param defaultVersion the version for a CSA that names none and for analytic scopes (blank: the installed version with the latest
 *     effective date)
 * @param nettingSetKind the netting sets and their trades ({@code netting-set})
 * @param csaKind the CSAs, whose SIMM terms (version, regimes, calculation currency, add-ons) are read when present ({@code csa})
 * @param addOnKind the notional add-on lines ({@code simm-addon}; none served: no notional add-on)
 * @param externalKind the counterparty's call and CRIF for the comparison ({@code crif-external}; none served: "no call received")
 * @param storedKind the stored SIMM figures shown, labelled, for the netting sets without sensitivities ({@code simm})
 * @param productClasses the product class of a record's {@code PRODUCT_GROUP} (the bank's mapping, paragraph 6)
 * @param defaultProductClass the product class of a group not in the mapping
 * @param maxFactors the most factors one playground grid and workbook holds
 * @param recipesDir where saved playground recipes are kept
 * @param cacheMinutes how long a date's netting sets and CSAs stay in memory after they are read
 * @param playgroundTemplate a bank's workbook template folder (blank: the built-in {@code playground/simm/template/})
 */
@ConfigurationProperties("drishti.simm")
public record SimmProperties(Boolean enabled, String parametersDir, String defaultVersion, String nettingSetKind, String csaKind, String addOnKind,
        String externalKind, String storedKind, Map<String, String> productClasses, String defaultProductClass, Integer maxFactors, String recipesDir,
        Integer cacheMinutes, String playgroundTemplate) {

    /** The mapping when none is configured: rates, inflation, money market and FX in RatesFX; the others by their name. */
    public static final Map<String, String> PRODUCT_CLASSES = defaultsMap();

    public SimmProperties {
        enabled = enabled == null || enabled;
        parametersDir = blank(parametersDir) ? "./config/simm" : parametersDir.strip();
        defaultVersion = defaultVersion == null ? "" : defaultVersion.strip();
        nettingSetKind = blank(nettingSetKind) ? "netting-set" : nettingSetKind.strip();
        csaKind = blank(csaKind) ? "csa" : csaKind.strip();
        addOnKind = blank(addOnKind) ? "simm-addon" : addOnKind.strip();
        externalKind = blank(externalKind) ? "crif-external" : externalKind.strip();
        storedKind = blank(storedKind) ? "simm" : storedKind.strip();
        Map<String, String> pc = new LinkedHashMap<>();
        (productClasses == null || productClasses.isEmpty() ? PRODUCT_CLASSES : productClasses).forEach((k, v) -> pc.put(k.strip().toUpperCase(java.util.Locale.ROOT),
                v.strip()));
        productClasses = Map.copyOf(pc);
        defaultProductClass = blank(defaultProductClass) ? "RatesFX" : defaultProductClass.strip();
        maxFactors = maxFactors == null || maxFactors < 10 ? 1500 : maxFactors;
        recipesDir = blank(recipesDir) ? "./data/simm/recipes" : recipesDir.strip();
        cacheMinutes = cacheMinutes == null || cacheMinutes < 1 ? 10 : cacheMinutes;
        playgroundTemplate = playgroundTemplate == null ? "" : playgroundTemplate.strip();
    }

    private static Map<String, String> defaultsMap() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String g : new String[] {"Rates", "Inflation", "Money market", "FX", "Fixed income", "Securities financing", "Structured"}) {
            m.put(g, "RatesFX");
        }
        m.put("Credit", "Credit");
        m.put("Equity", "Equity");
        m.put("Commodity", "Commodity");
        return Map.copyOf(m);
    }

    /** The defaults (tests, and a server without the block). */
    public static SimmProperties defaults() {
        return new SimmProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** The product class of a record's product group. */
    public String productClass(String productGroup) {
        String v = productGroup == null ? null : productClasses.get(productGroup.strip().toUpperCase(java.util.Locale.ROOT));
        return v == null ? defaultProductClass : v;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
