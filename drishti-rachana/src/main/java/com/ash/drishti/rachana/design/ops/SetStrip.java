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
package com.ash.drishti.rachana.design.ops;

import java.util.List;
import java.util.Map;

/**
 * Sets the header key figures (at most eight; an empty list removes the strip).
 *
 * @param items each {@code { label, bind, fmt, tone, emphasis }}
 */
public record SetStrip(List<Map<String, Object>> items) implements Op {

    @Override
    public String name() {
        return "setStrip";
    }

    @Override
    public void applyTo(SutraDoc doc) {
        if (items == null) {
            throw new OpException(OpException.MALFORMED, "setStrip needs 'items': a list of { label, bind } (empty to remove the strip)");
        }
        doc.setTop("strip", OpChecks.plain(items));
    }
}
