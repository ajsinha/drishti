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
package com.ash.drishti.marketdata;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * The market-data module's server part (docs/architecture/KERNEL_AND_MODULES.md section 3.3; THIN_CONSOLE_DESIGN.md step S8a): the
 * manifest's {@code java.spring}, made by the module host in market-data's own child context. It brings the Market landings' figures
 * ({@code /api/v1/market-data/landing}); it reads the kernel only through its API, as the person ({@code KernelCalls}).
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.ash.drishti.marketdata", excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = MarketDataAutoConfiguration.class)})
public class MarketDataAutoConfiguration {
}
