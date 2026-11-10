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
package com.ash.drishti.banking;

import com.ash.drishti.kernel.api.modules.ApiAlias;
import com.ash.drishti.server.modules.ConditionalOnModule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * The banking module in the server (docs/architecture/DOMAIN_MODULES.md): found on the class path through
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}, loaded only while the access registry says the
 * module is on ({@link ConditionalOnModule}). Off, nothing below loads: no bean, engine, job or API of VaR, exposure, FRTB-SA, SIMM,
 * sensitivities, the MR cube or the official results.
 *
 * <p>The API moved under {@code /api/v1/risk}; the old prefixes are answered as the new ones for this release ({@link ApiAlias}), then
 * removed.
 */
@AutoConfiguration
@ConditionalOnModule("banking")
@EnableConfigurationProperties(RiskLimits.class)         // drishti.risk.limit-near: one rule for every limit (RiskLimits)
// as the application's own scan: test configurations excluded (TypeExcludeFilter), and this class itself not scanned twice
@ComponentScan(basePackages = "com.ash.drishti.banking", excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = BankingAutoConfiguration.class)})
public class BankingAutoConfiguration {

    @Bean
    ApiAlias bankingVarPath() {
        return new ApiAlias("/api/v1/var", "/api/v1/risk/var");
    }

    @Bean
    ApiAlias bankingExposurePath() {
        return new ApiAlias("/api/v1/exposure", "/api/v1/risk/exposure");
    }

    @Bean
    ApiAlias bankingFrtbPath() {
        return new ApiAlias("/api/v1/frtb", "/api/v1/risk/frtb");
    }

    @Bean
    ApiAlias bankingSimmPath() {
        return new ApiAlias("/api/v1/simm", "/api/v1/risk/simm");
    }

    @Bean
    ApiAlias bankingSensitivitiesPath() {
        return new ApiAlias("/api/v1/sensitivities", "/api/v1/risk/sensitivities");
    }

    @Bean
    ApiAlias bankingRiskFactorsPath() {
        return new ApiAlias("/api/v1/risk-factors", "/api/v1/risk/risk-factors");
    }

    @Bean
    ApiAlias bankingMrCubePath() {
        return new ApiAlias("/api/v1/pivot/mr", "/api/v1/risk/mr");
    }
}
