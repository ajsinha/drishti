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
package com.ash.drishti.engine.bind;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.engine.pivot.PivotProperties;
import com.ash.drishti.engine.view.ViewModel.LinkView;
import com.ash.drishti.graph.ReferenceCatalog;
import com.ash.drishti.inference.Semantics;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Link;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.model.Column;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.PivotSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The interactive pivot of a table or ladder: what the view says about it (its fields and the arrangement it opens
 * with, carried by the table's data) and, when a user opens the Pivot tab, the panel's rows as raw values, every row up
 * to {@link PivotProperties#maxRecords()} (a table's {@code limit} only shortens the table). Stateless and thread-safe.
 */
public final class PivotBinder {

    /**
     * A panel's rows for its pivot.
     *
     * @param panel the panel id
     * @param fields name, label, fmt and, for a field whose values are entity ids, the kind they open
     * @param rows one list of raw values per row, in field order
     * @param total rows the panel has
     * @param truncated true when only the first {@code limit} rows are given
     */
    public record Records(String panel, List<Map<String, Object>> fields, List<List<Object>> rows, int total, boolean truncated, int limit) {}

    private final ElCompiler el;
    private final ReferenceCatalog catalog;
    private final Binder binder;
    private final PivotProperties props;

    PivotBinder(ElCompiler el, ReferenceCatalog catalog, Binder binder, PivotProperties props) {
        this.el = el;
        this.catalog = catalog;
        this.binder = binder;
        this.props = props;
    }

    /** What the table's data carries: the fields offered and the arrangement the tab opens with; empty when not opted in. */
    Optional<Map<String, Object>> view(Panel p) {
        if (!props.enabled()) {
            return Optional.empty();
        }
        return p.pivot().map(spec -> {
            Map<String, Object> out = spec.toMap();
            List<Map<String, Object>> fields = new ArrayList<>();
            for (PivotSpec.Field f : spec.fields()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", f.name());
                m.put("label", label(f, p.columns()));
                String fmt = fmt(f, p.columns());
                m.put("numeric", fmt != null && !fmt.equals("date") && !fmt.equals("text"));
                m.put("fmt", fmt);
                fields.add(m);
            }
            out.put("fields", fields);
            out.put("maxRows", props.maxRecords());
            return out;
        });
    }

    /** Every row of the panel (up to the limit) as raw values of the pivot's fields. */
    Records records(Panel p, BindContext c) {
        if (!props.enabled()) {
            throw new com.ash.drishti.common.DrishtiException(com.ash.drishti.common.ErrorCode.FORBIDDEN,
                    "the Pivot tab is switched off on this server (drishti.pivot.enabled)");
        }
        PivotSpec spec = p.pivot().orElseThrow(() -> new IllegalArgumentException("panel '" + p.id() + "' does not offer a pivot"));
        DataNode rows = binder.eval(p.option("rows").orElseThrow(), c.eval()) instanceof DataNode n ? n : DataNode.missing();
        List<Expr> exprs = spec.fields().stream().map(f -> el.compile(f.bind())).toList();
        int limit = props.maxRecords();
        int total = rows.size();
        List<List<Object>> out = new ArrayList<>(Math.min(total, limit));
        String[] kinds = new String[exprs.size()];
        boolean[] ids = new boolean[exprs.size()];          // fields that hold entity ids: as a table column would link them
        for (int k = 0; k < ids.length; k++) {
            String bind = spec.fields().get(k).bind().strip();
            ids[k] = Binder.namesAnId(bind) || p.columns().stream().anyMatch(col -> col.link() && bind.equals(col.bind() == null ? null : col.bind().strip()));
        }
        for (int i = 0; i < total && i < limit; i++) {
            EvalContext rc = c.eval().withRow(rows.get(i), i);
            List<Object> row = new ArrayList<>(exprs.size());
            for (int k = 0; k < exprs.size(); k++) {
                Object v;
                try {
                    v = Values.simplify(exprs.get(k).eval(rc));
                } catch (RuntimeException e) {
                    v = null;                              // a row that cannot answer a field has no value for it
                }
                if (v instanceof Link l) {
                    LinkView lv = binder.linkView(l);
                    if (kinds[k] == null && lv != null) {
                        kinds[k] = lv.kind();
                    }
                    v = l.id();
                } else if (v instanceof DataNode n) {
                    v = n.size();                          // a list or an object counts as its size
                } else if (v instanceof String s && kinds[k] == null && ids[k]) {
                    kinds[k] = catalog.kindOf(s).orElse(null);
                }
                row.add(v);
            }
            out.add(row);
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        for (int k = 0; k < spec.fields().size(); k++) {
            PivotSpec.Field f = spec.fields().get(k);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name());
            m.put("label", label(f, p.columns()));
            m.put("fmt", fmt(f, p.columns()));
            if (kinds[k] != null) {
                m.put("kind", kinds[k]);
            }
            fields.add(m);
        }
        return new Records(p.id(), fields, out, total, total > limit, limit);
    }

    /** The field's own label, else that of the column bound to the same expression, else its name in words. */
    private static String label(PivotSpec.Field f, List<Column> columns) {
        if (f.label() != null) {
            return f.label();
        }
        for (Column c : columns) {
            if (c.label() != null && f.bind().strip().equals(c.bind() == null ? null : c.bind().strip())) {
                return c.label();
            }
        }
        String last = f.name().substring(f.name().lastIndexOf('.') + 1);
        return Semantics.humanize(last);
    }

    private static String fmt(PivotSpec.Field f, List<Column> columns) {
        if (f.fmt() != null) {
            return f.fmt();
        }
        for (Column c : columns) {
            if (f.bind().strip().equals(c.bind() == null ? null : c.bind().strip())) {
                return c.fmt();
            }
        }
        return null;
    }
}
