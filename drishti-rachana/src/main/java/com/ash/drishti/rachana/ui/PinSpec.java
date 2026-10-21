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
package com.ash.drishti.rachana.ui;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One pin of a chip, or one parameter of a board (UI_BOARD.md 2.1 {@code pins:}, 3 {@code params:}).
 *
 * @param name the pin's name
 * @param role data, control (in pins and board params) or out
 * @param type the type and refinements
 * @param required true when the board must connect it (a {@code default} counts as connected)
 * @param defaultValue the default, or null
 * @param shape a {@code rowset}'s or {@code document}'s columns, in the chip's own names (empty otherwise)
 * @param ofPin an out pin's {@code of: <pin>.<column>} (the column whose kind its id carries), or null
 * @param shapeOf an out rowset's {@code shape-of: <pin>}, or null
 */
public record PinSpec(String name, PinRole role, TypeRef type, boolean required, JsonNode defaultValue, Map<String, ColumnSpec> shape,
        String ofPin, String shapeOf) {

    public PinSpec {
        shape = shape == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(shape));
    }

    /** True for an in pin (data or control). */
    public boolean in() {
        return role != PinRole.OUT;
    }

    /** True when the pin has a default value. */
    public boolean hasDefault() {
        return defaultValue != null && !defaultValue.isNull();
    }

    /** The pin roles. */
    public enum PinRole { DATA, CONTROL, OUT }

    /**
     * One column of a {@code rowset} or {@code document} shape.
     *
     * @param name the column's name in the chip
     * @param type its type
     * @param required true when the board must map it
     * @param many true when it takes several supplier columns as a list
     * @param max the most columns a {@code many} column takes (0: no limit)
     */
    public record ColumnSpec(String name, TypeRef type, boolean required, boolean many, int max) {}
}
