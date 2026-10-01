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
package com.ash.drishti.rachana;

import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.ElException;
import com.ash.drishti.rachana.model.Column;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.PanelKind;
import com.ash.drishti.rachana.model.SourceLocation;
import com.ash.drishti.rachana.model.Sutra;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Knows which Sutra values are Rachana-EL and compiles them all at load time, so a typo in an expression
 * is reported against the file (with {@code DRS-2101}) instead of surfacing as a broken view.
 */
public final class SutraExpressions {

    /** Per panel kind, the options that hold expressions (the rest are field names or literals). */
    static final Map<PanelKind, Set<String>> EL_OPTIONS = Map.ofEntries(
            Map.entry(PanelKind.KV, Set.of("rows")), Map.entry(PanelKind.TABLE, Set.of("rows", "moreLabel")),
            Map.entry(PanelKind.TABS, Set.of("each", "tabTitle")), Map.entry(PanelKind.LINE, Set.of("rows", "source", "mark")),
            Map.entry(PanelKind.AREA, Set.of("rows", "limit")), Map.entry(PanelKind.HBAR, Set.of("rows")),
            Map.entry(PanelKind.LADDER, Set.of("rows", "highlight")), Map.entry(PanelKind.GAUGE, Set.of("value", "max")),
            Map.entry(PanelKind.LINKS, Set.of()), Map.entry(PanelKind.STATUS, Set.of()),
            Map.entry(PanelKind.PROVENANCE, Set.of()), Map.entry(PanelKind.MARKDOWN, Set.of()), Map.entry(PanelKind.SURFACE, Set.of("rows")),
            Map.entry(PanelKind.WATERFALL, Set.of("rows")), Map.entry(PanelKind.HISTOGRAM, Set.of("rows")),
            Map.entry(PanelKind.SCATTER, Set.of("rows")), Map.entry(PanelKind.CANDLESTICK, Set.of("rows")),
            Map.entry(PanelKind.GRAPH, Set.of("nodes", "edges")), Map.entry(PanelKind.TIMELINE, Set.of("rows")),
            Map.entry(PanelKind.PIVOT, Set.of("rows")));

    private final ElCompiler compiler;

    public SutraExpressions(ElCompiler compiler) {
        this.compiler = compiler;
    }

    /** Compiles every expression in {@code s}; returns the problems (empty when all compile). */
    public List<SutraProblem> check(Sutra s) {
        List<SutraProblem> out = new ArrayList<>();
        expr(s.match().where(), s.location(), out);
        if (s.title() != null) {
            expr(s.title().id(), s.location(), out);
            expr(s.title().with(), s.location(), out);
        }
        s.strip().forEach(i -> expr(i.bind(), i.location(), out));
        s.panels().forEach(p -> panel(p, out));
        s.keys().values().forEach(a -> {
            if (a.startsWith("link(")) {
                expr(a, s.location(), out);
            }
        });
        return out;
    }

    private void panel(Panel p, List<SutraProblem> out) {
        template(p.title(), p.location(), out);
        for (Column c : p.columns()) {
            expr(c.bind(), p.location(), out);
        }
        for (String opt : EL_OPTIONS.getOrDefault(p.kind(), Set.of())) {
            Object v = p.options().get(opt);
            if (v instanceof String str) {
                expr(str, p.location(), out);
            }
        }
        if (p.options().get("fields") instanceof List<?> fields) {
            for (Object f : fields) {
                if (f instanceof Map<?, ?> m && m.get("bind") != null) {
                    expr(m.get("bind").toString(), p.location(), out);
                }
            }
        }
        if (p.kind() == PanelKind.HISTOGRAM && p.options().get("markers") instanceof List<?> markers) {
            for (Object mk : markers) {
                if (mk instanceof Map<?, ?> m && m.get("value") != null) {
                    expr(m.get("value").toString(), p.location(), out);
                }
            }
        }
        if (p.kind() == PanelKind.MARKDOWN) {
            p.option("text").ifPresent(t -> template(t, p.location(), out));
        }
        if (p.body() != null) {
            panel(p.body(), out);
        }
    }

    private void expr(String src, SourceLocation at, List<SutraProblem> out) {
        if (src == null) {
            return;
        }
        try {
            compiler.compile(src);
        } catch (ElException e) {
            out.add(new SutraProblem("DRS-2101", "expression '" + src + "': " + e.getMessage(), at));
        }
    }

    private void template(String src, SourceLocation at, List<SutraProblem> out) {
        if (src == null) {
            return;
        }
        try {
            compiler.template(src);
        } catch (ElException e) {
            out.add(new SutraProblem("DRS-2101", "template '" + src + "': " + e.getMessage(), at));
        }
    }
}
