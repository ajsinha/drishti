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
 * @param options kind-specific options (strings are Rachana-EL where the kind says so)
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
        SourceLocation location) {

    public Panel {
        columns = List.copyOf(columns);
        options = Map.copyOf(options);
    }

    public Optional<String> option(String name) {
        Object v = options.get(name);
        return v == null ? Optional.empty() : Optional.of(v.toString());
    }
}
