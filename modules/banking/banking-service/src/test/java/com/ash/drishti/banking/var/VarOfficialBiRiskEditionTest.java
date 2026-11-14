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

import org.springframework.test.context.TestPropertySource;

/**
 * Every test of {@link VarOfficialBiTest} again with the server running Drishti Risk (drishti.distribution=risk, docs/architecture/DISTRIBUTIONS.md): the official records in BI (the market-risk-var model and report, both in the edition's datasets) work
 * under Drishti Risk exactly as on the full product.
 */
@TestPropertySource(properties = {"drishti.distribution=risk", "drishti.distributions-dir=../../../config/distributions", "drishti.module-manifests-dir=../../../modules"})
class VarOfficialBiRiskEditionTest extends VarOfficialBiTest {
}
