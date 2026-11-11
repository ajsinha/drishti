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
package com.ash.drishti.engine.bind;

import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.model.Panel;

/**
 * Binds a {@code map} panel: which country is highlighted (or whose regions are drawn), read from the document; what a click
 * opens and how the countries are shaded come from {@link MapSupport}, read across a kind before the panels are bound. The
 * shapes are the console's (vendored), so the record carries codes and figures, never geometry.
 */
final class MapBinder {

    private final ElCompiler el;

    MapBinder(ElCompiler el) {
        this.el = el;
    }

    PanelData map(Panel p, BindContext c) {
        boolean region = p.option("region").isPresent();
        String highlight = text(p, "highlight", c);
        String country = region ? text(p, "region", c) : null;
        MapSupport.Lookup found = c.maps().get(p.id());
        return new PanelData.GeoMap(region ? "region" : "world", highlight, country, found == null ? null : found.link(),
                found == null ? null : found.shade(), p.option("unit").orElse(null));
    }

    /** The text an option's expression gives for this document; null when the document lacks it or it is masked (a masked place is never named). */
    private String text(Panel p, String option, BindContext c) {
        return p.option(option).map(expr -> {
            try {
                Object v = el.compile(expr).eval(c.eval());
                return Values.isNull(v) || Values.masked(v) ? null : Values.text(v);
            } catch (RuntimeException e) {
                return null;
            }
        }).filter(s -> !s.isBlank()).orElse(null);
    }
}
