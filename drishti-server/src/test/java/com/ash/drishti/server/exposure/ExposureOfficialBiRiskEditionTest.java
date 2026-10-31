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
package com.ash.drishti.server.exposure;

import org.springframework.test.context.TestPropertySource;

/**
 * Every test of {@link ExposureOfficialBiTest} again with the server running the {@code risk} edition (docs/admin/EDITIONS.md): the official exposure records in BI (the counterparty-exposure model, in the edition's datasets) work
 * under Drishti Risk exactly as on the full product. Its packs are all in the edition's list, so allow-only stays on.
 */
@TestPropertySource(properties = {"drishti.edition=risk"})
class ExposureOfficialBiRiskEditionTest extends ExposureOfficialBiTest {
}
