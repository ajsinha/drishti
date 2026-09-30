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
package com.ash.drishti.inference;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.rachana.model.Column;
import com.ash.drishti.rachana.model.Match;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.PanelKind;
import com.ash.drishti.rachana.model.Sutra;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sutra ⊕ inference. With a Sutra, the Sutra wins: inference only fills panels that ask for it
 * ({@code infer: true}) or that leave their columns unstated. Without one, the whole layout is inferred.
 */
public final class LayoutMerger {

    private final InferenceEngine engine;
    private final ElCompiler el;
    private final Formats formats;

    public LayoutMerger(InferenceEngine engine, ElCompiler el, Formats formats) {
        this.engine = engine;
        this.el = el;
        this.formats = formats;
    }

    public EffectiveLayout merge(Optional<Sutra> sutra, DataNode doc, String kind) {
        if (sutra.isEmpty()) {
            InferredLayout inf = engine.infer(doc, kind);
            Sutra s = new Sutra("inferred-" + kind, 0, "inferred", new Match(kind, null, 0), inf.title(), inf.strip(), inf.panels(),
                    Map.of("F9", "raw"), Rules.INFERRED);
            return new EffectiveLayout(s, "inference only", true, inf.explanations());
        }
        Sutra s = sutra.get();
        Map<String, String> why = new LinkedHashMap<>();
        List<Panel> panels = new ArrayList<>();
        for (Panel p : s.panels()) {
            panels.add(fill(p, doc, why));
        }
        boolean inferred = !why.isEmpty() || panels.stream().anyMatch(p -> p.kind() == PanelKind.LINKS);
        Sutra effective = new Sutra(s.name(), s.version(), s.domain(), s.match(), s.title(), s.strip(), panels, s.keys(), s.location());
        return new EffectiveLayout(effective, "Sutra " + s.name() + " v" + s.version() + (inferred ? " + inference" : ""), inferred, why);
    }

    private Panel fill(Panel p, DataNode doc, Map<String, String> why) {
        Panel body = p.body() == null ? null : fillBody(p, doc, why);
        List<Column> cols = p.columns();
        boolean wants = p.infer() || cols.isEmpty();
        if (cols.isEmpty() && wants && p.option("rows").isPresent()) {
            DataNode rows = rows(p.option("rows").get(), doc);
            if (p.kind() == PanelKind.TABLE || p.kind() == PanelKind.LADDER) {
                cols = engine.columns().forRows(rows);
            } else if (p.kind() == PanelKind.KV) {
                cols = engine.columns().forObject(rows, "@.");
            }
            if (!cols.isEmpty()) {
                why.put(p.id(), "ColumnInference: " + cols.size() + " fields from " + p.option("rows").get());
            }
        }
        if (cols == p.columns() && body == p.body()) {
            return p;
        }
        return new Panel(p.id(), p.kind(), p.title(), p.key(), p.code(), p.area(), p.infer(), cols, body, p.options(), p.location());
    }

    private Panel fillBody(Panel tabs, DataNode doc, Map<String, String> why) {
        Panel b = tabs.body();
        if (!b.columns().isEmpty() || tabs.option("each").isEmpty()) {
            return b;
        }
        DataNode first = rows(tabs.option("each").get(), doc).get(0);
        List<Column> cols = engine.columns().forObject(first, "@.");
        why.put(tabs.id(), "ColumnInference: " + cols.size() + " fields per element");
        return new Panel(b.id(), b.kind(), b.title(), b.key(), b.code(), b.area(), b.infer(), cols, b.body(), b.options(), b.location());
    }

    private DataNode rows(String expr, DataNode doc) {
        Object v = el.compile(expr).eval(EvalContext.of(doc, formats));
        return v instanceof DataNode n ? n : DataNode.missing();
    }
}
