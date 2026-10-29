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
 * Every test of {@link ExposureApiTest} again with the server running the {@code risk} edition (docs/architecture/EDITIONS.md): exposure scopes, profiles, contributions and the what-if work
 * under Drishti Risk exactly as on the full product. Its fixtures use the retired finance example pack, which the edition does not list, so this run lets the site switch
 * allow-only off (drishti.edition-spec.packs.allow-only=false, a site override as EDITIONS_GUIDE.md shows); the commands and BI
 * datasets stay the edition's.
 */
@TestPropertySource(properties = {"drishti.edition=risk", "drishti.edition-spec.packs.allow-only=false"})
class ExposureApiRiskEditionTest extends ExposureApiTest {
}
