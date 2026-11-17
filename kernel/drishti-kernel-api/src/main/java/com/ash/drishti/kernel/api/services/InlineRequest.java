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
package com.ash.drishti.kernel.api.services;

import com.ash.drishti.kernel.api.module.InlineFigures;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Rows for the pivot engine: field for field the body of {@code POST /api/v1/pivot/inline}. The record holds the fields only; the rules
 * of the body (field names and types, the row limit) are read once, by {@code InlinePivot} of drishti-bicompute, when the engine takes it.
 *
 * @param fields the fields of the rows
 * @param rows   the rows, one value per field (a number, text or null)
 * @param view   the arrangement ({@code rows}, {@code columns}, {@code values}, {@code expandTo}, {@code top}), as the engine reads it
 */
public record InlineRequest(List<Field> fields, List<List<Object>> rows, Map<String, Object> view) {

    /** The engine's most inline rows: {@link InlineFigures#MAX_ROWS}. */
    public static final int MAX_ROWS = InlineFigures.MAX_ROWS;

    /** Copies the lists and the view (null fields and rows become empty); the view must be there. */
    public InlineRequest {
        fields = fields == null ? List.of() : List.copyOf(fields);
        rows = rows == null ? List.of() : List.copyOf(rows);
        Objects.requireNonNull(view, "view");
        view = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(view));
    }

    /**
     * One field of the rows, as the body names it.
     *
     * @param name  the field's name
     * @param type  {@code string}, {@code number} or {@code date}
     * @param label its label (the name when null)
     */
    public record Field(String name, String type, String label) {

        /** The label defaults to the name. */
        public Field {
            label = label == null ? name : label;
        }
    }
}
