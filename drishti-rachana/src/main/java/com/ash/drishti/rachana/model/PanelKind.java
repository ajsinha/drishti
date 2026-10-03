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
 * The twenty panel kinds of the grammar. Each declares which options it requires and which it accepts,
 * so the validator can reject a Sutra before it ever meets data.
 */
public enum PanelKind {
    KV(Set.of(), Set.of("rows", "columns", "fields")),
    TABLE(Set.of("rows"), Set.of("totalLabel", "limit", "moreLabel", "link", "search", "pivot", "children", "expand")),
    TABS(Set.of("each"), Set.of("tabTitle", "layout")),
    LINE(Set.of(), Set.of("rows", "source", "x", "y", "mark", "footer", "unit", "fmt")),
    AREA(Set.of("rows"), Set.of("x", "series", "limit", "limitLabel", "unit")),
    HBAR(Set.of("rows"), Set.of("label", "value", "fmt", "tone")),
    LADDER(Set.of("rows"), Set.of("totalLabel", "highlight", "search", "pivot", "children", "expand")),
    LINKS(Set.of(), Set.of()),
    STATUS(Set.of(), Set.of("fields")),
    PROVENANCE(Set.of(), Set.of()),
    MARKDOWN(Set.of("text"), Set.of()),
    GAUGE(Set.of("value"), Set.of("max", "label", "fmt")),
    /** A grid of values over two axes (volatility surfaces, correlation matrices): heatmap, or 3D on request. */
    SURFACE(Set.of("rows", "y"), Set.of("fmt", "unit", "view")),
    /**
     * Ordered signed steps as floating bars (P&amp;L attribution): rises up, falls down, totals as full bars. Rises are
     * the theme's good colour and falls its bad one ({@code colors: gain-loss}, the default), or its positive and
     * negative ones ({@code colors: theme}, blue and orange, colour-blind friendly); totals are neutral.
     */
    WATERFALL(Set.of("rows"), Set.of("label", "value", "total", "sum", "fmt", "unit", "colors")),
    /** A distribution of numbers, binned on the server, with optional marker lines (VaR, ES, mean). */
    HISTOGRAM(Set.of("rows"), Set.of("value", "bins", "markers", "fmt", "unit")),
    /** Two measures per row as points (risk against return), optionally sized and coloured by a group. */
    SCATTER(Set.of("rows", "x", "y"), Set.of("size", "label", "group", "fmt", "xFmt", "xLabel", "yLabel")),
    /** Open, high, low and close by date, with optional volume bars. */
    CANDLESTICK(Set.of("rows"), Set.of("x", "open", "high", "low", "close", "volume", "fmt", "unit")),
    /** Entities and the relations between them (a legal-entity hierarchy); a node that names an entity opens it. */
    GRAPH(Set.of("nodes"), Set.of("edges", "label", "group", "layout")),
    /** Dated events in order, each with a status tone and a short description. */
    TIMELINE(Set.of("rows"), Set.of("date", "label", "detail", "status", "tone")),
    /** A two-dimensional aggregate of the rows (by one field, across another) with totals and an optional heat scale. */
    PIVOT(Set.of("rows", "by", "across"), Set.of("value", "agg", "fmt", "tone", "heat", "totals", "expand"));

    private final Set<String> required;
    private final Set<String> optional;

    PanelKind(Set<String> required, Set<String> optional) {
        this.required = required;
        this.optional = optional;
    }

    public Set<String> required() {
        return required;
    }

    /** Options this kind accepts but does not require. */
    public Set<String> optional() {
        return optional;
    }

    public boolean accepts(String option) {
        return required.contains(option) || optional.contains(option) || ("source".equals(option) && readsData());
    }

    /**
     * Whether panels of this kind read the entity's data, and so may read a linked entity's instead ({@code source}):
     * every kind but {@code links} and {@code provenance} (which describe the view). A markdown panel reads data through
     * the {@code ${...}} parts of its text, so it may name a source too.
     */
    public boolean readsData() {
        return this != LINKS && this != PROVENANCE;
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
