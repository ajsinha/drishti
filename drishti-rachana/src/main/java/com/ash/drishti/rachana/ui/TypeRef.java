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

import java.util.List;

/**
 * A pin type with its refinements (UI_BOARD.md 4.1 and 4.2): what a pin accepts, what an out pin gives, what a supplier
 * attribute is.
 *
 * @param type the type
 * @param of the kind of an {@code id}, {@code selection} or {@code document} ({@code <pack>/<kind>}), or null
 * @param unit a measure's unit, or null
 * @param ccy a measure's currency ({@code any} accepts every currency), or null
 * @param min a number's lower bound, or null
 * @param max a number's upper bound, or null
 * @param integer true when a number must be whole
 * @param values an enum's values (empty for other types)
 */
public record TypeRef(PinType type, String of, String unit, String ccy, Double min, Double max, boolean integer, List<String> values) {

    public TypeRef {
        values = values == null ? List.of() : List.copyOf(values);
    }

    /** A bare type, no refinements. */
    public static TypeRef of(PinType type) {
        return new TypeRef(type, null, null, null, null, null, false, List.of());
    }

    /** An {@code id}, {@code selection} or {@code document} of a kind. */
    public static TypeRef of(PinType type, String kind) {
        return new TypeRef(type, kind, null, null, null, null, false, List.of());
    }

    /** A measure in a currency. */
    public static TypeRef measure(String ccy) {
        return new TypeRef(PinType.MEASURE, null, null, ccy, null, null, false, List.of());
    }

    /** This type with the kind {@code kind}. */
    public TypeRef withKind(String kind) {
        return new TypeRef(type, kind, unit, ccy, min, max, integer, values);
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder(type.yamlName());
        if (of != null) {
            b.append(" of ").append(of);
        }
        if (ccy != null) {
            b.append(" ccy ").append(ccy);
        }
        if (unit != null) {
            b.append(" unit ").append(unit);
        }
        return b.toString();
    }
}
