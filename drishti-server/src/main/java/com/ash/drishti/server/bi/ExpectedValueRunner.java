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
package com.ash.drishti.server.bi;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.ExpectedValue;
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.report.BiReportService;
import com.ash.drishti.server.bi.report.Report;
import com.ash.drishti.server.bi.report.ReportParser;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the expected values ({@link ExpectedValue}) of a report or a dataset (docs/guides/REPORTS.md section 14): Check shows each as passed, failed or
 * could-not-run, and publishing refuses a failing one unless a bi-admin forces it. A test runs <em>as a role</em>: its own {@code role:}, else the one the person
 * chose, else the person's own roles; so the figure is what a reader with that role's row rules and masks sees. A person may run as a role they hold; a
 * bi-steward (or bi-admin) as any. The load of the report and dataset is the caller's; only the query is the role's.
 */
public final class ExpectedValueRunner {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What one test came to. {@code status} is {@code pass}, {@code fail} or {@code error} (it could not be run: the message says why). */
    public record Outcome(String name, String kind, String target, String expect, Double actual, String status, String message, String role) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("kind", kind);
            m.put("target", target);
            m.put("expect", expect);
            m.put("actual", actual);
            m.put("status", status);
            m.put("message", message);
            m.put("role", role);
            return m;
        }
    }

    /** All the tests of an item. */
    public record Run(String item, String kind, String role, List<Outcome> outcomes) {

        public long count(String status) {
            return outcomes.stream().filter(o -> o.status().equals(status)).count();
        }

        public boolean ok() {
            return outcomes.stream().allMatch(o -> o.status().equals("pass"));
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item", item);
            m.put("kind", kind);
            m.put("role", role);
            m.put("total", outcomes.size());
            m.put("passed", count("pass"));
            m.put("failed", count("fail"));
            m.put("errors", count("error"));
            m.put("ok", ok());
            m.put("results", outcomes.stream().map(Outcome::toMap).toList());
            return m;
        }
    }

    private final BiService bi;
    private final DatasetService datasets;
    private final BiReportService reports;
    private final AuditLog audit;

    public ExpectedValueRunner(BiService bi, DatasetService datasets, BiReportService reports, AuditLog audit) {
        this.bi = bi;
        this.datasets = datasets;
        this.reports = reports;
        this.audit = audit;
    }

    /**
     * Runs the tests of a stored report or dataset (or of {@code yaml}, the draft text a designer holds, for a report).
     *
     * @param role the role to run the tests without one of their own as; blank: the caller's own roles
     */
    public Run run(Principal p, String id, String yaml, String role) {
        BiStore.Item item = bi.readableItem(p, id);
        if (!"report".equals(item.kind()) && !"dataset".equals(item.kind())) {
            throw new DrishtiException(ErrorCode.BI_INVALID, "only a report or a dataset has tests, not a " + item.kind());
        }
        if (yaml != null && !"report".equals(item.kind())) {
            bi.requirePower(p, BiAccess.AUTHOR);
        }
        String body = yaml != null ? yaml : bi.bodyFor(p, item);
        Run out = new Run(id, item.kind(), role == null || role.isBlank() ? null : role, run(p, item, body, yaml != null, role));
        audit.record(p.user(), "bi-tests-run", id, out.count("pass") + " passed, " + out.count("fail") + " failed, " + out.count("error") + " could not run"
                + (role == null || role.isBlank() ? "" : " as " + role));
        return out;
    }

    /**
     * What would stop a publish: one line per test that fails or cannot run, each as its own role or the publisher's. Empty when the item has no tests or all pass.
     */
    public List<String> failures(Principal p, BiStore.Item item) {
        if (!"report".equals(item.kind()) && !"dataset".equals(item.kind()) || item.body().isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (Outcome o : run(p, item, item.body(), false, null)) {
            if (!o.status().equals("pass")) {
                lines.add(o.name() + ": " + (o.status().equals("fail") ? "expected " + o.expect() + ", got " + (o.actual() == null ? "nothing" : trim(o.actual())) : o.message()));
            }
        }
        return lines;
    }

    private List<Outcome> run(Principal p, BiStore.Item item, String body, boolean draftText, String chosen) {
        List<Outcome> out = new ArrayList<>();
        if ("dataset".equals(item.kind())) {
            DatasetParser.Result r = DatasetParser.parse(body);
            if (r.dataset() == null) {
                throw new DrishtiException(ErrorCode.DATASET_INVALID, "the dataset has errors: " + r.firstError());
            }
            for (ExpectedValue t : r.dataset().tests()) {
                out.add(datasetTest(p, item, t, chosen));
            }
            return out;
        }
        ReportParser.Result r = ReportParser.parse(body);
        Report report = r.report();
        if (report == null) {
            throw new DrishtiException(ErrorCode.REPORT_INVALID, "the report has errors: " + r.firstError());
        }
        for (ExpectedValue t : report.tests()) {
            out.add(reportTest(p, item, report, draftText ? body : null, t, chosen));
        }
        return out;
    }

    // ---- one test ----------------------------------------------------------------------------------------------------

    private Outcome datasetTest(Principal p, BiStore.Item item, ExpectedValue t, String chosen) {
        String role = roleOf(t, chosen);
        try {
            Principal as = as(p, role);
            QueryRequest q = new QueryRequest(t.by(), List.of(t.measure()), filters(t), date(t), date(t), List.of(), 5000, "auto");
            QueryService.Result r = datasets.queryItem(as, item, q, false);
            return judge(t, "measure", t.measure(), r, t.measure(), role);
        } catch (DrishtiException e) {
            return new Outcome(t.label(), "measure", t.measure(), t.expectText(), null, "error", e.getMessage(), role);
        }
    }

    private Outcome reportTest(Principal p, BiStore.Item item, Report report, String yaml, ExpectedValue t, String chosen) {
        String role = roleOf(t, chosen);
        String kind = t.visual() != null ? "visual" : "measure";
        String target = t.visual() != null ? t.visual() : t.measure();
        try {
            Principal as = as(p, role);
            if (t.visual() == null) {
                BiReportService.Base home = reports.target(p, item.id(), yaml);
                QueryRequest q = new QueryRequest(t.by(), List.of(t.measure()), filters(t), date(t), date(t), List.of(), 5000, "auto");
                return judge(t, kind, target, datasets.queryItem(as, home.dsItem(), q, false), t.measure(), role);
            }
            Report.Page page = report.pages().stream().filter(x -> x.visual(t.visual()) != null).findFirst().orElseThrow(
                    () -> new DrishtiException(ErrorCode.REPORT_STATE, "the report has no visual '" + t.visual() + "'"));
            ObjectNode state = JSON.createObjectNode();
            var carry = state.putArray("carry");
            for (ExpectedValue.Cond c : t.filters()) {
                ObjectNode n = carry.addObject();
                n.put("field", c.field());
                n.put("op", c.op());
                var vs = n.putArray("values");
                c.values().forEach(v -> vs.add(String.valueOf(v)));
            }
            if (t.date() != null) {
                state.putObject("dates").put("from", t.date()).put("to", t.date());
            }
            BiReportService.Base b = reports.base(p, item.id(), yaml, page.id(), t.visual(), state);
            if (b.query().measures().isEmpty()) {
                throw new DrishtiException(ErrorCode.REPORT_VISUAL, "the visual '" + t.visual() + "' asks for no measure, so it shows no number");
            }
            String column = t.column() != null ? t.column() : b.query().measures().get(0);
            return judge(t, kind, target, datasets.queryItem(as, b.dsItem(), b.query(), false), column, role);
        } catch (DrishtiException e) {
            return new Outcome(t.label(), kind, target, t.expectText(), null, "error", e.getMessage(), role);
        }
    }

    /** Picks the figure out of the answer and compares it. */
    private static Outcome judge(ExpectedValue t, String kind, String target, QueryService.Result r, String column, String role) {
        ArrowIpcWriter.Column col = r.columns().stream().filter(c -> c.name().equals(column)).findFirst().orElse(null);
        if (col == null) {
            return new Outcome(t.label(), kind, target, t.expectText(), null, "error", "the answer has no column '" + column + "' (columns: "
                    + String.join(", ", r.columns().stream().map(ArrowIpcWriter.Column::name).toList()) + ")", role);
        }
        if (r.meta().masked().contains(column)) {
            return new Outcome(t.label(), kind, target, t.expectText(), null, "error", "'" + column + "' is masked for " + (role == null ? "you" : "the role " + role) + ": a masked figure cannot be checked", role);
        }
        int at = -1;
        if (!t.row().isEmpty()) {
            for (int i = 0; i < r.rows() && at < 0; i++) {
                boolean all = true;
                for (Map.Entry<String, Object> e : t.row().entrySet()) {
                    ArrowIpcWriter.Column rc = r.columns().stream().filter(c -> c.name().equals(e.getKey())).findFirst().orElse(null);
                    all &= rc != null && same(rc.values()[i], e.getValue());
                }
                at = all ? i : -1;
            }
            if (at < 0) {
                return new Outcome(t.label(), kind, target, t.expectText(), null, "fail", "no row with " + t.row(), role);
            }
        } else if (r.rows() == 1) {
            at = 0;
        } else if (r.rows() == 0) {
            return new Outcome(t.label(), kind, target, t.expectText(), null, "fail", "the answer has no rows", role);
        } else {
            return new Outcome(t.label(), kind, target, t.expectText(), null, "error", "the answer has " + r.rows() + " rows: say which one with row: { field: value }, or leave by: out", role);
        }
        Object v = col.values()[at];
        if (!(v instanceof Number n)) {
            return new Outcome(t.label(), kind, target, t.expectText(), null, "fail", "the figure is empty", role);
        }
        double actual = n.doubleValue();
        boolean ok = t.passes(actual);
        return new Outcome(t.label(), kind, target, t.expectText(), actual, ok ? "pass" : "fail", ok ? "" : "expected " + t.expectText() + ", got " + trim(actual), role);
    }

    private static boolean same(Object cell, Object want) {
        if (cell instanceof Number a && want instanceof Number b) {
            return Math.abs(a.doubleValue() - b.doubleValue()) < 1e-9;
        }
        return String.valueOf(cell).equals(String.valueOf(want));
    }

    private static String trim(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(Math.round(d * 1e6) / 1e6);
    }

    // ---- the role ----------------------------------------------------------------------------------------------------

    private static String roleOf(ExpectedValue t, String chosen) {
        return t.role() != null && !t.role().isBlank() ? t.role() : chosen == null || chosen.isBlank() ? null : chosen;
    }

    /** The reader a test runs as: the caller, or the caller carrying only {@code role}, which they must hold (a bi-steward may run as any). */
    private Principal as(Principal p, String role) {
        if (role == null) {
            return p;
        }
        if (!p.roles().contains(role) && !bi.hasPower(p, BiAccess.STEWARD)) {
            throw new DrishtiException(ErrorCode.BI_FORBIDDEN, p.user() + " does not hold the role '" + role + "', so cannot check as it (a bi-steward can check as any role)");
        }
        return new Principal(p.user(), List.of(role), p.embedApp());
    }

    private static List<QueryRequest.Filter> filters(ExpectedValue t) {
        return t.filters().stream().map(c -> new QueryRequest.Filter(c.field(), c.op(), c.values())).toList();
    }

    private static LocalDate date(ExpectedValue t) {
        return t.date() == null ? null : LocalDate.parse(t.date());
    }
}
