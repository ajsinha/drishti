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
package com.ash.drishti.engine.view;

import com.ash.drishti.engine.view.ViewModel.Cell;
import java.util.List;

/**
 * Decides whether a bound panel has anything to show. Real documents are often incomplete: a Sutra may ask for
 * rows, points or fields the document does not have. Such a panel is still returned, flagged empty, and the
 * console renders it as "No data available" instead of an empty frame or a broken chart.
 */
public final class Emptiness {

    private Emptiness() {
    }

    public static boolean of(PanelData d) {
        return switch (d) {
            case null -> true;
            case PanelData.Fields f -> blank(f.fields());
            case PanelData.Table t -> t.rows() == null || t.rows().isEmpty();
            case PanelData.Tabs t -> t.tabs() == null || t.tabs().isEmpty() || t.tabs().stream().allMatch(x -> blank(x.fields()));
            case PanelData.Chart c -> c.x() == null || c.x().isEmpty() || c.series() == null
                    || c.series().stream().allMatch(s -> s.values() == null || s.values().stream().noneMatch(Emptiness::finite));
            case PanelData.Bars b -> b.bars() == null || b.bars().isEmpty();
            case PanelData.Links l -> l.links() == null || l.links().isEmpty();
            case PanelData.Text t -> t.text() == null || t.text().isBlank();
            case PanelData.Gauge g -> !Double.isFinite(g.value());
            case PanelData.Surface s -> s.z() == null || s.z().stream().allMatch(r -> r == null || r.stream().noneMatch(Emptiness::finite));
        };
    }

    /** True when every cell is missing (no text, or the formatter's dash). */
    static boolean blank(List<Cell> cells) {
        return cells == null || cells.isEmpty() || cells.stream().allMatch(c -> c.text() == null || c.text().isBlank() || "—".equals(c.text()));
    }

    static boolean finite(Double v) {
        return v != null && Double.isFinite(v);
    }
}
