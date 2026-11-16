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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A table schema: its id, its top-level struct and its identifier fields (the columns that identify a row, v2's
 * {@code identifier-field-ids}). Every field, nested ones included, can be found by id and by its full name
 * ({@code counterparty.id}; a list's element is {@code legs.element}, a map's parts {@code tags.key} and
 * {@code tags.value}). Immutable; the lookups are built once.
 */
public final class Schema {

    private final int schemaId;
    private final Type.Struct struct;
    private final List<Integer> identifierFieldIds;
    private final Map<Integer, NestedField> byId = new HashMap<>();
    private final Map<Integer, String> nameOf = new HashMap<>();
    private final Map<String, Integer> idOf = new LinkedHashMap<>();
    private final Map<Integer, Integer> parentOf = new HashMap<>();

    public Schema(int schemaId, List<NestedField> columns, List<Integer> identifierFieldIds) {
        this.schemaId = schemaId;
        this.struct = new Type.Struct(columns);
        this.identifierFieldIds = List.copyOf(identifierFieldIds);
        index(struct, "", -1);
    }

    public Schema(int schemaId, List<NestedField> columns) {
        this(schemaId, columns, List.of());
    }

    private void index(Type t, String prefix, int parent) {
        switch (t) {
            case Type.Struct s -> {
                for (NestedField f : s.fields()) {
                    add(f, prefix + f.name(), parent);
                }
            }
            case Type.ListType l -> add(l.elementField(), prefix + "element", parent);
            case Type.MapType m -> {
                add(m.keyField(), prefix + "key", parent);
                add(m.valueField(), prefix + "value", parent);
            }
            case Type.Primitive p -> {
                // a leaf
            }
        }
    }

    private void add(NestedField f, String name, int parent) {
        byId.put(f.id(), f);
        nameOf.put(f.id(), name);
        idOf.putIfAbsent(name, f.id());
        parentOf.put(f.id(), parent);
        index(f.type(), name + ".", f.id());
    }

    public int schemaId() {
        return schemaId;
    }

    public Type.Struct asStruct() {
        return struct;
    }

    /** The top-level columns. */
    public List<NestedField> columns() {
        return struct.fields();
    }

    public List<Integer> identifierFieldIds() {
        return identifierFieldIds;
    }

    /** The field of this id, at any depth, or null. */
    public NestedField field(int id) {
        return byId.get(id);
    }

    /** The field of this full name ({@code counterparty.id}), or null. */
    public NestedField field(String fullName) {
        Integer id = idOf.get(fullName);
        return id == null ? null : byId.get(id);
    }

    /** The full name of the field of this id, or null. */
    public String nameOf(int id) {
        return nameOf.get(id);
    }

    /** The id of the struct, list or map holding the field, or -1 for a top-level column. */
    public int parentOf(int id) {
        return parentOf.getOrDefault(id, -1);
    }

    /** Every field id, at any depth. */
    public java.util.Set<Integer> ids() {
        return Collections.unmodifiableSet(byId.keySet());
    }

    /** The highest field id used. */
    public int highestFieldId() {
        int max = 0;
        for (int id : byId.keySet()) {
            max = Math.max(max, id);
        }
        return max;
    }

    /** A schema of the given top-level columns of this one, in the order given (a projection). */
    public Schema select(List<Integer> ids) {
        List<NestedField> out = new ArrayList<>();
        for (int id : ids) {
            NestedField f = field(id);
            if (f == null) {
                throw new IllegalArgumentException("no field " + id + " in schema " + schemaId);
            }
            out.add(f);
        }
        return new Schema(schemaId, out);
    }

    /** The same schema with another id and identifier fields. */
    public Schema withId(int id) {
        return new Schema(id, struct.fields(), identifierFieldIds);
    }

    /** Whether the two describe the same columns (ids, names, types, required) and identifier fields, whatever their schema ids. */
    public boolean sameStructure(Schema other) {
        return struct.equals(other.struct) && identifierFieldIds.equals(other.identifierFieldIds);
    }

    @Override
    public String toString() {
        return "schema " + schemaId + " " + struct.fields();
    }
}
