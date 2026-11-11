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
package com.ash.drishti.server.pivot;

import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.kernel.api.pivot.PivotView;
import com.ash.drishti.bicompute.explain.Explain;
import com.ash.drishti.server.explain.ExplainPanels;
import com.ash.drishti.kernel.api.explain.ExplainRequest;
import com.ash.drishti.kernel.api.explain.Explainer;
import com.ash.drishti.kernel.api.explain.Explanation;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.kernel.api.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Explain a pivot cell (kind {@code pivot}, core; BI_GUIDE.md): a cell of a pivot answer on two dates (or two cells), its difference
 * split by a dimension's members in steps that add up, by the pivot engine's own explanation ({@link Explain}), as the person (the view is
 * resolved and checked as for every pivot query: row rules and masks apply). Asked with a POST, the body the pivot's own: {@code view},
 * {@code value} (the value's label or id), {@code from}, {@code fromDate}, {@code to}, {@code toDate}, {@code by}, {@code order}. Each
 * explanation is audited ({@code pivot.explain}). {@code POST /api/v1/pivot/explain} is a deprecated alias of this for one release.
 */
@Component
final class PivotCellExplainer implements Explainer {

    private final PivotController pivot;
    private final PivotCore core;
    private final AuditLog audit;

    PivotCellExplainer(PivotController pivot, PivotCore core, AuditLog audit) {
        this.pivot = pivot;
        this.core = core;
        this.audit = audit;
    }

    @Override
    public String kind() {
        return "pivot";
    }

    @Override
    public String title() {
        return "A pivot cell: its difference between two dates or two cells, by a dimension's members";
    }

    @Override
    public List<String> measures() {
        return List.of("cell");
    }

    @Override
    public String engine() {
        return "pivot";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Explanation explain(Principal p, ExplainRequest r) {
        Map<String, Object> body = new LinkedHashMap<>(r.args());
        PivotView view = pivot.view(p, body);
        Explain.Request req = new Explain.Request(view, r.text("value"), coords(body.get("from")), r.text("fromDate"),
                body.get("to") == null ? null : coords(body.get("to")), r.text("toDate"), r.required("by", "by=DESK (the dimension whose members split the difference)"),
                r.text("order"), r.text("price"), r.text("quantity"));
        Map<String, Object> a = Explain.explain(core, pivot.on(p), req);
        audit.record(p.user(), "pivot.explain", view.source(), "from=" + req.from() + "@" + req.fromDate() + "; to=" + req.to() + "@" + req.toDate()
                + "; by=" + req.by());
        Map<String, Object> from = (Map<String, Object>) a.get("from");
        Map<String, Object> to = (Map<String, Object>) a.get("to");
        Double value = to.get("value") instanceof Number n ? n.doubleValue() : null;
        Explanation x = new Explanation("pivot", "cell", String.valueOf(to.get("coords")), a.get("measure") + " of " + view.source());
        x.value(value, "", String.valueOf(to.get("date")));
        x.origin("computed by the pivot engine in the server for you, from the rows you may see");
        x.preview(String.valueOf(a.get("words")), String.valueOf(a.get("method")));
        x.formula(a.get("measure") + " = " + a.get("function") + " over the cell's rows (" + a.get("kind") + ")");
        x.formula("difference = " + fmt(to.get("value")) + " − " + fmt(from.get("value")) + " = " + fmt(a.get("difference")));
        x.formula("split by " + a.get("by") + ": " + a.get("method"));
        x.rachana(String.valueOf(a.get("function")));
        x.input("Source", view.source());
        x.input("First cell", from.get("coords") + " on " + from.get("date"));
        x.input("Second cell", to.get("coords") + " on " + to.get("date"));
        x.input("Split by", a.get("by"));
        x.input("Order", a.get("order"));
        x.lineage(view.source(), "the view's rows on " + from.get("date") + " and " + to.get("date"), "");
        List<Map<String, Object>> steps = (List<Map<String, Object>>) a.get("steps");
        double start = from.get("value") instanceof Number n ? n.doubleValue() : 0;
        double end = value == null ? 0 : value;
        List<Map<String, Object>> wf = new ArrayList<>();
        wf.add(ExplainPanels.step("First", start, 0, start, fmt(start), "muted", true));
        double run = start;
        List<String> cols = List.of("Member", "Cause", "Before", "After", "Step", "Running");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> s : steps) {
            double st = ((Number) s.get("step")).doubleValue();
            wf.add(ExplainPanels.step(String.valueOf(s.get("member")), st, run, run + st, Explanation.signed(st), ExplainPanels.riseTone(st), false));
            run += st;
            rows.add(ExplainPanels.row(cols, List.of(String.valueOf(s.get("member")), String.valueOf(s.get("cause")), fmt(s.get("from")), fmt(s.get("to")),
                    ExplainPanels.change(st, false), fmt(s.get("cumulative"))), null, false));
        }
        wf.add(ExplainPanels.step("Second", end, 0, end, fmt(end), "muted", true));
        Map<String, Object> res = (Map<String, Object>) a.get("residual");
        x.why(String.valueOf(from.get("date")), String.valueOf(to.get("date")), ((Number) a.get("difference")).doubleValue(), true, List.of("waterfall"),
                "waterfall", List.of(ExplainPanels.markdown("answer", "The answer", String.valueOf(a.get("words")), 12),
                        ExplainPanels.waterfall("waterfall", "The steps by " + a.get("by"), wf, "", 7),
                        ExplainPanels.table("steps", "Each member's step", cols, List.of(false, false, true, true, true, true), rows, null, 5, null),
                        ExplainPanels.markdown("residual", "What is left", "Residual " + fmt(res.get("value")) + ": " + res.get("name"), 12)), a);
        x.check(true, "the steps add up to the difference");
        x.unbuilt("workbook", "explain-pivot-workbook");
        x.link("Guide: Pivot", "/help/context/pivot");
        return x;
    }

    private static String fmt(Object o) {
        return o instanceof Number n ? Explanation.number(n.doubleValue()) : "—";
    }

    private static Map<String, String> coords(Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        }
        return out;
    }
}
