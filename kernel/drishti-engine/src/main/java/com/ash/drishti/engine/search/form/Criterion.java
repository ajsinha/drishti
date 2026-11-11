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
package com.ash.drishti.engine.search.form;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a person asked of one field of the form.
 *
 * @param path the field
 * @param op the operator (one of the field type's)
 * @param values the operand(s): one for most operators, two for {@code between}, any number for {@code in}
 * @param caseSensitive for text: compare exactly rather than ignoring case
 */
public record Criterion(String path, String op, List<String> values, boolean caseSensitive) {

    public Criterion {
        values = values == null ? List.of() : List.copyOf(values);
    }

    /** From the JSON the console sends: {@code {path, op, value?, value2?, values?, case?}}. */
    public static Criterion of(Map<?, ?> m) {
        List<String> values = new ArrayList<>();
        if (m.get("values") instanceof List<?> l) {
            l.forEach(v -> values.add(String.valueOf(v)));
        }
        for (String k : new String[] {"value", "value2"}) {
            if (m.get(k) != null && !String.valueOf(m.get(k)).isBlank()) {
                values.add(String.valueOf(m.get(k)));
            }
        }
        return new Criterion(String.valueOf(m.get("path")), String.valueOf(m.get("op") == null ? "" : m.get("op")), values,
                m.get("case") instanceof Boolean b ? b : "true".equals(String.valueOf(m.get("case"))));
    }
}
