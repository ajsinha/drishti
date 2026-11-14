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
package com.ash.drishti.server.liveset;

import com.ash.drishti.rachana.model.Form;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One live result set's declaration (docs/guides/TRADING_GUIDE.md section 6): a kind, an optional condition in the search grammar, the
 * columns, the record's key and version, the grouping, the links, the retention of the live side and, for a blotter, the editable fields
 * as a Rachana form with {@code save:}. A FORM search is the same declaration without {@code edit:}; a blotter is a file
 * {@code blotters/<id>.blotter.yaml} in a module. Immutable.
 *
 * @param id the declaration's id ({@code trade}), lower case, digits and dashes
 * @param title the screen's title
 * @param kind the records' kind
 * @param query the search command whose condition picks the rows ({@code TRD where TRADE_STATUS != 'CANCELLED'}), or null: every record
 * @param key the field naming a record ({@code TRADE_ID}); null: the entity id
 * @param version the field holding the record's version (the optimistic-concurrency guard), or null
 * @param changedBy the field naming who made the version, or null
 * @param changedAt the field holding when, or null
 * @param columns the grid's columns, as declared ({@code field}, {@code label}, {@code type}, {@code format}, {@code width})
 * @param groupBy the grouping it opens with
 * @param subtotals the columns summed per group
 * @param sortField the field it is sorted by, or null
 * @param descending whether that sort is descending
 * @param links the links of a record ({@code label}, {@code href} with {@code {FIELD}} placeholders)
 * @param retention the live side's age limit and row cap, or null
 * @param form the editable fields, or null for a read-only set
 * @param tray whether the screen shows the tray of the person's pending changes
 * @param module the module that declared it, or null
 */
public record LiveSetSpec(String id, String title, String kind, String query, String key, String version, String changedBy, String changedAt,
        List<Map<String, Object>> columns, List<String> groupBy, List<String> subtotals, String sortField, boolean descending,
        List<Map<String, String>> links, Retention retention, Form form, boolean tray, String module) {

    static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    static final Pattern FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]{0,127}");
    private static final Set<String> KEYS = Set.of("blotter", "live-set", "title", "kind", "query", "key", "version", "changed-by", "changed-at",
            "columns", "group-by", "subtotals", "sort", "links", "retention", "edit", "tray", "description");
    private static final Set<String> COLUMN_TYPES = Set.of("text", "number", "amount", "date", "datetime", "list", "status", "id");

    /**
     * The live side's retention: rows older than {@code ttl} by {@code on} (the arrival time when null) are dropped, and at most
     * {@code maxRows}, the newest, are kept. Absent: no limit.
     */
    public record Retention(Duration ttl, String on, Integer maxRows) {

        /** Parsed from {@code {ttl: 8h, on: UPDATED_AT, max-rows: 50000}}; null for null. */
        public static Retention of(Object raw, String where) {
            if (raw == null) {
                return null;
            }
            if (!(raw instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException(where + ": retention is { ttl, on, max-rows }");
            }
            for (Object k : m.keySet()) {
                if (!Set.of("ttl", "on", "max-rows").contains(String.valueOf(k))) {
                    throw new IllegalArgumentException(where + ": retention." + k + " is not known (ttl, on, max-rows)");
                }
            }
            Duration ttl = m.get("ttl") == null ? null : duration(String.valueOf(m.get("ttl")), where + ": retention.ttl");
            String on = m.get("on") == null ? null : String.valueOf(m.get("on")).trim();
            if (on != null && !FIELD.matcher(on).matches()) {
                throw new IllegalArgumentException(where + ": retention.on names a field, not '" + on + "'");
            }
            Integer max = null;
            if (m.get("max-rows") != null) {
                try {
                    max = Integer.parseInt(String.valueOf(m.get("max-rows")).trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(where + ": retention.max-rows is a number");
                }
                if (max < 1) {
                    throw new IllegalArgumentException(where + ": retention.max-rows is at least 1");
                }
            }
            if (ttl == null && max == null) {
                throw new IllegalArgumentException(where + ": retention names a ttl, a max-rows or both");
            }
            return new Retention(ttl, on, max);
        }

        /** {@code 30s}, {@code 15m}, {@code 8h}, {@code 1d}, {@code 7d}. */
        public static Duration duration(String text, String where) {
            String t = text.trim().toLowerCase(Locale.ROOT);
            if (!t.matches("\\d+(ms|s|m|h|d)")) {
                throw new IllegalArgumentException(where + " is a time such as 30s, 15m, 8h or 1d, not '" + text + "'");
            }
            long n = Long.parseLong(t.replaceAll("[a-z]+$", ""));
            return switch (t.replaceAll("^\\d+", "")) {
                case "ms" -> Duration.ofMillis(n);
                case "s" -> Duration.ofSeconds(n);
                case "m" -> Duration.ofMinutes(n);
                case "h" -> Duration.ofHours(n);
                default -> Duration.ofDays(n);
            };
        }

        Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ttl", ttl == null ? null : ttl.toString());
            m.put("on", on == null ? "arrival" : on);
            m.put("maxRows", maxRows);
            return m;
        }
    }

    public LiveSetSpec {
        columns = columns == null ? List.of() : List.copyOf(columns);
        groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
        subtotals = subtotals == null ? List.of() : List.copyOf(subtotals);
        links = links == null ? List.of() : List.copyOf(links);
    }

    /** Whether people may change records through it. */
    public boolean editable() {
        return form != null && form.save() != null;
    }

    /** The name the edit path knows its form by ({@code live:trade}), as a Sutra's name for a Sutra's form. */
    public String formName() {
        return "live:" + id;
    }

    /** The panel id of its form. */
    public static final String PANEL = "edit";

    /** Parses a declaration; {@code where} names the file in messages. Throws {@link IllegalArgumentException} with every problem found first. */
    @SuppressWarnings("unchecked")
    public static LiveSetSpec parse(Map<String, Object> m, String where, String module) {
        List<String> problems = new ArrayList<>();
        for (String k : m.keySet()) {
            if (!KEYS.contains(k)) {
                problems.add("'" + k + "' is not a key of a live set (" + String.join(", ", new java.util.TreeSet<>(KEYS)) + ")");
            }
        }
        String id = str(m.containsKey("blotter") ? m.get("blotter") : m.get("live-set"));
        if (id == null || !ID.matcher(id).matches()) {
            problems.add("blotter: names it, lower case, digits and dashes");
        }
        String kind = str(m.get("kind"));
        if (kind == null || !ID.matcher(kind).matches()) {
            problems.add("kind: names the records' kind");
        }
        String key = field(m, "key", problems);
        String version = field(m, "version", problems);
        String changedBy = field(m, "changed-by", problems);
        String changedAt = field(m, "changed-at", problems);
        List<Map<String, Object>> columns = new ArrayList<>();
        if (m.get("columns") instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> c = o instanceof Map<?, ?> cm ? new LinkedHashMap<>((Map<String, Object>) cm) : new LinkedHashMap<>(Map.of("field", String.valueOf(o)));
                String f = str(c.get("field"));
                if (f == null || !FIELD.matcher(f).matches()) {
                    problems.add("a column names its field");
                    continue;
                }
                String type = str(c.get("type"));
                if (type != null && !COLUMN_TYPES.contains(type)) {
                    problems.add("column " + f + ": type is one of " + new java.util.TreeSet<>(COLUMN_TYPES) + ", not " + type);
                }
                columns.add(Map.copyOf(c));
            }
        } else {
            problems.add("columns: lists the grid's columns");
        }
        List<String> groupBy = fields(m.get("group-by"), "group-by", problems);
        List<String> subtotals = fields(m.get("subtotals"), "subtotals", problems);
        String sortField = null;
        boolean descending = false;
        if (m.get("sort") instanceof Map<?, ?> s) {
            sortField = str(s.get("field"));
            descending = Boolean.parseBoolean(String.valueOf(s.get("descending")));
            if (sortField == null || !FIELD.matcher(sortField).matches()) {
                problems.add("sort.field names a field");
            }
        }
        List<Map<String, String>> links = new ArrayList<>();
        if (m.get("links") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> lm && str(lm.get("label")) != null && str(lm.get("href")) != null) {
                    String href = str(lm.get("href"));
                    if (!href.startsWith("/") || href.startsWith("//")) {
                        problems.add("link " + lm.get("label") + ": href is a path of this console (/...), not '" + href + "'");
                    }
                    links.add(Map.of("label", str(lm.get("label")), "href", href));
                } else {
                    problems.add("a link is { label, href }");
                }
            }
        }
        Retention retention = null;
        try {
            retention = Retention.of(m.get("retention"), where);
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
        }
        Form form = null;
        if (m.get("edit") != null) {
            List<String> fp = Form.problems(m.get("edit"));
            if (!fp.isEmpty()) {
                fp.forEach(p -> problems.add("edit: " + p));
            } else {
                Optional<Form> f = Form.fromOption(m.get("edit"));
                form = f.orElse(null);
                if (form == null || form.save() == null) {
                    problems.add("edit: needs save: (target, command, approval) for the changes to go anywhere");
                }
                if (version == null) {
                    problems.add("an editable live set names its version field (version:): a stale change is refused by it");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(where + ": " + String.join("; ", problems));
        }
        return new LiveSetSpec(id, str(m.get("title")) == null ? id : str(m.get("title")), kind, str(m.get("query")), key, version, changedBy,
                changedAt, columns, groupBy, subtotals, sortField, descending, links, retention, form, Boolean.parseBoolean(String.valueOf(m.get("tray"))),
                module);
    }

    /** What the screen needs to draw it: never the form's internals beyond what a field shows. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("kind", kind);
        m.put("query", query);
        m.put("key", key);
        m.put("version", version);
        m.put("changedBy", changedBy);
        m.put("changedAt", changedAt);
        m.put("columns", columns);
        m.put("groupBy", groupBy);
        m.put("subtotals", subtotals);
        m.put("sort", sortField == null ? null : Map.of("field", sortField, "descending", descending));
        m.put("links", links);
        m.put("retention", retention == null ? null : retention.describe());
        m.put("editable", editable());
        m.put("tray", tray);
        m.put("module", module);
        if (form != null) {
            List<Map<String, Object>> fields = new ArrayList<>();
            for (Form.Field f : form.fields()) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("id", f.id());
                fm.put("label", f.label());
                fm.put("type", f.type());
                fm.put("bind", f.recordField());
                fm.put("required", f.required());
                fm.put("min", f.min());
                fm.put("max", f.max());
                fm.put("options", f.options() == null ? List.of() : f.options().stream().map(o -> Map.of("value", o.value(), "label", o.label())).toList());
                fields.add(fm);
            }
            m.put("fields", fields);
            m.put("approval", form.save().approval());
            m.put("reason", form.save().reason() ? "required" : "optional");
        }
        return m;
    }

    private static String field(Map<String, Object> m, String k, List<String> problems) {
        String v = str(m.get(k));
        if (v != null && !FIELD.matcher(v).matches()) {
            problems.add(k + ": names a field, not '" + v + "'");
            return null;
        }
        return v;
    }

    private static List<String> fields(Object raw, String k, List<String> problems) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> l) {
            for (Object o : l) {
                String f = str(o);
                if (f == null || !FIELD.matcher(f).matches()) {
                    problems.add(k + ": lists fields");
                } else {
                    out.add(f);
                }
            }
        } else if (raw != null) {
            problems.add(k + ": is a list of fields");
        }
        return out;
    }

    static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
