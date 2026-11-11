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
package com.ash.drishti.engine.design;

import com.ash.drishti.rachana.model.Area;
import com.ash.drishti.rachana.model.Column;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.PanelKind;
import com.ash.drishti.rachana.model.SourceLocation;
import java.util.List;
import java.util.Map;

/**
 * One way to show a field: a panel kind with its options filled from the shape, how good a fit it is, and why. The first
 * choice becomes the drafted panel; the rest travel with it as alternatives the designer can swap in, and
 * {@code suggest} returns the list for a field dropped on the canvas.
 *
 * @param kind the panel kind id ({@code line}, {@code pivot}...)
 * @param score fit, 0 to 1; higher is better
 * @param reason why this kind suits the data, in a sentence
 * @param area the column it belongs in
 * @param title a suggested panel title
 * @param options the kind's options, as the Sutra spells them ({@code rows}, {@code x}, {@code by}...)
 * @param columns explicit columns (tables, kv, surface), or for {@code tabs} the columns of the body
 */
public record PanelChoice(String kind, double score, String reason, @com.fasterxml.jackson.annotation.JsonIgnore Area area, String title, Map<String, Object> options,
        List<Column> columns) {

    private static final SourceLocation DRAFT = new SourceLocation("auto-design", 0, 0);

    public PanelChoice {
        options = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(options));
        columns = List.copyOf(columns);
    }

    /** The column as the API says it: {@code main} or {@code right}. */
    @com.fasterxml.jackson.annotation.JsonProperty("area")
    public String areaName() {
        return area.name().toLowerCase(java.util.Locale.ROOT);
    }

    PanelChoice withScore(double s, String why) {
        return new PanelChoice(kind, s, why, area, title, options, columns);
    }

    PanelChoice withTitle(String t) {
        return new PanelChoice(kind, score, reason, area, t, options, columns);
    }

    PanelChoice inArea(Area a) {
        return new PanelChoice(kind, score, reason, a, title, options, columns);
    }

    /** The Rachana panel this choice describes. */
    Panel toPanel(String id) {
        PanelKind k = PanelKind.parse(kind).orElseThrow();
        if (k == PanelKind.TABS) {
            Panel body = new Panel("body", PanelKind.KV, null, null, null, Area.MAIN, true, columns, null, Map.of(), DRAFT);
            return new Panel(id, k, title, null, null, area, true, List.of(), body, options, DRAFT);
        }
        return new Panel(id, k, title, null, null, area, true, columns, null, options, DRAFT);
    }
}
