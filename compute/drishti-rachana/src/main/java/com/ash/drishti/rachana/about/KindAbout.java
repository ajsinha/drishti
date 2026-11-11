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
package com.ash.drishti.rachana.about;

import com.ash.drishti.rachana.el.Template;
import java.util.Map;

/**
 * One kind's entry in one pack's about file, after parsing. Immutable.
 *
 * @param title the kind's plain title, or null
 * @param about the page's template, or null
 * @param guide the pack guide section ({@code slug[#anchor]}), or null
 * @param glossary the field entries by field path
 * @param panels the panels' templates by panel id
 */
public record KindAbout(String title, Template about, String guide, Map<String, GlossaryEntry> glossary, Map<String, Template> panels) {

    public KindAbout {
        glossary = Map.copyOf(glossary);
        panels = Map.copyOf(panels);
    }
}
