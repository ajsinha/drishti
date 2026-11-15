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
package com.ash.drishti.common.live;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A live row held to its kind's contract (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 3.4): required fields, types and enum values
 * of the contract's top-level {@code fields}. {@code strict} (the default, decision Q8) checks everything on arrival; {@code lenient}
 * passes values outside an enum and checks required fields and types; {@code deferred} checks nothing of the body here (key and order
 * are checked by the feed in every mode). Pure.
 */
public final class LiveValidator {

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern NUMBER = Pattern.compile("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");

    private LiveValidator() {
    }

    /** What is wrong with {@code row} under {@code contract}, in words; empty when it holds (or the contract has no fields). */
    public static Optional<String> check(Map<String, Object> contract, LiveRules.Validate mode, JsonNode row) {
        if (mode == LiveRules.Validate.DEFERRED || contract == null || !(contract.get("fields") instanceof List<?> fields)) {
            return Optional.empty();
        }
        List<String> out = new ArrayList<>();
        for (Object o : fields) {
            if (!(o instanceof Map<?, ?> f) || f.get("name") == null) {
                continue;
            }
            String name = String.valueOf(f.get("name"));
            JsonNode v = row.get(name);
            boolean empty = v == null || v.isNull() || (v.isTextual() && v.asText().isBlank());
            if (empty) {
                if ("required".equals(String.valueOf(f.get("presence")))) {
                    out.add(name + " is required");
                }
                continue;
            }
            String type = String.valueOf(f.get("type"));
            String fault = typeFault(type, v);
            if (fault != null) {
                out.add(name + " " + fault);
                continue;
            }
            if (mode == LiveRules.Validate.STRICT && f.get("enum") instanceof List<?> values && !values.isEmpty() && v.isValueNode()
                    && values.stream().noneMatch(x -> String.valueOf(x).equals(v.asText()))) {
                out.add(name + " is '" + v.asText() + "', not one of its values");
            }
        }
        return out.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", out));
    }

    private static String typeFault(String type, JsonNode v) {
        return switch (type) {
            case "integer", "amount", "rate" -> v.isNumber() || (v.isTextual() && NUMBER.matcher(v.asText().strip()).matches()) ? null
                    : "is " + kind(v) + ", not a number";
            case "boolean" -> v.isBoolean() || (v.isTextual() && List.of("true", "false").contains(v.asText().strip().toLowerCase(java.util.Locale.ROOT)))
                    ? null : "is " + kind(v) + ", not true or false";
            case "date", "timestamp" -> v.isTextual() && v.asText().length() >= 10 && DATE.matcher(v.asText().substring(0, 10)).matches() ? null
                    : "is " + kind(v) + ", not a date (YYYY-MM-DD)";
            case "vector", "record list", "text list", "id list", "date list", "list" -> v.isArray() ? null : "is " + kind(v) + ", not a list";
            case "record" -> v.isObject() ? null : "is " + kind(v) + ", not a record";
            case "text", "id" -> v.isValueNode() ? null : "is " + kind(v) + ", not text";
            default -> null;
        };
    }

    private static String kind(JsonNode v) {
        return v.isTextual() ? "text '" + abbreviate(v.asText()) + "'" : v.isArray() ? "a list" : v.isObject() ? "a record" : v.toString();
    }

    private static String abbreviate(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }
}
