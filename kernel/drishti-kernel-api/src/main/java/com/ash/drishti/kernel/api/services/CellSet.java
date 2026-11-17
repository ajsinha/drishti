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

/**
 * The pivot engine's answer, whole: exactly the map {@code POST /api/v1/pivot/inline} answers ({@code rows} and {@code columns} heads,
 * {@code cells} by {@code <row key>|<column key>}, {@code cellCount} and the engine's notes). The accessors read its main parts;
 * {@code InlineFigures.Figures} reads a figure by member.
 *
 * @param answer the engine's answer
 */
public record CellSet(Map<String, Object> answer) {

    /** Copies the answer (null becomes empty). */
    public CellSet {
        answer = answer == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(answer));
    }

    /** The row heads ({@code {key, kind: node|grand|others, value}}). */
    public List<Object> rows() {
        return answer.get("rows") instanceof List<?> l ? List.copyOf(l) : List.of();
    }

    /** The column heads. */
    public List<Object> columns() {
        return answer.get("columns") instanceof List<?> l ? List.copyOf(l) : List.of();
    }

    /** The cells by {@code <row key>|<column key>}, each with its {@code values} by measure name. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> cells() {
        return answer.get("cells") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
