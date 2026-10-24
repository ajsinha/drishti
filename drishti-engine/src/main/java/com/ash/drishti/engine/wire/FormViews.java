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
package com.ash.drishti.engine.wire;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.engine.view.ViewModel.PanelView;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.model.Column;
import com.ash.drishti.rachana.model.Form;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.Wiring;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A panel's {@code form:} as the console draws it (SUTRA_NEXT.md 10): the fields with what this viewer may do with each (a masked
 * field is marked and carries no value), the rules and hints as written (the browser checks them as people type; the drafts API
 * checks them again), each lookup's choices read as the viewer, and, for a table edited beside or in its cells, every row's values
 * (masked ones left out). Roles are applied by the server ({@link #forRoles}), which knows who asks.
 */
public final class FormViews {

    /** The most rows of a table whose values are sent for editing. */
    public static final int MAX_ROWS = 500;
    /** What a field says when the viewer may see it but not change it. */
    public static final String LOCKED = "locked: you can view, not change";
    /** What a masked field says. */
    public static final String HIDDEN = "Hidden for you";

    private FormViews() {}

    /**
     * {@code v} with the form of {@code p} for this viewer, or {@code v} when the panel has none.
     *
     * @param redact the viewer's masks
     * @param rows the panel's rows as the viewer sees them (null when it has none)
     * @param lookup the entities a lookup's source names, as the viewer sees them
     */
    public static PanelView attach(Panel p, PanelView v, java.util.function.UnaryOperator<DataNode> redact, DataNode rows,
            Function<Wiring.Source, List<DataNode>> lookup) {
        if (v == null || p.form().isEmpty()) {
            return v;
        }
        Form f = p.form().get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", f.title());
        out.put("key", f.key());
        out.put("edit", f.edit());
        out.put("new", f.create());
        out.put("newLabel", f.newLabel());
        out.put("roles", f.roles());
        out.put("steps", f.steps().stream().map(s -> Map.of("id", s.id(), "label", s.label() == null ? s.id() : s.label())).toList());
        List<Map<String, Object>> fields = new ArrayList<>();
        List<String> masked = new ArrayList<>();
        for (Form.Field fd : f.fields()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", fd.id());
            m.put("label", fd.label());
            m.put("type", fd.type());
            m.put("bind", fd.recordField());
            m.put("required", fd.required());
            put(m, "min", fd.min());
            put(m, "max", fd.max());
            put(m, "pattern", fd.pattern());
            put(m, "maxLength", fd.maxLength());
            put(m, "placeholder", fd.placeholder());
            put(m, "fmt", fd.fmt());
            put(m, "step", fd.step());
            m.put("editableBy", fd.editableBy());
            m.put("onNew", fd.onNew());
            if (fd.readonly()) {
                m.put("readonly", true);
                put(m, "reason", fd.reason());
            }
            boolean hidden = masked(redact, fd.recordField());
            if (hidden) {
                m.put("masked", true);
                masked.add(fd.id());
            }
            Integer col = column(p.columns(), fd.recordField());
            if (col != null) {
                m.put("col", col);
            }
            if (!fd.options().isEmpty()) {
                m.put("options", fd.options().stream().map(o -> Map.of("value", o.value(), "label", o.label())).toList());
            } else if (fd.lookup() != null && !hidden) {
                m.put("options", choices(fd.lookup(), lookup));
            }
            fields.add(m);
        }
        out.put("fields", fields);
        out.put("rules", f.rules().stream().map(r -> Map.of("rule", r.rule().text(), "message", r.message(), "severity", r.severity(), "fields", r.fields())).toList());
        out.put("hints", f.hints().stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", h.label());
            m.put("value", h.value().text());
            put(m, "of", h.of() == null ? null : h.of().text());
            put(m, "fmt", h.fmt());
            put(m, "step", h.step());
            return m;
        }).toList());
        if (f.edit() != null && rows instanceof DataNode.Arr arr) {
            List<Map<String, Object>> rv = new ArrayList<>();
            for (DataNode r : arr.elements()) {
                if (rv.size() >= MAX_ROWS) {
                    break;
                }
                Map<String, Object> values = new LinkedHashMap<>();
                List<String> rowMasked = new ArrayList<>();
                for (Form.Field fd : f.fields()) {
                    DataNode n = r.get(fd.recordField());
                    if (masked.contains(fd.id()) || n != null && n.isMasked()) {
                        rowMasked.add(fd.id());
                    } else {
                        values.put(fd.id(), n == null ? null : Values.simplify(n));
                    }
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", f.key() == null || values.get(f.key()) == null ? null : values.get(f.key()).toString());
                row.put("values", values);
                row.put("masked", rowMasked);
                rv.add(row);
            }
            out.put("rows", rv);
        }
        return v.withForm(out);
    }

    /** The panel's rows (its {@code rows:} option over the viewer's context), or null. */
    public static DataNode rows(Panel p, ElCompiler el, com.ash.drishti.rachana.el.EvalContext eval) {
        Object expr = p.options().get("rows");
        if (!(expr instanceof String s) || p.form().map(Form::edit).isEmpty()) {
            return null;
        }
        try {
            Object v = el.compile(s).eval(eval);
            return v instanceof DataNode n ? n : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The form of a panel for a person holding {@code roles}: a field they may not edit (its {@code editable-by}, else the form's
     * {@code roles}) is locked and says so; a masked one stays masked. {@code *} holds every role (sign-in off).
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> forRoles(Map<String, Object> form, List<String> roles) {
        if (form == null) {
            return null;
        }
        List<String> formRoles = (List<String>) form.getOrDefault("roles", List.of());
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) form.get("fields")) {
            List<String> who = (List<String>) f.getOrDefault("editableBy", List.of());
            List<String> need = who.isEmpty() ? formRoles : who;
            Map<String, Object> m = new LinkedHashMap<>(f);
            if (!need.isEmpty() && !roles.contains("*") && roles.stream().noneMatch(need::contains)) {
                m.put("locked", LOCKED);
            }
            fields.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>(form);
        out.put("fields", fields);
        return out;
    }

    private static List<Map<String, Object>> choices(Form.Lookup l, Function<Wiring.Source, List<DataNode>> lookup) {
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<DataNode> found;
        try {
            found = lookup.apply(l.source());
        } catch (RuntimeException e) {
            return out;
        }
        for (DataNode d : found) {
            DataNode v = d.get(l.value());
            if (v == null || v.isMasked() || Values.simplify(v) == null) {
                continue;                                      // a masked value is never offered: it would be typed back unseen
            }
            String value = Values.text(Values.simplify(v));
            if (!seen.add(value)) {
                continue;
            }
            DataNode lb = d.get(l.label());
            Map<String, Object> carry = new LinkedHashMap<>();
            for (String c : l.carry()) {
                DataNode cv = d.get(c);
                carry.put(c, cv == null ? null : cv.isMasked() ? DataNode.MASK : Values.simplify(cv));
            }
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("value", value);
            o.put("label", lb == null || lb.isMasked() || Values.simplify(lb) == null ? value : Values.text(Values.simplify(lb)));
            o.put("carry", carry);
            out.add(o);
        }
        return out;
    }

    private static boolean masked(java.util.function.UnaryOperator<DataNode> redact, String field) {
        if (redact == null) {
            return false;
        }
        DataNode probe = redact.apply(new DataNode.Obj(Map.of(field, DataNode.of("x"))));
        DataNode got = probe.get(field);
        return got != null && got.isMasked();
    }

    private static Integer column(List<Column> columns, String field) {
        for (int i = 0; i < columns.size(); i++) {
            String b = columns.get(i).bind();
            if (b != null && (b.equals("@." + field) || b.equals("$." + field) || b.equals(field))) {
                return i;
            }
        }
        return null;
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }
}
