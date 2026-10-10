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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A form for a kind whose pack declares none: the fields people search by, guessed from what the pack already says. The
 * candidates are the kind's search columns (the searchable, promoted ones, which are also the fast ones), the fields the
 * Sutra's {@code match.where} reads, and the plain fields of its header strip; labels come from the pack's about.yaml
 * (the field's term) and otherwise from the name. A type is read from sample values when there are some (numbers, ISO dates,
 * true/false, a short list of repeated words as an enum) and from the name otherwise.
 */
public final class FormInference {

    private static final Pattern PATH_IN_EL = Pattern.compile("\\$\\.([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)");
    private static final Pattern PLAIN_BIND = Pattern.compile("^\\s*\\$\\.([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)\\s*$");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}([T ].*)?");
    private static final Pattern AMOUNT_NAME = Pattern.compile("(?i).*(amount|notional|mtm|pnl|price|value|exposure|balance|cost|fee|nav|limit|\\bpv\\b|dv01|var).*");
    private static final Pattern ID_NAME = Pattern.compile("(?i)(id|.*Id|.*_id|.*code|.*ref|.*number|.*reference)");

    private final int maxFields;
    private final int enumMax;

    /**
     * @param maxFields at most this many inferred fields
     * @param enumMax a text field with at most this many distinct sample values is offered as a choice
     */
    public FormInference(int maxFields, int enumMax) {
        this.maxFields = Math.max(1, maxFields);
        this.enumMax = Math.max(2, enumMax);
    }

    public FormInference() {
        this(14, 12);
    }

    /**
     * @param kind the kind
     * @param columns its search columns, as paths
     * @param matchWhere the {@code match.where} texts of its Sutras
     * @param stripBinds the {@code bind} texts of its Sutras' strips
     * @param term a field's term from about.yaml, or null
     * @param samples sample values by path (may be empty)
     */
    public FormSpec infer(String kind, List<String> columns, List<String> matchWhere, List<String> stripBinds, Function<String, String> term,
            Map<String, List<Object>> samples) {
        Set<String> paths = new LinkedHashSet<>();
        columns.forEach(c -> paths.add(strip(c)));
        for (String w : matchWhere) {
            if (w != null) {
                Matcher m = PATH_IN_EL.matcher(w);
                while (m.find()) {
                    paths.add(m.group(1));
                }
            }
        }
        for (String b : stripBinds) {
            Matcher m = b == null ? null : PLAIN_BIND.matcher(b);
            if (m != null && m.matches()) {
                paths.add(m.group(1));
            }
        }
        List<FormField> fields = new ArrayList<>();
        for (String path : paths) {
            if (path.isEmpty() || path.contains("[") || fields.size() >= maxFields) {
                continue;
            }
            List<Object> sample = samples.getOrDefault(path, List.of());
            FieldType type = typeOf(path, sample);
            String label = term == null ? null : term.apply(path);
            List<String> values = type == FieldType.ENUM ? distinct(sample) : List.of();
            fields.add(new FormField(path, label == null || label.isBlank() ? FormField.humanize(path) : label, type, null, "", 0, "", "", false, false,
                    values, true));
        }
        return new FormSpec(kind, "", fields, true);
    }

    private static String strip(String c) {
        return c.startsWith("$.") ? c.substring(2) : c;
    }

    FieldType typeOf(String path, List<Object> sample) {
        List<Object> seen = sample.stream().filter(v -> v != null && !String.valueOf(v).isBlank()).toList();
        String last = path.substring(path.lastIndexOf('.') + 1);
        if (!seen.isEmpty()) {
            if (seen.stream().allMatch(v -> v instanceof Boolean)) {
                return FieldType.BOOL;
            }
            if (seen.stream().allMatch(v -> v instanceof Number)) {
                return AMOUNT_NAME.matcher(last).matches() ? FieldType.AMOUNT : FieldType.NUMBER;
            }
            if (seen.stream().allMatch(v -> DATE.matcher(String.valueOf(v)).matches())) {
                return FieldType.DATE;
            }
            if (ID_NAME.matcher(last).matches()) {
                return FieldType.ID;
            }
            Set<String> distinct = new LinkedHashSet<>();
            seen.forEach(v -> distinct.add(String.valueOf(v)));
            if (distinct.size() <= enumMax && seen.size() >= 2 * distinct.size()) {
                return FieldType.ENUM;
            }
            return FieldType.TEXT;
        }
        String lower = last.toLowerCase(Locale.ROOT);
        if (lower.endsWith("date") || lower.endsWith("time") || lower.endsWith("at") && lower.length() > 4) {
            return FieldType.DATE;
        }
        if (lower.startsWith("is") && last.length() > 2 && Character.isUpperCase(last.charAt(2)) || lower.startsWith("has") && last.length() > 3
                && Character.isUpperCase(last.charAt(3))) {
            return FieldType.BOOL;
        }
        if (ID_NAME.matcher(last).matches()) {
            return FieldType.ID;
        }
        if (AMOUNT_NAME.matcher(last).matches()) {
            return FieldType.AMOUNT;
        }
        return FieldType.TEXT;
    }

    private static List<String> distinct(List<Object> sample) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        sample.stream().filter(v -> v != null && !String.valueOf(v).isBlank()).forEach(v -> counts.merge(String.valueOf(v), 1, Integer::sum));
        return counts.keySet().stream().sorted().toList();
    }
}
