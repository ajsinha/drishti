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
package com.ash.drishti.engine.explain;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.engine.view.ViewModel.Cell;
import com.ash.drishti.engine.view.ViewModel.PanelView;
import com.ash.drishti.rachana.about.GlossaryEntry;
import com.ash.drishti.rachana.about.GlossaryResolver;
import com.ash.drishti.rachana.model.Column;
import com.ash.drishti.rachana.model.Panel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Layer 2 of the explanation (docs/architecture/CONTEXT_HELP.md): what each number on the page means. Walks the view the
 * caller got, so only fields with a cell on that page can have an entry, and a panel the caller may not open contributes
 * nothing. Authored text is never evaluated and never carries a value; for a field the caller sees hidden the definition is
 * given and the meanings of its values are not. One instance per answer; not shared between threads.
 */
final class GlossaryBuilder {

    /** The page's own key figures, as the panel id they are listed under. */
    static final String STRIP = "strip";

    /** Everything the page shows of one field. */
    private static final class Shown {
        final Set<String> labels = new LinkedHashSet<>();
        final Set<String> panels = new LinkedHashSet<>();
        final Set<String> texts = new LinkedHashSet<>();
        boolean masked;
    }

    private final GlossaryResolver resolver;
    private final String kind;
    private final Map<String, Panel> defined;
    private final Function<String, Optional<GlossaryEntry>> derived;
    private final Map<String, Shown> shown = new LinkedHashMap<>();

    GlossaryBuilder(GlossaryResolver resolver, String kind, Map<String, Panel> defined, Function<String, Optional<GlossaryEntry>> derived) {
        this.resolver = resolver;
        this.kind = kind;
        this.defined = defined;
        this.derived = derived;
    }

    List<PageContext.Term> build(ViewModel view) {
        for (Cell c : view.strip()) {
            note(GlossaryResolver.normalise(c.path()), c, c.label(), STRIP);
        }
        for (PanelView p : view.panels()) {
            if (p.denied() == null && p.data() != null) {
                panel(p);
            }
        }
        List<PageContext.Term> out = new ArrayList<>();
        shown.forEach((key, s) -> resolver.resolve(kind, key, derived).ifPresent(e -> out.add(term(key, s, e))));
        return out;
    }

    private void panel(PanelView p) {
        Panel def = defined.get(p.id());
        switch (p.data()) {
            case PanelData.Fields f -> {
                for (int i = 0; i < f.fields().size(); i++) {
                    Cell c = f.fields().get(i);
                    String key = GlossaryResolver.normalise(c.path());
                    if (key == null && def != null && i < def.columns().size()) {
                        key = GlossaryResolver.keyOf(def.columns().get(i).bind(), def.option("rows").orElse(null));
                    }
                    note(key, c, c.label(), p.id());
                }
            }
            case PanelData.Tabs t -> t.tabs().forEach(tab -> tab.fields().forEach(c -> note(GlossaryResolver.normalise(c.path()), c, c.label(), p.id())));
            case PanelData.Metric m -> note(m.value() == null ? null : GlossaryResolver.normalise(m.value().path()), m.value(),
                    m.value() == null ? null : m.value().label(), p.id());
            case PanelData.Table t -> table(p.id(), def, t);
            default -> { }
        }
    }

    private void table(String panel, Panel def, PanelData.Table t) {
        if (def == null) {
            return;
        }
        List<Column> cols = def.columns();
        String rows = def.option("rows").orElse(null);
        for (int k = 0; k < cols.size() && k < t.columns().size(); k++) {
            String key = GlossaryResolver.keyOf(cols.get(k).bind(), rows);
            if (key == null) {
                continue;
            }
            Shown s = shown.computeIfAbsent(key, x -> new Shown());
            s.labels.add(t.columns().get(k));
            s.panels.add(panel);
            for (PanelData.Row r : t.rows()) {
                values(s, r, k);
            }
        }
    }

    private static void values(Shown s, PanelData.Row row, int k) {
        if (k < row.cells().size()) {
            text(s, row.cells().get(k));
        }
        if (row.children() != null) {
            row.children().forEach(c -> values(s, c, k));
        }
    }

    private static void text(Shown s, Cell c) {
        if (c == null) {
            return;
        }
        if (DataNode.MASK.equals(c.text())) {
            s.masked = true;
        } else if (c.text() != null && !c.text().isBlank() && s.texts.size() < 200) {
            s.texts.add(c.text());
        }
    }

    private void note(String key, Cell c, String label, String panel) {
        if (key == null || c == null) {
            return;
        }
        Shown s = shown.computeIfAbsent(key, x -> new Shown());
        if (label != null && !label.isBlank()) {
            s.labels.add(label);
        }
        s.panels.add(panel);
        text(s, c);
    }

    private static PageContext.Term term(String key, Shown s, GlossaryEntry e) {
        String label = s.labels.isEmpty() ? key : s.labels.iterator().next();
        Map<String, String> values = new LinkedHashMap<>();
        if (!s.masked) {                                          // the meaning of a value would name it
            s.texts.forEach(t -> {
                String m = e.values().get(t);
                if (m != null) {
                    values.put(t, m);
                }
            });
        }
        return new PageContext.Term(key, label, s.labels.size() > 1 ? List.copyOf(s.labels) : List.of(), List.copyOf(s.panels),
                e.term() == null ? label : e.term(), e.means(), e.unit(), e.sign(), e.note(), e.formula(), values, e.origin(), s.masked ? Boolean.TRUE : null);
    }
}
