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

import com.ash.drishti.kernel.api.official.OfficialResults;
import com.ash.drishti.packs.OfficialReaders;
import java.util.Map;

/**
 * The banking module's official results (DOMAIN_MODULES.md J6): the lakes of the official VaR and exposure results
 * ({@code config/lakes/banking/var-official.yaml}, {@code ccr-official.yaml}) and the official kinds, each to its publisher's settings prefix, so a
 * pack's reader of them follows the publisher's format. Found with the {@link java.util.ServiceLoader} before any bean exists.
 */
public final class BankingOfficialResults implements OfficialResults {

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
