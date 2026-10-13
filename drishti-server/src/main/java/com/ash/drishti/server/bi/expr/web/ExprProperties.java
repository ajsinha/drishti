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
package com.ash.drishti.server.bi.expr.web;

import com.ash.drishti.server.bi.expr.Expressions;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The expression language, {@code drishti.bi.expr.*} (docs/admin/CONFIGURATION.md).
 *
 * @param legacy {@code warn}: the old expression syntax is read with a {@code DRS-9100} warning (wave A); {@code refuse}: refused with the DEL v2 text as the fix
 * @param parseCache how many parsed expressions are kept (the L0 parse cache; 0 turns it off)
 */
@ConfigurationProperties("drishti.bi.expr")
public record ExprProperties(String legacy, Integer parseCache) {

    public ExprProperties {
        legacy = legacy == null || legacy.isBlank() ? "warn" : legacy.strip().toLowerCase(Locale.ROOT);
        if (!legacy.equals("warn") && !legacy.equals("refuse")) {
            throw new IllegalArgumentException("drishti.bi.expr.legacy is warn or refuse, not '" + legacy + "'");
        }
        parseCache = parseCache == null || parseCache < 0 ? 10_000 : parseCache;
    }

    public Expressions.Legacy mode() {
        return "refuse".equals(legacy) ? Expressions.Legacy.REFUSE : Expressions.Legacy.WARN;
    }
}
