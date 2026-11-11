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
package com.ash.drishti.rachana.parse;

import java.util.List;
import java.util.Map;

/**
 * A YAML node that remembers where it was written. Values are {@code Map<String, PNode>},
 * {@code List<PNode>} or a scalar ({@code String}, {@code Long}, {@code Double}, {@code Boolean}, null).
 *
 * @param value the value
 * @param line 1-based line
 * @param column 1-based column
 */
public record PNode(Object value, int line, int column) {

    @SuppressWarnings("unchecked")
    public Map<String, PNode> map() {
        return value instanceof Map<?, ?> m ? (Map<String, PNode>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    public List<PNode> list() {
        return value instanceof List<?> l ? (List<PNode>) l : List.of();
    }

    public boolean isMap() {
        return value instanceof Map;
    }

    public boolean isList() {
        return value instanceof List;
    }

    public boolean isScalar() {
        return !isMap() && !isList();
    }

    public String text() {
        return value == null || !isScalar() ? null : value.toString();
    }
}
