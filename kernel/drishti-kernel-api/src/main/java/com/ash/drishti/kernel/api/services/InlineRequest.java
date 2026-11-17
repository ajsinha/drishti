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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Rows for the pivot engine: field for field the body of {@code POST /api/v1/pivot/inline}, with the same rules.
 *
 * @param fields the fields of the rows
 * @param rows   the rows, one value per field (a number, text or null)
 * @param view   the arrangement ({@code rows}, {@code columns}, {@code values}, {@code expandTo}, {@code top}), as the engine reads it
 */
public record InlineRequest(List<Field> fields, List<List<Object>> rows, Map<String, Object> view) {

    /** The engine's most inline rows (a chart of a table's shown rows is a page, never a lake). */
    public static final int MAX_ROWS = 100_000;

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    /** Refuses no fields, more than {@link #MAX_ROWS} rows, or a view that is absent. */
    public InlineRequest {
        fields = fields == null ? List.of() : List.copyOf(fields);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("an inline pivot needs its fields");
        }
        rows = rows == null ? List.of() : List.copyOf(rows);
        if (rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException("the engine takes at most " + MAX_ROWS + " rows inline (" + rows.size() + " given)");
        }
        Objects.requireNonNull(view, "view");
        view = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(view));
    }

    /**
     * One field: a name of letters, digits and {@code _}, a type ({@code string}, {@code number} or {@code date}) and a label.
     *
     * @param name  the field's name
     * @param type  {@code string}, {@code number} or {@code date}
     * @param label its label (the name when null)
     */
    public record Field(String name, String type, String label) {

        /** Refuses a name or a type the engine does not take. */
        public Field {
            if (name == null || !NAME.matcher(name).matches() || !List.of("string", "number", "date").contains(type)) {
                throw new IllegalArgumentException("an inline field is {name: letters, digits and _, type: string, number or date}: "
                        + name + " " + type);
            }
            label = label == null ? name : label;
        }
    }
}
