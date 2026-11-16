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
package com.ash.drishti.iceberg.types;

/**
 * A field of a struct (or a list's element, a map's key or value): its id, which never changes and is what data files
 * are matched by, its name (which a rename changes), whether it is required, its type and its doc.
 */
public record NestedField(int id, String name, boolean required, Type type, String doc) {

    public static NestedField required(int id, String name, Type type) {
        return new NestedField(id, name, true, type, null);
    }

    public static NestedField optional(int id, String name, Type type) {
        return new NestedField(id, name, false, type, null);
    }

    public NestedField withType(Type t) {
        return new NestedField(id, name, required, t, doc);
    }

    public NestedField withName(String n) {
        return new NestedField(id, n, required, type, doc);
    }
}
