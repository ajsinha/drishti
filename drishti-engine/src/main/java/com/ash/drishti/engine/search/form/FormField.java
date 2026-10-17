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
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One input of a kind's search form.
 *
 * @param path the document path searched ({@code mtm}, {@code counterparty.name})
 * @param label the heading shown for it
 * @param type the kind of input
 * @param operators the operators the person may choose, the first being the default (all of the type's when not declared)
 * @param defaultValue what the input starts with, or empty
 * @param order position in the form (lower first); fields without one keep their declared order after those with one
 * @param group a heading the field sits under, or empty
 * @param help one line of help shown with the field, or empty
 * @param required the form cannot be submitted without it
 * @param hidden kept in the definition (and usable in a saved search) but not offered as an input
 * @param values for an enum: the values offered (when empty the server offers the values found in the data)
 * @param inferred true when no pack declared it
 * @param depends for an enum: the path of another enum field whose choice narrows this one's values (a product sub type by its product
 *     type), or empty
 * @param parents for a dependent enum: each value with the values of {@code depends} it occurs with in the data (empty when unknown)
 */
public record FormField(String path, String label, FieldType type, List<String> operators, String defaultValue, int order, String group,
        String help, boolean required, boolean hidden, List<String> values, boolean inferred, String depends, Map<String, List<String>> parents) {

    public FormField(String path, String label, FieldType type, List<String> operators, String defaultValue, int order, String group,
            String help, boolean required, boolean hidden, List<String> values, boolean inferred) {
        this(path, label, type, operators, defaultValue, order, group, help, required, hidden, values, inferred, "", Map.of());
    }

    /** The same field with the values offered and, for a dependent enum, the parent values each occurs with. */
    public FormField withValues(List<String> offered, Map<String, List<String>> byParent) {
        return new FormField(path, label, type, operators, defaultValue, order, group, help, required, hidden, offered, inferred, depends, byParent);
    }

    private static final Pattern PATH = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*|\\[\\d+\\])*");

    public FormField {
        operators = operators == null || operators.isEmpty() ? type.operators() : List.copyOf(operators);
        defaultValue = defaultValue == null ? "" : defaultValue;
        group = group == null ? "" : group;
        help = help == null ? "" : help;
        values = values == null ? List.of() : List.copyOf(values);
        depends = depends == null ? "" : depends;
        parents = parents == null ? Map.of() : Map.copyOf(parents);
    }

    /** Reads one declared field, adding what is wrong with it to {@code problems} (null when it cannot be used). */
    static FormField parse(Object raw, int index, String where, List<String> problems) {
        String at = where + "[" + index + "]";
        if (!(raw instanceof Map<?, ?> m)) {
            problems.add(at + " must be a mapping with at least a path");
            return null;
        }
        Object p = m.get("path");
        String path = p == null ? "" : String.valueOf(p).trim();
        if (path.startsWith("$.")) {
            path = path.substring(2);
        }
        if (!PATH.matcher(path).matches()) {
            problems.add(at + ": path '" + path + "' is not a field path such as mtm or counterparty.name");
            return null;
        }
        String named = at + " (" + path + ")";
        FieldType type = FieldType.TEXT;
        if (m.get("type") != null) {
            var t = FieldType.of(String.valueOf(m.get("type")));
            if (t.isEmpty()) {
                problems.add(named + ": type '" + m.get("type") + "' is not one of text, number, amount, date, enum, id, bool");
                return null;
            }
            type = t.get();
        }
        List<String> ops = new ArrayList<>();
        if (m.get("operators") != null) {
            if (!(m.get("operators") instanceof List<?> l)) {
                problems.add(named + ": operators must be a list");
                return null;
            }
            for (Object o : l) {
                String op = String.valueOf(o).trim();
                if (!type.operators().contains(op)) {
                    problems.add(named + ": operator '" + op + "' does not suit type " + type.id() + " (allowed: " + String.join(", ", type.operators()) + ")");
                } else if (!ops.contains(op)) {
                    ops.add(op);
                }
            }
        }
        int order = 0;
        if (m.get("order") != null) {
            try {
                order = Integer.parseInt(String.valueOf(m.get("order")).trim());
            } catch (NumberFormatException e) {
                problems.add(named + ": order must be a whole number");
            }
        }
        List<String> values = new ArrayList<>();
        if (m.get("values") != null) {
            if (type != FieldType.ENUM) {
                problems.add(named + ": values belongs to type enum");
            } else if (m.get("values") instanceof List<?> l) {
                l.forEach(v -> values.add(String.valueOf(v)));
            } else {
                problems.add(named + ": values must be a list");
            }
        }
        String depends = str(m.get("depends"));
        if (depends.startsWith("$.")) {
            depends = depends.substring(2);
        }
        if (!depends.isEmpty() && (type != FieldType.ENUM || !PATH.matcher(depends).matches() || depends.equals(path))) {
            problems.add(named + ": depends names another enum field's path (PRODUCT_TYPE), and only an enum may depend on one");
            depends = "";
        }
        if (bool(m.get("required")) && bool(m.get("hidden")) && str(m.get("default")).isEmpty()) {
            problems.add(named + ": a required field that is hidden needs a default");
        }
        Object label = m.get("label");
        return new FormField(path, label == null || String.valueOf(label).isBlank() ? humanize(path) : String.valueOf(label).trim(), type, ops,
                m.get("default") == null ? "" : String.valueOf(m.get("default")), order, str(m.get("group")), str(m.get("help")), bool(m.get("required")),
                bool(m.get("hidden")), values, false, depends, Map.of());
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static boolean bool(Object o) {
        return o instanceof Boolean b ? b : o != null && "true".equalsIgnoreCase(String.valueOf(o));
    }

    /** {@code counterparty.name} to {@code Counterparty name}; {@code tradeDate} to {@code Trade date}. */
    public static String humanize(String path) {
        String last = path.substring(path.lastIndexOf('.') + 1);
        String spaced = last.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').toLowerCase(java.util.Locale.ROOT).trim();
        String owner = path.contains(".") ? path.substring(0, path.lastIndexOf('.')).replace('.', ' ') + " " : "";
        String all = (owner + spaced).trim();
        return all.isEmpty() ? path : Character.toUpperCase(all.charAt(0)) + all.substring(1);
    }

    /** The field as the API and the YAML show it. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("label", label);
        out.put("type", type.id());
        out.put("operators", operators);
        if (!defaultValue.isEmpty()) {
            out.put("default", defaultValue);
        }
        if (order != 0) {
            out.put("order", order);
        }
        if (!group.isEmpty()) {
            out.put("group", group);
        }
        if (!help.isEmpty()) {
            out.put("help", help);
        }
        if (required) {
            out.put("required", true);
        }
        if (hidden) {
            out.put("hidden", true);
        }
        if (!values.isEmpty()) {
            out.put("values", values);
        }
        if (!depends.isEmpty()) {
            out.put("depends", depends);
            if (!parents.isEmpty()) {
                out.put("parents", parents);
            }
        }
        return out;
    }
}
