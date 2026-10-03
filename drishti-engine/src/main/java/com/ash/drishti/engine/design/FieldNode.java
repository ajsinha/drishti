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
package com.ash.drishti.engine.design;

import com.ash.drishti.engine.shape.RoleInfo;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One path of a shape, resolved for the design rules: its JSON type, role, how many documents carry it, and its
 * children. Built by {@link ShapeModel}; immutable once built.
 *
 * @param path {@code $.profile}, {@code $.positions[].desk}
 * @param name the last segment
 * @param type {@code object}, {@code array}, {@code string}, {@code number}, {@code boolean} or {@code mixed}
 * @param role the shape's role for it, or null
 * @param presence the share of documents that have it (parents multiplied in), 0 to 1
 * @param format the schema's {@code format} (date, date-time...) or null
 * @param min the schema's minimum (numbers) or NaN
 * @param max the schema's maximum (numbers) or NaN
 * @param minItems fewest items seen (arrays) or -1
 * @param maxItems most items seen (arrays) or -1
 * @param tree an array whose items hold a list of the same shape
 * @param props the fields of an object, or of an array's items
 * @param schema the resolved schema node
 */
record FieldNode(String path, String name, String type, RoleInfo role, double presence, String format, double min, double max,
        int minItems, int maxItems, boolean tree, Map<String, FieldNode> props, JsonNode schema) {

    FieldNode {
        props = props == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(props));
    }

    String roleName() {
        return role == null ? "" : role.role();
    }

    boolean is(String r) {
        return r.equals(roleName());
    }

    boolean array() {
        return "array".equals(type);
    }

    boolean object() {
        return "object".equals(type);
    }

    boolean scalar() {
        return "string".equals(type) || "number".equals(type) || "boolean".equals(type);
    }

    boolean date() {
        return "date".equals(format) || "date-time".equals(format);
    }

    /** The fields of an array's records (for an array of records); empty otherwise. */
    Map<String, FieldNode> record() {
        return props;
    }

    /** The path of a field of this node's records or object. */
    String child(String field) {
        return path + (array() ? "[]." : ".") + field;
    }
}
