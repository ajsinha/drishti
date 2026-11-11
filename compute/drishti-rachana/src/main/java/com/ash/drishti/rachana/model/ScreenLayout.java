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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * A screen's optional {@code layout:} (RACHANA_REFERENCE.md 13): a 12-column grid, named areas drawn as a picture of the screen, a split
 * with a draggable divider, areas shown as tabs, collapsible sections, a sticky area, the phone's stacking order and whether readers
 * may arrange panels for themselves. Without it a screen flows (main and right columns), as before.
 *
 * @param areas the picture, one string a row, one name a cell ({@code "header header"}, {@code "trades detail"}); {@code .} is an
 *     empty cell; empty when the screen uses the flow grid
 * @param columns grid units of each area column (summing to 12); empty: equal columns
 * @param split two side-by-side areas with a draggable divider, or null
 * @param tabs areas whose panels are shown as tabs, one tab a panel
 * @param sections collapsible sections of the flow grid, in order (panels name theirs with {@code section:})
 * @param sticky the area (or, without areas, the panel) that stays at the top while the page scrolls, or null
 * @param userCanArrange whether a reader may move and resize panels within the grid for themselves
 * @param phone the stacking order on a phone: areas or panel ids; empty: the picture read left to right, top to bottom
 * @param tablet the picture at tablet width, or empty (the right column drops under the main one)
 * @param tabletColumns the tablet picture's column units
 */
public record ScreenLayout(
        List<String> areas,
        List<Integer> columns,
        Split split,
        List<String> tabs,
        List<Section> sections,
        String sticky,
        boolean userCanArrange,
        List<String> phone,
        List<String> tablet,
        List<Integer> tabletColumns) {

    /** The grid every size is measured in. */
    public static final int GRID = 12;

    public ScreenLayout {
        areas = areas == null ? List.of() : List.copyOf(areas);
        columns = columns == null ? List.of() : List.copyOf(columns);
        tabs = tabs == null ? List.of() : List.copyOf(tabs);
        sections = sections == null ? List.of() : List.copyOf(sections);
        phone = phone == null ? List.of() : List.copyOf(phone);
        tablet = tablet == null ? List.of() : List.copyOf(tablet);
        tabletColumns = tabletColumns == null ? List.of() : List.copyOf(tabletColumns);
    }

    /**
     * Two areas side by side with a divider between them.
     *
     * @param left the area on the left of the divider
     * @param right the area on its right
     * @param resizable whether the reader may drag the divider (default true)
     * @param remember {@code user}: the position is kept per reader; {@code none}: it resets on every visit
     */
    public record Split(String left, String right, boolean resizable, String remember) {}

    /**
     * A collapsible section of the flow grid.
     *
     * @param id the name panels give in {@code section:}
     * @param title the heading
     * @param collapsed whether it starts closed (a reader's choice is kept per reader)
     */
    public record Section(String id, String title, boolean collapsed) {}

    /** Whether the screen places panels in named areas (else the flow grid). */
    public boolean hasAreas() {
        return !areas.isEmpty();
    }

    /** The area names of a picture, in reading order. */
    public static List<String> names(List<String> picture) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String row : picture) {
            for (String cell : cells(row)) {
                if (!".".equals(cell)) {
                    out.add(cell);
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** The cells of one row of a picture. */
    public static List<String> cells(String row) {
        String t = row == null ? "" : row.trim();
        return t.isEmpty() ? List.of() : List.of(t.split("\\s+"));
    }

    /** The area names, in reading order. */
    public List<String> areaNames() {
        return names(areas);
    }

    /** The section with this id, or null. */
    public Section section(String id) {
        return sections.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    /** As the view model carries it to the console (plain maps; the engine adds each panel's place). */
    public Map<String, Object> toView() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (hasAreas()) {
            m.put("areas", areas);
        }
        if (!columns.isEmpty()) {
            m.put("columns", columns);
        }
        if (split != null) {
            m.put("split", Map.of("between", List.of(split.left(), split.right()), "resizable", split.resizable(), "remember", split.remember()));
        }
        if (!tabs.isEmpty()) {
            m.put("tabs", tabs);
        }
        if (!sections.isEmpty()) {
            List<Map<String, Object>> s = new ArrayList<>();
            for (Section x : sections) {
                s.add(Map.of("id", x.id(), "title", x.title() == null ? x.id() : x.title(), "collapsed", x.collapsed()));
            }
            m.put("sections", s);
        }
        if (sticky != null) {
            m.put("sticky", sticky);
        }
        m.put("arrange", userCanArrange);
        if (!phone.isEmpty()) {
            m.put("phone", phone);
        }
        if (!tablet.isEmpty()) {
            m.put("tablet", tablet);
            if (!tabletColumns.isEmpty()) {
                m.put("tabletColumns", tabletColumns);
            }
        }
        return m;
    }
}
