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
package com.ash.drishti.rachana.model;

import java.util.List;
import java.util.Map;

/**
 * A parsed, validated layout definition. Immutable; identified by {@code name@version}.
 *
 * @param name the Sutra name, for example {@code irs-vanilla}
 * @param version positive integer; old versions stay loadable
 * @param domain grouping folder, for example {@code rates}
 * @param match which entities it applies to
 * @param title the title line
 * @param strip header key figures (at most eight)
 * @param panels the panels in declaration order
 * @param keys function key to action ({@code link(...)}, {@code raw}, {@code impact}, or a panel id)
 * @param location the file it came from
 * @param description the author's one-paragraph plain text (never evaluated), or null
 */
public record Sutra(
        String name,
        int version,
        String domain,
        Match match,
        Title title,
        List<StripItem> strip,
        List<Panel> panels,
        Map<String, String> keys,
        SourceLocation location,
        String description) {

    public static final int MAX_STRIP = 8;

    /** A Sutra without a description. */
    public Sutra(String name, int version, String domain, Match match, Title title, List<StripItem> strip, List<Panel> panels,
            Map<String, String> keys, SourceLocation location) {
        this(name, version, domain, match, title, strip, panels, keys, location, null);
    }

    public Sutra {
        strip = List.copyOf(strip);
        panels = List.copyOf(panels);
        keys = Map.copyOf(keys);
    }

    public String id() {
        return name + "@" + version;
    }
}
