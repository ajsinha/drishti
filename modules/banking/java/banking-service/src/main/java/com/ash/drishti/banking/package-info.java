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
/**
 * The banking domain module (docs/architecture/DOMAIN_MODULES.md): VaR and ES with regulatory VaR ({@code var}), exposure and CCR
 * ({@code exposure}), FRTB-SA and sensitivities ({@code frtb}), ISDA SIMM ({@code simm}) and the official end-of-day results
 * ({@code official}); their maths is in drishti-compute-banking ({@code com.ash.drishti.banking.compute}).
 *
 * <p>The rules (ArchitectureRulesTest): the core never imports this package; this module reaches the core through its SPIs and public
 * services ({@code PivotSourceProvider}, {@code CacheGroup}, {@code CapacityFigures}, {@code HealthFigures}, {@code OfficialResults},
 * {@code ApiAlias}); another domain never imports it. Loaded by {@link com.ash.drishti.banking.BankingAutoConfiguration} only while the
 * access registry says the module is on.
 */
package com.ash.drishti.banking;
