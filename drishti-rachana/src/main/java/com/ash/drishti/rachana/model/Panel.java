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
import java.util.Optional;

/**
 * One panel of a view.
 *
 * @param id unique within the Sutra
 * @param kind the panel kind
 * @param title header text (may contain Rachana-EL in {@code ${...}})
 * @param key function key that focuses it ({@code F2}), or null
 * @param code short code shown at the header's right ({@code CRV}, {@code REFS}), or null
 * @param area main or right column
 * @param infer let the inference engine complete this panel (columns, fields)
 * @param columns explicit columns, possibly empty
 * @param body the nested panel for {@code tabs}, or null
 * @param parts the panels shown inside each tab of a {@code tabs} panel ({@code panels:}), bound with {@code @} the tab's element; empty otherwise
 * @param options kind-specific options (strings are Rachana-EL where the kind says so), and the layout sizes
 *     {@code span} and {@code height} every top-level panel may set
 * @param location where it was declared
 */
public record Panel(
        String id,
        PanelKind kind,
        String title,
        String key,
        String code,
        Area area,
        boolean infer,
        List<Column> columns,
        Panel body,
        Map<String, Object> options,
        SourceLocation location,
        List<Panel> parts,
        Wiring wiring) {

    /** A panel without Sutra Next wiring ({@code wiring}: {@code source:} as a mapping, {@code emits:}, {@code waits:}, {@code children:} as a list). */
    public Panel(String id, PanelKind kind, String title, String key, String code, Area area, boolean infer, List<Column> columns,
            Panel body, Map<String, Object> options, SourceLocation location, List<Panel> parts) {
        this(id, kind, title, key, code, area, infer, columns, body, options, location, parts, Wiring.NONE);
    }

    /** A panel with no nested panels. */
    public Panel(String id, PanelKind kind, String title, String key, String code, Area area, boolean infer, List<Column> columns,
            Panel body, Map<String, Object> options, SourceLocation location) {
        this(id, kind, title, key, code, area, infer, columns, body, options, location, List.of());
    }

    public Panel {
        columns = List.copyOf(columns);
        parts = parts == null ? List.of() : List.copyOf(parts);
        wiring = wiring == null ? Wiring.NONE : wiring;
        options = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(options));
    }

    /** The panel's width in columns of a 12-column grid ({@code span: 6}); absent: the whole column. */
    public static final String SPAN = "span";
    /** The panel's height in grid rows ({@code height: 8}); absent: as tall as its content. */
    public static final String HEIGHT = "height";
    public static final int MAX_SPAN = 12;
    /** A named area of the screen's {@code layout.areas} ({@code area: trades}); main and right stay {@link #area()}. */
    public static final String GRID_AREA = "area";
    /** The panel's sort key within its area or the flow ({@code order: 99}); absent: as written. */
    public static final String ORDER = "order";
    /** The collapsible section of {@code layout.sections} the panel sits in. */
    public static final String SECTION = "section";
    /** Whether the reader may fold the panel to its heading (remembered per reader). */
    public static final String COLLAPSIBLE = "collapsible";
    /** A row or a bar that sets a control when clicked: {@code { filter: [panel ids], by: <control id>, field: <row field> }}. */
    public static final String ON_SELECT = "on-select";
    /** A condition on each row, read with the controls: {@code where: "oneOf(@.leg, ctl.legs)"}. */
    public static final String WHERE = "where";
    /**
     * {@code when}: an expression over the document; when it is not true the panel is left out of the view (a physical deal's
     * delivery panel only on a physical deal, one Sutra for every sub type of a product). Any kind takes it, inside a tab too.
     */
    public static final String WHEN = "when";
    public static final int MAX_HEIGHT = 24;

    /** The interactive pivot a table or ladder opts into ({@code pivot: true} or a mapping); see {@link PivotSpec}. */
    public static final String PIVOT = "pivot";

    /**
     * The panel's pivot, read against its columns: empty when the panel does not opt in (no {@code pivot}, or
     * {@code pivot: false}) or the option cannot be read (the Sutra would not have loaded).
     */
    public Optional<PivotSpec> pivot() {
        Object raw = options.get(PIVOT);
        if (raw == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(PivotSpec.parse(raw, PivotSpec.Mode.PANEL, PivotSpec.fromColumns(columns)).spec());
    }

    /** {@code span} when the Sutra sets it. */
    public Optional<Integer> span() {
        return options.get(SPAN) instanceof Number n ? Optional.of(n.intValue()) : Optional.empty();
    }

    /** {@code height} when the Sutra sets it. */
    public Optional<Integer> height() {
        return options.get(HEIGHT) instanceof Number n ? Optional.of(n.intValue()) : Optional.empty();
    }

    /** {@code refresh} when the panel (or its Sutra, for a top-level panel) sets it (SUTRA_NEXT.md 9). */
    public Optional<Refresh> refresh() {
        return Refresh.fromOption(options.get(Refresh.KEY));
    }

    public Optional<String> option(String name) {
        Object v = options.get(name);
        return v == null ? Optional.empty() : Optional.of(v.toString());
    }
}
