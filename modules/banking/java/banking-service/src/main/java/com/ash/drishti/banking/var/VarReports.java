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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.content.ContentDocs;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The report generator (VAR_GUIDE.md section 18.14; REGULATORY_VAR_REPORTING.md section 6): for a report definition and a period it
 * reads the stored official records (never recomputing a filed figure), fills every cell with its lineage, runs the definition's checks
 * and freezes the result as a <b>run</b> in the content store ({@code var/reports/<report>/<period>/run-v<n>.json}); generating the period
 * again makes the next version, never overwrites. The sign-off and the files are {@link VarReportSignoff}'s.
 */
final class VarReports {

    static final String ROOT = "var/reports/";
    private static final Pattern RUN = Pattern.compile(".*/run-v(\\d+)\\.json");

    private final VarOfficialStore store;
    private final VarPeriodStats stats;
    private final VarBacktesting backtesting;
    private final RegulatoryProperties props;

    VarReports(VarOfficialStore store, VarPeriodStats stats, VarBacktesting backtesting, RegulatoryProperties props) {
        this.store = store;
        this.stats = stats;
        this.backtesting = backtesting;
        this.props = props;
    }

    ContentDocs docs() {
        return store.docs();
    }

    Map<String, VarReportDefinition> definitions() {
        try {
            return VarReportDefinition.all(Path.of(props.reportsDir()));
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    VarReportDefinition definition(String id) {
        VarReportDefinition d = definitions().get(id);
        if (d == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no report definition " + id + " in " + props.reportsDir() + " (the reports: "
                    + definitions().keySet() + ")");
        }
        return d;
    }

    static String folder(String report, String period) {
        return ROOT + report + "/" + period.replace("..", "_to_") + "/";
    }

    /** The versions of a report's runs of a period, oldest first. */
    List<Long> versions(String report, String period) {
        List<Long> out = new ArrayList<>();
        for (String k : store.docs().store().list(folder(report, period))) {
            Matcher m = RUN.matcher(k);
            if (m.matches()) {
                out.add(Long.parseLong(m.group(1)));
            }
        }
        out.sort(null);
        return out;
    }

    /** A run (null version: the newest); 404 in words when there is none. */
    @SuppressWarnings("unchecked")
    Map<String, Object> run(String report, String period, Long version) {
        List<Long> vs = versions(report, period);
        if (vs.isEmpty()) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no run of " + report + " for " + period + " yet: generate it");
        }
        long v = version == null ? vs.get(vs.size() - 1) : version;
        return store.docs().value(folder(report, period) + "run-v" + v + ".json", Map.class).map(x -> (Map<String, Object>) x)
                .orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no run v" + v + " of " + report + " for " + period));
    }

    /** The period a report runs over: its own kind (a fiscal year or quarter) or the text given; the current one when none. */
    VarPeriodStats.Period period(VarReportDefinition d, String text) {
        String t = text;
        if (t == null || t.isBlank()) {
            LocalDate newest = store.dates().isEmpty() ? LocalDate.now(java.time.ZoneOffset.UTC) : store.dates().last();
            t = VarPeriodStats.fiscalYear(newest, props.yearEnd());
            if (d.period().equals("fiscal-quarter")) {
                LocalDate start = VarPeriodStats.period(t, props.yearEnd()).from();
                t += "Q" + (1 + (int) (java.time.temporal.ChronoUnit.MONTHS.between(start, newest) / 3));
            }
        }
        try {
            return VarPeriodStats.period(t, props.yearEnd());
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    /** Generates and freezes the next run of a report for a period. */
    Map<String, Object> generate(String report, String periodText, String who) {
        VarReportDefinition d = definition(report);
        VarPeriodStats.Period p = period(d, periodText);
        Map<String, Object> run = d.type().equals("backtesting-chart") ? chart(d, p) : table(d, p);
        List<Map<String, Object>> checks = checks(d, p, run);
        run.put("checks", checks);
        run.put("checksSummary", summary(checks));
        List<Long> vs = versions(report, p.name());
        long version = vs.isEmpty() ? 1 : vs.get(vs.size() - 1) + 1;
        run.put("version", version);
        run.put("frozenAt", Instant.now().toString());
        run.put("frozenBy", who);
        run.put("code", "drishti-server " + version());
        if (!store.docs().create(folder(report, p.name()) + "run-v" + version + ".json", run)) {
            throw new DrishtiException(ErrorCode.CONTENT_CONFLICT, "the run v" + version + " of " + report + " for " + p.name() + " was made at the same time: try again");
        }
        return run;
    }

    private static String version() {
        String v = VarReports.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    private Map<String, Object> head(VarReportDefinition d, VarPeriodStats.Period p) {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("report", d.id());
        run.put("title", d.title());
        run.put("type", d.type());
        run.put("period", p.name());
        run.put("from", p.from().toString());
        run.put("to", p.to().toString());
        run.put("measure", d.measure());
        run.put("units", d.units());
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("file", d.file());
        def.put("digest", d.digest());
        run.put("definition", def);
        run.put("signOff", d.signOff());
        run.put("stepTitles", d.stepTitles());
        run.put("outputs", d.outputs());
        return run;
    }

    // ------------------------------------------------------------------------------------------------------------- var-table

    private Map<String, Object> table(VarReportDefinition d, VarPeriodStats.Period p) {
        Map<String, Object> run = head(d, p);
        VarPeriodStats.Period prior = VarPeriodStats.priorYear(p, props.yearEnd());
        Map<String, Map<String, Object>> st = new LinkedHashMap<>();
        Map<String, Map<String, Object>> pr = new LinkedHashMap<>();
        for (VarReportDefinition.Row r : d.rows()) {
            if (r.node() != null) {
                st.put(r.node(), stats.stats(r.node(), d.measure(), p));
                pr.put(r.node(), stats.stats(r.node(), d.measure(), prior));
            }
        }
        String total = d.totalNode();
        Map<String, Object> ts = st.get(total);
        run.put("preview", ts.get("preview"));
        if (ts.get("previewNote") != null) {
            run.put("previewNote", ts.get("previewNote"));
        }
        run.put("statistics", ts);
        run.put("records", ts.get("records"));
        run.put("synthetic", ts.get("synthetic"));
        run.put("priorPeriod", prior.name());
        run.put("columns", d.columns());
        List<Map<String, Object>> rows = new ArrayList<>();
        double[] sumParts = new double[4];
        double[] sumPrior = new double[1];
        boolean partsComplete = true;
        for (VarReportDefinition.Row r : d.rows()) {
            if (r.node() == null || r.total()) {
                continue;
            }
            Map<String, Object> s = st.get(r.node());
            double pe = value(s.get("periodEnd"));
            double avg = VarOfficialStore.num(s.get("average"));
            double ppe = value(pr.get(r.node()).get("periodEnd"));
            partsComplete &= Double.isFinite(pe) && Double.isFinite(avg);
            sumParts[0] += pe;
            sumParts[1] += avg;
            sumPrior[0] += Double.isFinite(ppe) ? ppe : 0;
        }
        for (VarReportDefinition.Row r : d.rows()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", r.label());
            row.put("code", r.code());
            row.put("node", r.node());
            row.put("total", r.total());
            Map<String, Object> cells = new LinkedHashMap<>();
            if (r.compute() != null) {                                 // diversification: the total less the rows, where it adds up
                row.put("computed", true);
                double tpe = value(ts.get("periodEnd"));
                double tavg = VarOfficialStore.num(ts.get("average"));
                double tppe = value(pr.get(total).get("periodEnd"));
                for (String c : d.columns()) {
                    Map<String, Object> cell = new LinkedHashMap<>();
                    switch (c) {
                        case "period-end" -> cell.put("value", partsComplete ? tpe - sumParts[0] : null);
                        case "average" -> cell.put("value", partsComplete ? tavg - sumParts[1] : null);
                        case "prior-period-end" -> cell.put("value", Double.isFinite(tppe) ? tppe - sumPrior[0] : null);
                        default -> {
                            cell.put("value", null);
                            cell.put("text", "n/m");
                        }
                    }
                    cell.put("lineage", Map.of("computed", "the total row less the sum of the rows above it, for the " + c
                            + (c.equals("high") || c.equals("low") ? ": not meaningful (the rows' highs and lows fall on different days)" : "")));
                    cells.put(c, cell);
                }
            } else {
                Map<String, Object> s = st.get(r.node());
                Map<String, Object> ps = pr.get(r.node());
                for (String c : d.columns()) {
                    cells.put(c, cell(c, r, s, ps, d, p, prior));
                }
            }
            row.put("cells", cells);
            rows.add(row);
        }
        run.put("rows", rows);
        run.put("facts", facts(d, p, rows, ts));
        run.put("footnotes", List.of("n/m: not meaningful (high and low fall on different days per row, so no diversification is computed for them).",
                "Period statistics over " + ts.get("records") + " official records (" + p.from() + " to " + p.to() + ")"
                        + (((List<?>) ts.getOrDefault("missing", List.of())).isEmpty() ? "; none missing." : "; missing: " + ts.get("missing") + "."),
                "Movement: the period end against the prior period's; over 10% needs a note."));
        return run;
    }

    private Map<String, Object> cell(String c, VarReportDefinition.Row r, Map<String, Object> s, Map<String, Object> ps, VarReportDefinition d,
            VarPeriodStats.Period p, VarPeriodStats.Period prior) {
        Map<String, Object> cell = new LinkedHashMap<>();
        Map<String, Object> lin = new LinkedHashMap<>();
        lin.put("node", r.node());
        lin.put("measure", d.measure());
        lin.put("statistic", c);
        lin.put("definition", d.file() + " (digest " + d.digest() + ")");
        switch (c) {
            case "period-end", "high", "low" -> {
                Object pt = s.get(c.equals("period-end") ? "periodEnd" : c);
                cell.put("value", pt instanceof Map<?, ?> m ? m.get("value") : null);
                if (pt instanceof Map<?, ?> m) {
                    lin.put("date", m.get("date"));
                    lin.put("record", "var/official/" + m.get("date") + ".json");
                    lin.put("resultVersion", m.get("resultVersion"));
                    lin.put("captureId", m.get("captureId"));
                    lin.put("inputs", inputs(LocalDate.parse(String.valueOf(m.get("date")))));
                }
                lin.put("period", p.name() + " (" + p.from() + " to " + p.to() + ")");
            }
            case "average" -> {
                cell.put("value", s.get("average"));
                lin.put("records", s.get("records"));
                lin.put("period", p.name() + " (" + p.from() + " to " + p.to() + ")");
            }
            case "prior-period-end" -> {
                Object pt = ps.get("periodEnd");
                cell.put("value", pt instanceof Map<?, ?> m ? m.get("value") : null);
                if (pt instanceof Map<?, ?> m) {
                    lin.put("date", m.get("date"));
                    lin.put("record", "var/official/" + m.get("date") + ".json");
                    lin.put("resultVersion", m.get("resultVersion"));
                } else {
                    cell.put("text", "n/a");
                    lin.put("note", "no official record in " + prior.name());
                }
                lin.put("period", prior.name());
            }
            case "movement" -> {
                double now = value(s.get("periodEnd"));
                double before = value(ps.get("periodEnd"));
                if (Double.isFinite(now) && Double.isFinite(before) && before != 0) {
                    cell.put("value", (now - before) / Math.abs(before));
                } else {
                    cell.put("value", null);
                    cell.put("text", "n/a");
                }
                lin.put("computed", "(period end - prior period end) / prior period end");
            }
            default -> cell.put("value", null);
        }
        cell.put("lineage", lin);
        return cell;
    }

    /** The data versions an official record was computed from (its inputs), or what it says it is. */
    private Object inputs(LocalDate d) {
        Map<String, Object> rec = store.index(d).orElse(null);
        if (rec == null) {
            return null;
        }
        return Boolean.TRUE.equals(rec.get("synthetic")) ? "synthetic demo history" : rec.get("inputs");
    }

    static double value(Object point) {
        return point instanceof Map<?, ?> m ? VarOfficialStore.num(m.get("value")) : Double.NaN;
    }

    /** The narrative's facts, pre-filled: the person writes the text. */
    private Map<String, Object> facts(VarReportDefinition d, VarPeriodStats.Period p, List<Map<String, Object>> rows, Map<String, Object> ts) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("average", ts.get("average"));
        f.put("periodEnd", ts.get("periodEnd"));
        f.put("high", ts.get("high"));
        f.put("low", ts.get("low"));
        String driver = null;
        double most = 0;
        for (Map<String, Object> r : rows) {
            if (Boolean.TRUE.equals(r.get("total")) || Boolean.TRUE.equals(r.get("computed"))) {
                continue;
            }
            Object mv = ((Map<String, Object>) ((Map<String, Object>) r.get("cells")).getOrDefault("movement", Map.of())).get("value");
            if (mv instanceof Number n && Math.abs(n.doubleValue()) > Math.abs(most)) {
                most = n.doubleValue();
                driver = String.valueOf(r.get("label"));
            }
        }
        f.put("largestMovement", driver == null ? null : Map.of("row", driver, "movement", most));
        Object pe = ts.get("periodEnd");
        if (pe instanceof Map<?, ?> m) {
            Map<String, Object> bt = backtesting.answer(d.totalNode(), LocalDate.parse(String.valueOf(m.get("date"))), false);
            f.put("backtesting", btFacts(bt));
        }
        StringBuilder text = new StringBuilder();
        text.append("Average ").append(d.title().toLowerCase(java.util.Locale.ROOT).startsWith("trading") ? "trading VaR" : d.measure().toUpperCase(java.util.Locale.ROOT))
                .append(" for ").append(p.name()).append(" was ").append(millions(ts.get("average"))).append(". Period-end VaR was ")
                .append(millions(value(pe))).append('.');
        if (driver != null) {
            text.append(" The largest movement against the prior period was ").append(driver).append(" (").append(pct(most)).append(").");
        }
        if (f.get("backtesting") instanceof Map<?, ?> b && b.get("actual") != null) {
            text.append(" There were ").append(b.get("actual")).append(" backtesting exceptions against actual P&L (").append(b.get("hypothetical"))
                    .append(" against hypothetical) over the last ").append(b.get("days")).append(" business days")
                    .append(((List<?>) b.get("dates")).isEmpty() ? "." : ", on " + String.join(", ", ((List<?>) b.get("dates")).stream().map(String::valueOf).toList()) + ".");
        }
        f.put("text", text.toString());
        return f;
    }

    private static Map<String, Object> btFacts(Map<String, Object> bt) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", bt.get("node"));
        out.put("days", bt.get("days"));
        out.put("zone", bt.get("zone"));
        out.put("multiplier", bt.get("multiplier"));
        if (bt.get("counts") instanceof List<?> cs && !cs.isEmpty() && cs.get(0) instanceof Map<?, ?> c) {
            out.put("actual", c.get("actual"));
            out.put("hypothetical", c.get("hypothetical"));
        }
        List<String> dates = new ArrayList<>();
        if (bt.get("exceptions") instanceof List<?> ex) {
            for (Object o : ex) {
                if (o instanceof Map<?, ?> e && !"hypothetical".equals(e.get("which"))) {
                    dates.add(String.valueOf(e.get("date")));
                }
            }
        }
        out.put("dates", dates);
        return out;
    }

    static String millions(Object v) {
        double x = v instanceof Number n ? n.doubleValue() : Double.NaN;
        return Double.isFinite(x) ? String.format(java.util.Locale.ROOT, "$%.1f million", x / 1e6) : "n/a";
    }

    static String pct(double x) {
        return String.format(java.util.Locale.ROOT, "%+.1f%%", x * 100);
    }

    // ------------------------------------------------------------------------------------------------------------- the chart

    @SuppressWarnings("unchecked")
    private Map<String, Object> chart(VarReportDefinition d, VarPeriodStats.Period p) {
        Map<String, Object> run = head(d, p);
        LocalDate newest = store.dates().isEmpty() ? null : store.dates().floor(p.to());
        if (newest == null || newest.isBefore(p.from())) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no official record in " + p.name() + ": nothing to chart");
        }
        run.put("preview", newest.isBefore(p.to()));
        if (newest.isBefore(p.to())) {
            run.put("previewNote", p.name() + " closes " + p.to() + ": the chart runs to the newest official record, " + newest
                    + "; the run of record is made on the period end");
        }
        Map<String, Object> bt = backtesting.answer(d.node(), newest, false);
        bt.remove("parts");
        run.put("node", d.node());
        run.put("backtest", bt);
        run.put("records", bt.get("days"));
        run.put("synthetic", bt.get("synthetic"));
        List<String> dates = new ArrayList<>();
        for (Object o : (List<Object>) bt.getOrDefault("exceptions", List.of())) {
            dates.add(String.valueOf(((Map<?, ?>) o).get("date")));
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        Map<String, Object> c0 = bt.get("counts") instanceof List<?> l && !l.isEmpty() ? (Map<String, Object>) l.get(0) : Map.of();
        facts.put("text", "Over the " + bt.get("days") + " business days to " + newest + ", " + c0.get("actual") + " exceptions against actual P&L and "
                + c0.get("hypothetical") + " against hypothetical at " + com.ash.drishti.banking.compute.var.VarLens.pct(props.backtesting().confidences().get(0))
                + (dates.isEmpty() ? "" : " (" + String.join(", ", dates) + ")") + "; zone " + bt.get("zone") + ", multiplier " + bt.get("multiplier") + ".");
        run.put("facts", facts);
        run.put("varAtEnd", bt.get("var"));
        run.put("endDate", newest.toString());
        return run;
    }

    // ------------------------------------------------------------------------------------------------------------- checks

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> checks(VarReportDefinition d, VarPeriodStats.Period p, Map<String, Object> run) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : d.checks()) {
            String kind = String.valueOf(c.get("check"));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("check", kind);
            try {
                switch (kind) {
                    case "total-equals-official" -> totalEqualsOfficial(d, run, r);
                    case "rows-sum-to-total" -> rowsSum(run, r);
                    case "no-missing-records" -> {
                        List<?> missing = d.type().equals("backtesting-chart") ? (List<?>) ((Map<?, ?>) run.get("backtest")).get("untested")
                                : (List<?>) ((Map<String, Object>) run.get("statistics")).getOrDefault("missing", List.of());
                        r.put("title", "No official record missing inside the period");
                        r.put("status", missing.isEmpty() ? "pass" : "fail");
                        r.put("evidence", missing.isEmpty() ? run.get("records") + " records" : missing.size() + " missing: " + missing.subList(0, Math.min(5, missing.size())));
                    }
                    case "same-total-as" -> sameTotal(d, c, run, r);
                    case "movement-needs-note" -> movement(c, run, r);
                    case "backtesting-exceptions" -> {
                        Map<String, Object> bt = d.type().equals("backtesting-chart") ? (Map<String, Object>) run.get("backtest")
                                : (Map<String, Object>) ((Map<String, Object>) run.get("facts")).get("backtesting");
                        r.put("title", "Backtesting exceptions against the zone");
                        Object zone = bt == null ? null : bt.get("zone");
                        r.put("status", zone == null ? "fail" : "red".equals(zone) ? "note" : "pass");
                        r.put("evidence", zone == null ? "no backtest (no desk P&L)" : "zone " + zone + ", multiplier " + bt.get("multiplier"));
                    }
                    case "window-complete" -> {
                        Map<String, Object> bt = (Map<String, Object>) run.get("backtest");
                        int days = bt == null || bt.get("days") == null ? 0 : ((Number) bt.get("days")).intValue();
                        r.put("title", "The backtest covers the whole window");
                        r.put("status", days >= props.backtesting().window() ? "pass" : "fail");
                        r.put("evidence", days + " of " + props.backtesting().window() + " business days");
                    }
                    default -> {
                        r.put("status", "fail");
                        r.put("evidence", "unknown check");
                    }
                }
            } catch (RuntimeException e) {
                r.put("status", "fail");
                r.put("evidence", String.valueOf(e.getMessage()));
            }
            out.add(r);
        }
        return out;
    }

    private void totalEqualsOfficial(VarReportDefinition d, Map<String, Object> run, Map<String, Object> r) {
        String node = d.totalNode();
        r.put("title", "The total equals the official " + d.measure().toUpperCase(java.util.Locale.ROOT) + " of " + node + " on every date used");
        LocalDate end;
        double frozen;
        if (d.type().equals("backtesting-chart")) {
            end = LocalDate.parse(String.valueOf(run.get("endDate")));
            frozen = VarOfficialStore.num(run.get("varAtEnd"));
        } else {
            Map<?, ?> pe = (Map<?, ?>) ((Map<?, ?>) run.get("statistics")).get("periodEnd");
            if (pe == null) {
                r.put("status", "fail");
                r.put("evidence", "no official record in the period");
                return;
            }
            end = LocalDate.parse(String.valueOf(pe.get("date")));
            frozen = VarOfficialStore.num(pe.get("value"));
        }
        Map<String, Object> rec = store.index(end).orElse(null);
        double official = rec != null && rec.get("nodes") instanceof Map<?, ?> n && n.get(node) instanceof Map<?, ?> f
                ? VarOfficialStore.num(f.get(d.measure())) : Double.NaN;
        boolean ok = Double.isFinite(official) && Math.abs(official - frozen) < 0.5;
        r.put("status", ok ? "pass" : "fail");
        r.put("evidence", String.format(java.util.Locale.ROOT, "%,.0f %s %,.0f (the record of %s)", frozen, ok ? "=" : "≠", official, end));
    }

    @SuppressWarnings("unchecked")
    private static void rowsSum(Map<String, Object> run, Map<String, Object> r) {
        r.put("title", "The rows plus diversification sum to the total");
        double[] sum = new double[2];
        double[] total = new double[2];
        for (Map<String, Object> row : (List<Map<String, Object>>) run.getOrDefault("rows", List.of())) {
            Map<String, Map<String, Object>> cells = (Map<String, Map<String, Object>>) row.get("cells");
            int i = 0;
            for (String c : List.of("period-end", "average")) {
                double v = cells.containsKey(c) ? VarOfficialStore.num(cells.get(c).get("value")) : Double.NaN;
                if (Boolean.TRUE.equals(row.get("total"))) {
                    total[i] = v;
                } else {
                    sum[i] += v;
                }
                i++;
            }
        }
        boolean ok = Math.abs(sum[0] - total[0]) < 1 && Math.abs(sum[1] - total[1]) < 1;
        r.put("status", ok ? "pass" : "fail");
        r.put("evidence", ok ? "period end and average" : String.format(java.util.Locale.ROOT, "%,.0f against %,.0f", sum[0], total[0]));
    }

    private void sameTotal(VarReportDefinition d, Map<String, Object> c, Map<String, Object> run, Map<String, Object> r) {
        String other = String.valueOf(c.get("report"));
        VarReportDefinition o = definition(other);
        r.put("title", "The same total as " + other);
        LocalDate end = d.type().equals("backtesting-chart") ? LocalDate.parse(String.valueOf(run.get("endDate")))
                : LocalDate.parse(String.valueOf(((Map<?, ?>) ((Map<?, ?>) run.get("statistics")).get("periodEnd")).get("date")));
        Map<String, Object> rec = store.index(end).orElse(null);
        double mine = figure(rec, d.totalNode(), d.measure());
        double theirs = figure(rec, o.totalNode(), o.type().equals("backtesting-chart") ? "var" : o.measure());
        boolean ok = Double.isFinite(mine) && Math.abs(mine - theirs) < 0.5;
        r.put("status", ok ? "pass" : "fail");
        r.put("evidence", String.format(java.util.Locale.ROOT, "%,.0f %s %,.0f on %s", mine, ok ? "=" : "≠", theirs, end));
    }

    private static double figure(Map<String, Object> rec, String node, String measure) {
        return rec != null && rec.get("nodes") instanceof Map<?, ?> n && n.get(node) instanceof Map<?, ?> f ? VarOfficialStore.num(f.get(measure))
                : Double.NaN;
    }

    @SuppressWarnings("unchecked")
    private static void movement(Map<String, Object> c, Map<String, Object> run, Map<String, Object> r) {
        double threshold = c.get("threshold") instanceof Number n ? n.doubleValue() : 0.10;
        r.put("title", "A movement over " + Math.round(threshold * 100) + "% against the prior period needs a note");
        List<String> over = new ArrayList<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) run.getOrDefault("rows", List.of())) {
            Map<String, Map<String, Object>> cells = (Map<String, Map<String, Object>>) row.get("cells");
            Object v = cells.containsKey("movement") ? cells.get("movement").get("value") : null;
            if (v instanceof Number x && Math.abs(x.doubleValue()) > threshold) {
                over.add(row.get("label") + " " + pct(x.doubleValue()));
                cells.get("movement").put("flag", true);
            }
        }
        r.put("status", over.isEmpty() ? "pass" : "note");
        r.put("evidence", over.isEmpty() ? "none over " + Math.round(threshold * 100) + "%" : String.join("; ", over) + ": the maker's comment is the note");
    }

    static Map<String, Object> summary(List<Map<String, Object>> checks) {
        Map<String, Object> s = new LinkedHashMap<>();
        for (String k : List.of("pass", "note", "fail")) {
            s.put(k, checks.stream().filter(c -> k.equals(c.get("status"))).count());
        }
        return s;
    }
}
