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
package com.ash.drishti.packs;

import java.util.Map;

/** The banking module's official results, as its provider declares them, for this module's own tests (the module itself is not here). */
public final class TestOfficialResults implements OfficialReaders.OfficialResults {

    @Override
    public Map<String, String> lakes() {
        return Map.of("var-official", "drishti.var.official", "ccr-official", "drishti.ccr.exposure.official");
    }

    @Override
    public Map<String, String> kinds() {
        return Map.of("var-official", "drishti.var.official", "exposure-official", "drishti.ccr.exposure.official", "exposure-official-cpty",
                "drishti.ccr.exposure.official");
    }
}
