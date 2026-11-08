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
package com.ash.drishti.lakeconfig;

import java.util.Locale;
import java.util.Map;

/**
 * The kinds no pack serves any more, each to what replaces it (CHANGELOG.md): a file of one is refused by the data load, saying so, and a
 * connector file written before still naming one never adds its table to a lake file.
 */
public final class RemovedKinds {

    private RemovedKinds() {}

    /** Removed kind to what replaces it. */
    public static final Map<String, String> REMOVED = Map.of("risk-hierarchy", "the risk-hierarchy kind is removed: the VaR and exposure "
            + "hierarchies are built from the promoted columns of the trades' rows (DIVISION, LEGAL_ENTITY, DESK, TT_BOOK, LIMIT_LETTER on every "
            + "scenario-pv, pnl-vector and exposure-path row) as the pack's hierarchies: block configures them (VAR_GUIDE.md section 15.14); "
            + "their VaR limits are var-limit rows");

    /** What replaces a kind or table name ({@code _} and {@code -} alike, any case), or null when it is not removed. */
    public static String replacement(String name) {
        return name == null ? null : REMOVED.get(name.toLowerCase(Locale.ROOT).replace('_', '-'));
    }
}
