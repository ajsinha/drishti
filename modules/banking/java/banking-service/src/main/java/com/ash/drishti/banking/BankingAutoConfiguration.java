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

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * The banking module's configuration (docs/architecture/KERNEL_AND_MODULES.md section 3.3): the manifest's {@code java.spring}, made by the
 * module host in banking's own child context when the server loads {@code banking-1.0.0.dmod}. Not loaded (removed, or off by the site's
 * switch), nothing below exists: no bean, engine, job or API of VaR, exposure, FRTB-SA, SIMM, sensitivities, the MR cube or the official
 * results.
 *
 * <p>The API is under {@code /api/v1/risk}.
 */
@AutoConfiguration
@EnableConfigurationProperties(RiskLimits.class)         // drishti.risk.limit-near: one rule for every limit (RiskLimits)
// as the application's own scan: test configurations excluded (TypeExcludeFilter), and this class itself not scanned twice
@ComponentScan(basePackages = "com.ash.drishti.banking", excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = BankingAutoConfiguration.class)})
public class BankingAutoConfiguration {
}
