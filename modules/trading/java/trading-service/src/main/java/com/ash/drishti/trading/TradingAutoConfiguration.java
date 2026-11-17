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
package com.ash.drishti.trading;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * The trading module's server part (docs/architecture/KERNEL_AND_MODULES.md section 3.3; THIN_CONSOLE_DESIGN.md step S8a): the manifest's
 * {@code java.spring}, made by the module host in trading's own child context when the server loads {@code trading-1.0.0.dmod}. It
 * brings the Trading landings' figures ({@code /api/v1/trading/landing}); not loaded, nothing below exists. It reads the kernel and the
 * other modules only through their API, as the person ({@code Kernel}, typed).
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.ash.drishti.trading", excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = TradingAutoConfiguration.class)})
public class TradingAutoConfiguration {
}
