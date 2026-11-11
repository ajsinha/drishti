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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The search form of one kind: the {@code form:} section of {@code pack.yaml}, under the kind's name, either as a list of
 * fields or as {@code {calendar: USNY, fields: [...]}}.
 *
 * @param kind the entity kind
 * @param calendar the business-day calendar relative dates run on ("" = the server's)
 * @param fields the fields in display order
 * @param inferred true when no pack declared the form and the fields were inferred
 */
public record FormSpec(String kind, String calendar, List<FormField> fields, boolean inferred) {

    public FormSpec {
        fields = List.copyOf(fields);
        calendar = calendar == null ? "" : calendar;
    }

    /** Reads a declared form; what is wrong with it is added to {@code problems} (the fields that can be read are kept). */
    public static FormSpec parse(String kind, Object raw, List<String> problems) {
        String where = "form." + kind;
        Object list = raw;
        String calendar = "";
        if (raw instanceof Map<?, ?> m) {
            list = m.get("fields");
            calendar = m.get("calendar") == null ? "" : String.valueOf(m.get("calendar")).trim();
            for (Object k : m.keySet()) {
                if (!Set.of("fields", "calendar").contains(String.valueOf(k))) {
                    problems.add(where + ": unknown key '" + k + "' (use fields and calendar)");
                }
            }
        }
        List<FormField> fields = new ArrayList<>();
        if (!(list instanceof List<?> l)) {
            problems.add(where + " needs a list of fields");
            return new FormSpec(kind, calendar, fields, false);
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < l.size(); i++) {
            FormField f = FormField.parse(l.get(i), i, where + ".fields", problems);
            if (f == null) {
                continue;
            }
            if (!seen.add(f.path())) {
                problems.add(where + ".fields: " + f.path() + " appears twice");
                continue;
            }
            fields.add(f);
        }
        if (!calendar.isEmpty()) {
            try {
                com.ash.drishti.common.BusinessCalendar.of(calendar);
            } catch (RuntimeException e) {
                problems.add(where + ": calendar '" + calendar + "' is not known");
            }
        }
        return new FormSpec(kind, calendar, ordered(fields), false);
    }

    /** Fields with an order first (ascending), then the rest as declared. */
    static List<FormField> ordered(List<FormField> in) {
        List<FormField> out = new ArrayList<>(in);
        out.sort(Comparator.comparingInt((FormField f) -> f.order() == 0 ? Integer.MAX_VALUE : f.order()));
        return out;
    }

    /** The declared forms of a pack's {@code form:} value: kind to form (problems added for each that cannot be read). */
    public static Map<String, FormSpec> parseAll(Object form, List<String> problems) {
        Map<String, FormSpec> out = new LinkedHashMap<>();
        if (form == null) {
            return out;
        }
        if (!(form instanceof Map<?, ?> m)) {
            problems.add("form must be a mapping of kind to its fields");
            return out;
        }
        m.forEach((k, v) -> out.put(String.valueOf(k), parse(String.valueOf(k), v, problems)));
        return out;
    }

    public Optional<FormField> field(String path) {
        String p = path != null && path.startsWith("$.") ? path.substring(2) : path;
        return fields.stream().filter(f -> f.path().equals(p)).findFirst();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        if (!calendar.isEmpty()) {
            out.put("calendar", calendar);
        }
        out.put("inferred", inferred);
        out.put("fields", fields.stream().map(FormField::toMap).toList());
        return out;
    }
}
