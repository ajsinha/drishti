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
 * Sets the Sutra's title line ({@code pill}, {@code id}, optionally {@code with}).
 *
 * @param title the title mapping; a title needs an {@code id} expression
 */
public record SetTitle(Map<String, Object> title) implements Op {

    @Override
    public String name() {
        return "setTitle";
    }

    @Override
    public void applyTo(SutraDoc doc) {
        if (title == null || title.isEmpty()) {
            throw new OpException(OpException.MALFORMED, "setTitle needs a 'title' mapping such as { pill: Trade, id: $.id }");
        }
        doc.setTop("title", OpChecks.plain(title));
    }
}
