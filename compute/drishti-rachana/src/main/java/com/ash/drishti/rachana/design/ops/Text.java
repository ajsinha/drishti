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

/**
 * Replaces the whole Sutra text (the YAML tab). Comments and order are then whatever the new text has; it must be a valid
 * Sutra, or the operation is refused and the text stays as it was.
 *
 * @param yaml the new text
 */
public record Text(String yaml) implements Op {

    @Override
    public String name() {
        return "text";
    }

    @Override
    public void applyTo(SutraDoc doc) {
        if (yaml == null) {
            throw new OpException(OpException.MALFORMED, "text needs 'yaml': the whole Sutra text");
        }
        doc.replaceAll(yaml);
    }
}
