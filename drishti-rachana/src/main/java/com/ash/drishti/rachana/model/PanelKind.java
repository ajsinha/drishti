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

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The thirteen panel kinds of the grammar. Each declares which options it requires and which it accepts,
 * so the validator can reject a Sutra before it ever meets data.
 */
public enum PanelKind {
    KV(Set.of(), Set.of("rows", "columns", "fields")),
    TABLE(Set.of("rows"), Set.of("totalLabel", "limit", "moreLabel", "link", "search")),
    TABS(Set.of("each"), Set.of("tabTitle", "layout")),
    LINE(Set.of(), Set.of("rows", "source", "x", "y", "mark", "footer", "unit", "fmt")),
    AREA(Set.of("rows"), Set.of("x", "series", "limit", "limitLabel", "unit")),
    HBAR(Set.of("rows"), Set.of("label", "value", "fmt", "tone")),
    LADDER(Set.of("rows"), Set.of("totalLabel", "highlight")),
    LINKS(Set.of(), Set.of()),
    STATUS(Set.of(), Set.of("fields")),
    PROVENANCE(Set.of(), Set.of()),
    MARKDOWN(Set.of("text"), Set.of()),
    GAUGE(Set.of("value"), Set.of("max", "label", "fmt")),
    /** A grid of values over two axes (volatility surfaces, correlation matrices): heatmap, or 3D on request. */
    SURFACE(Set.of("rows", "y"), Set.of("fmt", "unit", "view"));

    private final Set<String> required;
    private final Set<String> optional;

    PanelKind(Set<String> required, Set<String> optional) {
        this.required = required;
        this.optional = optional;
    }

    public Set<String> required() {
        return required;
    }

    public boolean accepts(String option) {
        return required.contains(option) || optional.contains(option);
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<PanelKind> parse(String s) {
        for (PanelKind k : values()) {
            if (k.id().equals(s)) {
                return Optional.of(k);
            }
        }
        return Optional.empty();
    }
}
