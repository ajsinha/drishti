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
package com.ash.drishti.iceberg.read;

import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Type;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * A row's values as JSON (ICEBERG_EXTERNAL_CONNECTOR.md section 3): numbers and booleans as JSON numbers and booleans, decimals
 * as numbers (or text with {@code decimals: string}), dates, times and timestamps as ISO-8601 text ({@code 2026-09-30},
 * {@code 18:30:00}, {@code 2026-09-30T18:30:00}, {@code 2026-09-30T18:30:00Z}; a fraction of a second only when there is
 * one, six digits), strings and UUIDs as text, fixed and binary as Base64 (or left out with {@code binary: omit}),
 * structs as objects, lists as arrays, maps as objects when their keys are text and else as arrays of
 * {@code {key, value}}. Stateless.
 */
public final class JsonValues {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** How decimals and binary values are written. */
    public record Options(boolean decimalsAsText, boolean omitBinary) {
        public static final Options DEFAULT = new Options(false, false);
    }

    private JsonValues() {}

    /** One row of {@code columns} (in order) as an object; a null column is a JSON null. */
    public static ObjectNode row(List<NestedField> columns, Object[] values, Options o) {
        ObjectNode n = F.objectNode();
        for (int i = 0; i < columns.size(); i++) {
            NestedField f = columns.get(i);
            JsonNode v = value(f.type(), values[i], o);
            if (v != null) {
                n.set(f.name(), v);
            }
        }
        return n;
    }

    /** One value; null when it is left out ({@code binary: omit}). */
    public static JsonNode value(Type t, Object v, Options o) {
        if (v == null) {
            return F.nullNode();
        }
        return switch (t) {
            case Type.Primitive p -> primitive(p, v, o);
            case Type.Struct s -> {
                ObjectNode n = F.objectNode();
                Object[] a = (Object[]) v;
                for (int i = 0; i < s.fields().size(); i++) {
                    JsonNode x = value(s.fields().get(i).type(), i < a.length ? a[i] : null, o);
                    if (x != null) {
                        n.set(s.fields().get(i).name(), x);
                    }
                }
                yield n;
            }
            case Type.ListType l -> {
                ArrayNode n = F.arrayNode();
                for (Object e : (List<?>) v) {
                    JsonNode x = value(l.element(), e, o);
                    n.add(x == null ? F.nullNode() : x);
                }
                yield n;
            }
            case Type.MapType m -> {
                Map<?, ?> map = (Map<?, ?>) v;
                if (m.key().id() == Type.Id.STRING) {
                    ObjectNode n = F.objectNode();
                    map.forEach((k, x) -> {
                        JsonNode j = value(m.value(), x, o);
                        n.set(String.valueOf(k), j == null ? F.nullNode() : j);
                    });
                    yield n;
                }
                ArrayNode n = F.arrayNode();
                map.forEach((k, x) -> {
                    ObjectNode e = n.addObject();
                    e.set("key", value(m.key(), k, o));
                    JsonNode j = value(m.value(), x, o);
                    e.set("value", j == null ? F.nullNode() : j);
                });
                yield n;
            }
        };
    }

    private static JsonNode primitive(Type.Primitive p, Object v, Options o) {
        return switch (p.id()) {
            case BOOLEAN -> F.booleanNode((Boolean) v);
            case INT -> F.numberNode(((Number) v).intValue());
            case LONG -> F.numberNode(((Number) v).longValue());
            case FLOAT -> F.numberNode(((Number) v).floatValue());
            case DOUBLE -> F.numberNode(((Number) v).doubleValue());
            case DECIMAL -> o.decimalsAsText() ? F.textNode(((BigDecimal) v).toPlainString()) : F.numberNode((BigDecimal) v);
            case DATE -> F.textNode(LocalDate.ofEpochDay(((Number) v).longValue()).toString());
            case TIME -> F.textNode(time(LocalTime.ofNanoOfDay(((Number) v).longValue() * 1000)));
            case TIMESTAMP -> F.textNode(timestamp((Long) v));
            case TIMESTAMPTZ -> F.textNode(timestamp((Long) v) + "Z");
            case STRING -> F.textNode(v.toString());
            case UUID -> F.textNode(v.toString());
            case FIXED, BINARY -> o.omitBinary() ? null : F.textNode(Base64.getEncoder().encodeToString((byte[]) v));
            default -> F.textNode(String.valueOf(v));
        };
    }

    /** Microseconds from the epoch as {@code 2026-09-30T18:30:00} (UTC), with {@code .ffffff} when there is a fraction. */
    public static String timestamp(long micros) {
        LocalDateTime t = LocalDateTime.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), (int) Math.floorMod(micros, 1_000_000L) * 1000,
                ZoneOffset.UTC);
        return t.toLocalDate() + "T" + time(t.toLocalTime());
    }

    private static String time(LocalTime t) {
        String s = String.format("%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond());
        int micros = t.getNano() / 1000;
        return micros == 0 ? s : s + String.format(".%06d", micros);
    }
}
