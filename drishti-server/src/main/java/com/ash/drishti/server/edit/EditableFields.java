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
package com.ash.drishti.server.edit;

import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.model.Form;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.Sutra;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a Sutra says may be edited (WRITE_BACK.md principle 1: nothing is editable unless a Sutra says so, field by field). A field is
 * editable through write-back when it is a field of a panel's {@code form:} that has a {@code save:} (RACHANA_REFERENCE.md 10 and 11); the
 * index maps a record's kind and field to the forms that edit it, rebuilt whenever the Sutras change. A change made from a form names
 * its Sutra and panel; one made through the API or the CLI names only the kind and field, and the first form (by Sutra name) that
 * edits it decides its rules.
 */
public final class EditableFields {

    /**
     * One editable field.
     *
     * @param kind the record's kind ({@code save.kind}, else the page's kind)
     * @param pageKind the kind of the page the form is on
     * @param sutra the Sutra
     * @param panel the panel's id
     * @param rows the path of the page's rows the form edits ({@code $.TRADES}), or null when the form edits the page's own record
     * @param form the form (its rules, roles and {@code save:})
     * @param field the field
     */
    public record Rule(String kind, String pageKind, String sutra, String panel, String rows, Form form, Form.Field field) {

        public Form.Save save() {
            return form.save();
        }

        /** The path the field writes: {@code $.<bind>}. */
        public String path() {
            return EditPaths.ofField(field.recordField());
        }

        /** The field that names a record of the form ({@code TRADE_ID}), or null. */
        public String keyField() {
            return form.key() == null ? null : form.field(form.key()).map(Form.Field::recordField).orElse(null);
        }
    }

    private final SutraRegistry sutras;
    private volatile Map<String, List<Rule>> byField = Map.of();

    public EditableFields(SutraRegistry sutras) {
        this.sutras = sutras;
        rebuild();
        sutras.onChange(names -> rebuild());
    }

    /** Re-reads every Sutra's forms. */
    public void rebuild() {
        Map<String, List<Rule>> out = new LinkedHashMap<>();
        List<Sutra> all = new ArrayList<>(sutras.all());
        all.sort(java.util.Comparator.comparing(Sutra::name));
        for (Sutra s : all) {
            for (Panel p : s.panels()) {
                Optional<Form> f = p.form();
                if (f.isEmpty() || f.get().save() == null) {
                    continue;
                }
                for (Rule r : rules(s, p, f.get())) {
                    out.computeIfAbsent(r.kind() + "|" + r.field().recordField(), k -> new ArrayList<>()).add(r);
                }
            }
        }
        byField = Map.copyOf(out);
    }

    private static List<Rule> rules(Sutra s, Panel p, Form f) {
        String pageKind = s.match() == null ? null : s.match().kind();
        String kind = f.save().kind() != null ? f.save().kind() : pageKind;
        Object rows = p.options().get("rows");
        String rowsPath = rows == null ? null : rows.toString();
        List<Rule> out = new ArrayList<>();
        for (Form.Field fd : f.fields()) {
            out.add(new Rule(kind, pageKind, s.name(), p.id(), rowsPath, f, fd));
        }
        return out;
    }

    /** The rule of a field ({@code bind}) of a kind's records, from a given Sutra and panel when named, else the first form that edits it. */
    public Optional<Rule> find(String kind, String bind, String sutra, String panel) {
        List<Rule> l = byField.getOrDefault(kind + "|" + bind, List.of());
        if (sutra != null && !sutra.isBlank()) {
            return l.stream().filter(r -> r.sutra().equals(sutra) && (panel == null || panel.isBlank() || r.panel().equals(panel))).findFirst();
        }
        return l.stream().findFirst();
    }

    /** The rules of one form, by field id (a draft's records name fields by id). */
    public Map<String, Rule> ofForm(String sutra, String panel) {
        Map<String, Rule> out = new LinkedHashMap<>();
        byField.values().forEach(l -> l.stream().filter(r -> r.sutra().equals(sutra) && r.panel().equals(panel)).forEach(r -> out.put(r.field().id(), r)));
        return out;
    }

    /** The kinds with at least one editable field (Admin → Edits). */
    public List<String> kinds() {
        return byField.values().stream().flatMap(List::stream).map(Rule::kind).distinct().sorted().toList();
    }

    public int size() {
        return byField.size();
    }
}
