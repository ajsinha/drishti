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
package com.ash.drishti.plugin.icebergext;

import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.meta.TableMetadata;
import com.ash.drishti.iceberg.meta.Transform;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.iceberg.types.Type;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a kind is in one snapshot's schema: its id columns, its business-date column (and how a row's date is taken
 * from it), the columns that make the document, and every searchable field (each primitive column, nested struct
 * fields flattened to the kind's depth, by their document names). Immutable.
 *
 * @param dateTimestamp the date column is a timestamp (its business date is the day of the time, in the kind's zone)
 * @param searchable document path ({@code ADDRESS.CITY}) to where it is: the top-level column and the struct positions under it
 */
record Shape(Schema schema, List<NestedField> idFields, NestedField dateField, boolean dateTimestamp, NestedField documentField,
        List<NestedField> documentColumns, Map<String, Path> searchable) {

    /** A searchable field: its top-level column, the positions within nested structs, and its type. */
    record Path(NestedField column, int[] within, Type.Primitive type) {}

    /** Why a kind cannot be served (no id), or null. */
    String problem(KindConfig cfg) {
        if (idFields.isEmpty()) {
            return cfg.kind() + " (" + cfg.table() + "): no id: the table has no identifier fields; set tables." + cfg.kind() + ".id";
        }
        return null;
    }

    static Shape of(KindConfig cfg, TableMetadata md, Schema schema) {
        List<NestedField> ids = new ArrayList<>();
        if (cfg.id().isEmpty()) {
            for (int id : schema.identifierFieldIds()) {
                NestedField f = schema.field(id);
                if (f != null && schema.parentOf(id) == -1) {
                    ids.add(f);
                }
            }
        } else {
            for (String c : cfg.id()) {
                ids.add(column(cfg, schema, c, "id"));
            }
        }
        NestedField date = null;
        boolean ts = false;
        if (cfg.date() != null) {
            date = column(cfg, schema, cfg.date(), "date");
            ts = date.type().id() == Type.Id.TIMESTAMP || date.type().id() == Type.Id.TIMESTAMPTZ;
            if (!ts && date.type().id() != Type.Id.DATE) {
                throw new IllegalArgumentException(cfg.kind() + ": the date column " + cfg.date() + " is " + date.type() + ", not a date or timestamp");
            }
            if (ts && !cfg.dateFromDay()) {
                throw new IllegalArgumentException(cfg.kind() + ": the date column " + cfg.date() + " is a timestamp: add date-from: day");
            }
        } else {
            // the partition spec's date: identity of a date column, or day() of a timestamp
            PartitionSpec spec = md.spec();
            for (PartitionSpec.Field f : spec.fields()) {
                NestedField src = schema.field(f.sourceId());
                if (src == null || schema.parentOf(src.id()) != -1) {
                    continue;
                }
                if (f.transform() instanceof Transform.Identity && src.type().id() == Type.Id.DATE) {
                    date = src;
                    break;
                }
                if (f.transform() instanceof Transform.Time t && t.unit() == Transform.TimeUnit.DAY
                        && (src.type().id() == Type.Id.TIMESTAMP || src.type().id() == Type.Id.TIMESTAMPTZ || src.type().id() == Type.Id.DATE)) {
                    date = src;
                    ts = src.type().id() != Type.Id.DATE;
                    break;
                }
            }
        }
        NestedField doc = cfg.documentColumn() == null ? null : column(cfg, schema, cfg.documentColumn(), "document");
        List<NestedField> cols = new ArrayList<>();
        Map<String, Path> searchable = new LinkedHashMap<>();
        for (NestedField f : schema.columns()) {
            if (cfg.exclude().contains(f.name()) || doc != null && f.id() == doc.id()) {
                continue;
            }
            cols.add(f);
            flatten(cfg.nameOf(f.name()), f, f.type(), new int[0], cfg.depth(), searchable);
        }
        return new Shape(schema, List.copyOf(ids), date, ts, doc, List.copyOf(cols), searchable);
    }

    private static void flatten(String path, NestedField top, Type t, int[] within, int depth, Map<String, Path> out) {
        if (t instanceof Type.Primitive p) {
            out.put(path, new Path(top, within, p));
        } else if (t instanceof Type.Struct s && within.length < depth - 1) {
            for (int i = 0; i < s.fields().size(); i++) {
                int[] next = java.util.Arrays.copyOf(within, within.length + 1);
                next[within.length] = i;
                flatten(path + "." + s.fields().get(i).name(), top, s.fields().get(i).type(), next, depth, out);
            }
        }
    }

    private static NestedField column(KindConfig cfg, Schema schema, String name, String what) {
        NestedField f = schema.field(name);
        if (f == null) {
            for (Map.Entry<String, String> r : cfg.rename().entrySet()) {     // the document's name of a renamed column
                if (r.getValue().equals(name)) {
                    f = schema.field(r.getKey());
                }
            }
        }
        if (f == null || schema.parentOf(f.id()) != -1) {
            throw new IllegalArgumentException(cfg.kind() + ": the " + what + " column " + name + " is not a top-level column of " + cfg.table());
        }
        return f;
    }

    /** A row's business date from its date column's value. */
    LocalDate dateOf(Object v, KindConfig cfg) {
        if (v == null) {
            return null;
        }
        if (!dateTimestamp) {
            return LocalDate.ofEpochDay(((Number) v).longValue());
        }
        long micros = (Long) v;
        if (dateField.type().id() == Type.Id.TIMESTAMP) {
            return LocalDate.ofEpochDay(Math.floorDiv(micros, 86_400_000_000L));     // wall-clock time: its own day
        }
        return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L)).atZone(cfg.dateZone()).toLocalDate();
    }

    /** The value of a searchable field in a row read with its top-level column at {@code slot}. */
    static Object at(Object[] row, int slot, Path p) {
        Object v = row[slot];
        for (int i : p.within()) {
            if (!(v instanceof Object[] struct)) {
                return null;
            }
            v = i < struct.length ? struct[i] : null;
        }
        return v;
    }
}
