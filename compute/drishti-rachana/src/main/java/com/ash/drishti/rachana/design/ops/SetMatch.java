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

import java.util.Map;

/**
 * Sets which entities the Sutra applies to ({@code kind}, optionally {@code where} and {@code priority}).
 *
 * @param match the match mapping
 */
public record SetMatch(Map<String, Object> match) implements Op {

    @Override
    public String name() {
        return "setMatch";
    }

    @Override
    public void applyTo(SutraDoc doc) {
        if (match == null || match.isEmpty()) {
            throw new OpException(OpException.MALFORMED, "setMatch needs a 'match' mapping such as { kind: trade }");
        }
        doc.setTop("match", OpChecks.plain(match));
    }
}
