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
 * @param controls the controls the reader sets
 * @param layout the screen's grid, areas, split, tabs and sections (RACHANA_REFERENCE.md 13), or null: the panels flow
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
        String description,
        List<Control> controls,
        ScreenLayout layout) {

    public static final int MAX_STRIP = 8;

    /** A Sutra without a layout: its panels flow (RACHANA_REFERENCE.md 13). */
    public Sutra(String name, int version, String domain, Match match, Title title, List<StripItem> strip, List<Panel> panels,
            Map<String, String> keys, SourceLocation location, String description, List<Control> controls) {
        this(name, version, domain, match, title, strip, panels, keys, location, description, controls, null);
    }

    /** A Sutra without a description. */
    public Sutra(String name, int version, String domain, Match match, Title title, List<StripItem> strip, List<Panel> panels,
            Map<String, String> keys, SourceLocation location) {
        this(name, version, domain, match, title, strip, panels, keys, location, null, List.of(), null);
    }

    /** A Sutra without controls. */
    public Sutra(String name, int version, String domain, Match match, Title title, List<StripItem> strip, List<Panel> panels,
            Map<String, String> keys, SourceLocation location, String description) {
        this(name, version, domain, match, title, strip, panels, keys, location, description, List.of(), null);
    }

    public Sutra {
        strip = List.copyOf(strip);
        panels = List.copyOf(panels);
        keys = Map.copyOf(keys);
        controls = controls == null ? List.of() : List.copyOf(controls);
    }

    /** The control named {@code id}, if the Sutra declares one. */
    public java.util.Optional<Control> control(String id) {
        return controls.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    public String id() {
        return name + "@" + version;
    }
}
