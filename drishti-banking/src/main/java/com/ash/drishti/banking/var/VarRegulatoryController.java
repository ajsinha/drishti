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
package com.ash.drishti.banking.var;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Regulatory VaR's API (VAR_GUIDE.md sections 18.10, 18.11 and 18.14 and 11.1): the backtest, the period statistics, the report runs with their
 * sign-off and files, and a demo server's synthetic official history. Every answer reads the official records, which cover every node:
 * a viewer who sees only some nodes is refused (403), as for the official record itself.
 *
 * <pre>
 *   GET  /api/v1/risk/var/backtesting?scope=&amp;asOf=&amp;format=xlsx      the backtest of a node: exceptions, zone, multiplier, the chart's days, the desks
 *   GET  /api/v1/risk/var/statistics?scope=&amp;period=&amp;measure=        period end, average, high, low; the 60-day and 12-week stressed averages
 *   GET  /api/v1/risk/var/reports                                    the report definitions and their newest runs
 *   POST /api/v1/risk/var/reports/{report}/runs?period=              generate and freeze the next run of a period
 *   GET  /api/v1/risk/var/reports/{report}/runs/{period}?version=    a run with its checks, lineage and sign-off record
 *   POST /api/v1/risk/var/reports/{report}/runs/{period}/narrative   {version, narrative}: the maker's text (the sign-off is in /api/v1/approvals)
 *   GET  /api/v1/risk/var/reports/{report}/runs/{period}/file?format=xlsx|csv|pdf&amp;version=&amp;draft=
 *   POST /api/v1/risk/var/official/synthetic-history?days=           a demo server's synthetic official history (administrators)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/risk/var")
public class VarRegulatoryController {

    private final VarService svc;
    private final VarBacktesting backtesting;
    private final VarPeriodStats stats;
    private final VarReports reports;
    private final VarReportSignoff signoff;
    private final VarSyntheticHistory synthetic;
    private final RegulatoryProperties props;
    private final Entitlements entitlements;

    VarRegulatoryController(VarService svc, VarRegulatory reg, RegulatoryProperties props, Entitlements entitlements) {
        this.svc = svc;
        this.backtesting = reg.backtesting();
        this.stats = reg.stats();
        this.reports = reg.reports();
        this.signoff = reg.signoff();
        this.synthetic = reg.synthetic();
        this.props = props;
        this.entitlements = entitlements;
    }

    private void seesAll(Principal p) {
        if (!svc.access().seesAll(p)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "regulatory VaR reads the official records, which cover every node: your role sees only some of them");
        }
    }

    /** The official node a scope names: BANK, DESK:RATES, LE:LE-NY, NODE:MGMT~DIV-MARKETS (a basket or a trade has no official record). */
    private String node(Principal p, String scope) {
        seesAll(p);
        String s = scope == null || scope.isBlank() ? "BANK" : scope.strip();
        if (s.toUpperCase(java.util.Locale.ROOT).startsWith("NODE:")) {
            return s.substring(5);
        }
        String node = svc.scoped(p, s, null).res().node();
        if (node == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "regulatory VaR is kept for the hierarchies' nodes (their official records): BANK, "
                    + "DESK:RATES, LE:LE-NY or NODE:<id>, not " + s);
        }
        return node;
    }

    private static LocalDate date(AsOf asOf) {
        return asOf == null ? null : asOf.businessDate();
    }

    @GetMapping("/backtesting")
    public ResponseEntity<Object> backtest(@RequestParam(required = false) String scope, @RequestParam(required = false) String format, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        String node = node(p, scope);
        Map<String, Object> out = backtesting.answer(node, date(asOf), true);
        if ("xlsx".equalsIgnoreCase(format)) {
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("type", "backtesting-chart");
            run.put("title", "Backtesting " + out.get("name"));
            run.put("period", out.get("to"));
            run.put("from", out.get("from"));
            run.put("to", out.get("to"));
            run.put("version", "-");
            run.put("frozenAt", java.time.Instant.now().toString());
            run.put("frozenBy", p.user());
            run.put("definition", Map.of("file", "(computed on demand, not a report run)"));
            run.put("node", node);
            run.put("backtest", out);
            byte[] bytes = com.ash.drishti.server.dist.XlsxWriter.write(VarReportSignoff.doc(run, Map.of(), false));
            return file(bytes, "backtesting_" + node.replace('~', '_') + "_" + out.get("to") + ".xlsx", "xlsx");
        }
        svc.audit(p.user(), "var-backtesting", node, "asOf=" + date(asOf));
        return ResponseEntity.ok(out);
    }

    @GetMapping("/statistics")
    public Map<String, Object> statistics(@RequestParam(required = false) String scope, @RequestParam(required = false) String period,
            @RequestParam(defaultValue = "var") String measure, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        String node = node(p, scope);
        try {
            String t = period == null || period.isBlank() ? VarPeriodStats.fiscalYear(svc.official().dates().isEmpty() ? LocalDate.now(java.time.ZoneOffset.UTC)
                    : svc.official().dates().last(), props.yearEnd()) : period;
            Map<String, Object> out = stats.stats(node, measure.toLowerCase(java.util.Locale.ROOT), VarPeriodStats.period(t, props.yearEnd()));
            out.put("fiscalYearEnd", props.fiscalYearEnd());
            return out;
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/reports")
    public Map<String, Object> reports(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        seesAll(p);
        List<Map<String, Object>> list = new ArrayList<>();
        for (VarReportDefinition d : reports.definitions().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("report", d.id());
            m.put("title", d.title());
            m.put("description", d.description());
            m.put("type", d.type());
            m.put("period", d.period());
            m.put("signOff", d.signOff());
            m.put("stepTitles", d.stepTitles());
            m.put("outputs", d.outputs());
            m.put("file", d.file());
            String period = reports.period(d, null).name();
            m.put("currentPeriod", period);
            List<Long> vs = reports.versions(d.id(), period);
            if (!vs.isEmpty()) {
                Map<String, Object> run = reports.run(d.id(), period, null);
                Map<String, Object> rec = signoff.record(run);
                m.put("run", Map.of("version", run.get("version"), "frozenAt", run.get("frozenAt"), "state", rec.get("state"),
                        "checks", run.get("checksSummary")));
            }
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fiscalYearEnd", props.fiscalYearEnd());
        out.put("dir", props.reportsDir());
        out.put("reports", list);
        return out;
    }

    @PostMapping("/reports/{report}/runs")
    public Map<String, Object> generate(@PathVariable String report, @RequestParam(required = false) String period,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        seesAll(p);
        Map<String, Object> run = reports.generate(report, period, p.user());
        svc.audit(p.user(), "var-report-run", report, "period=" + run.get("period") + " version=" + run.get("version"));
        return signoff.view(run);
    }

    @GetMapping("/reports/{report}/runs/{period}")
    public Map<String, Object> run(@PathVariable String report, @PathVariable String period, @RequestParam(required = false) Long version,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        seesAll(p);
        return signoff.view(reports.run(report, period, version));
    }

    /**
     * The maker's narrative, written before the maker signs. The sign-off itself (approve a step, send the run back) is made in the one
     * Approvals queue ({@code /api/v1/approvals}, kind {@code var-report}).
     */
    @PostMapping("/reports/{report}/runs/{period}/narrative")
    public Map<String, Object> narrative(@PathVariable String report, @PathVariable String period, @RequestBody Map<String, Object> body,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        seesAll(p);
        Long version = body.get("version") instanceof Number n ? n.longValue() : null;
        Map<String, Object> run = reports.run(report, period, version);
        Map<String, Object> rec = signoff.act(run, p.user(), "narrative", null, null, str(body.get("narrative")));
        svc.audit(p.user(), "var-report-signoff", report, "period=" + period + " version=" + run.get("version") + " action=narrative state=" + rec.get("state"));
        return signoff.view(run);
    }

    @GetMapping("/reports/{report}/runs/{period}/file")
    public ResponseEntity<Object> file(@PathVariable String report, @PathVariable String period, @RequestParam(defaultValue = "xlsx") String format,
            @RequestParam(required = false) Long version, @RequestParam(defaultValue = "false") boolean draft,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        seesAll(p);
        Map<String, Object> run = reports.run(report, period, version);
        String f = format.toLowerCase(java.util.Locale.ROOT);
        boolean signed = "signed".equals(signoff.record(run).get("state"));
        byte[] bytes = signoff.file(run, f, draft);
        svc.audit(p.user(), "var-report-file", report, "period=" + period + " version=" + run.get("version") + " format=" + f + (signed ? "" : " draft"));
        return file(bytes, VarReportSignoff.filename(run, f, !signed), f);
    }

    @PostMapping("/official/synthetic-history")
    public Map<String, Object> seed(@RequestParam(defaultValue = "259") int days, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return synthetic.seed(days);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static ResponseEntity<Object> file(byte[] bytes, String name, String format) {
        MediaType type = switch (format) {
            case "csv" -> MediaType.parseMediaType("text/csv");
            case "pdf" -> MediaType.APPLICATION_PDF;
            default -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        };
        return ResponseEntity.ok().contentType(type).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(bytes);
    }
}
