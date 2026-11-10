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
import com.ash.drishti.server.approvals.ApprovalProperties;
import com.ash.drishti.kernel.api.approvals.ApprovalSource;
import com.ash.drishti.kernel.api.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Regulatory report runs (VAR_GUIDE.md section 18.14) in the Approvals queue: a frozen run waits for the next step of its sign-off chain
 * (maker, checker, then the approver the definition names). One person signs one step (four eyes); a failed check blocks every approval; a
 * send-back (reject) returns the run to the maker with a reason. A run is due {@code drishti.approvals.due-days.var-report} days after its
 * period ends, and is pinned on top as the date nears. The maker's narrative is written on the report screen.
 */
@Component
public class VarReportApprovals implements ApprovalSource {

    private final ObjectProvider<VarRegulatory> regulatory;
    private final ObjectProvider<VarService> var;
    private final ApprovalProperties props;

    public VarReportApprovals(ObjectProvider<VarRegulatory> regulatory, ObjectProvider<VarService> var, ApprovalProperties props) {
        this.regulatory = regulatory;
        this.var = var;
        this.props = props;
    }

    @Override
    public String kind() {
        return "var-report";
    }

    @Override
    public String group() {
        return "risk";
    }

    @Override
    public String label() {
        return "Report sign-off";
    }

    @Override
    public boolean enabled() {
        return regulatory.getIfAvailable() != null && var.getIfAvailable() != null;
    }

    private boolean seesAll(Principal p) {
        VarService s = var.getIfAvailable();
        return s != null && s.access().seesAll(p);
    }

    @Override
    public List<Item> items(Principal p, int limit) {
        VarRegulatory reg = regulatory.getIfAvailable();
        if (reg == null || !seesAll(p)) {
            return List.of();
        }
        List<Item> out = new ArrayList<>();
        try {
            for (VarReportDefinition d : reg.reports().definitions().values()) {
                String period = reg.reports().period(d, null).name();
                if (reg.reports().versions(d.id(), period).isEmpty() || out.size() >= limit) {
                    continue;
                }
                Map<String, Object> run = reg.reports().run(d.id(), period, null);
                out.add(item(run, reg.signoff().record(run), p));
            }
        } catch (DrishtiException e) {
            return out;                                               // no report folder, or a definition that does not read
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Item item(Map<String, Object> run, Map<String, Object> rec, Principal p) {
        List<Map<String, Object>> steps = rec.get("steps") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        boolean signed = steps.stream().anyMatch(s -> p.user().equals(s.get("who")));
        String next = (String) rec.get("next");
        Map<String, String> titles = run.get("stepTitles") instanceof Map<?, ?> t ? (Map<String, String>) t : Map.of();
        String status = next == null ? "signed" : PENDING;
        LocalDate due = null;
        try {
            due = LocalDate.parse(String.valueOf(run.get("to"))).plusDays(props.dueDays().getOrDefault(kind(), 30));
        } catch (java.time.format.DateTimeParseException e) {
            // a run without a period end has no due date
        }
        Map<String, Object> last = steps.isEmpty() ? Map.of() : steps.get(steps.size() - 1);
        String id = run.get("report") + ":" + run.get("period") + ":v" + run.get("version");
        return new Item(kind(), id, run.get("title") + " " + run.get("period") + " v" + run.get("version"), ApprovalSource.text(run.get("frozenBy")),
                ApprovalSource.instant(run.get("frozenAt")), status, ApprovalSource.text(rec.get("narrative")), next == null ? null : due,
                next != null && !signed && seesAll(p), signed, false, "/var/reports/" + run.get("report") + "?period=" + run.get("period"),
                last.isEmpty() ? null : ApprovalSource.text(last.get("who")), ApprovalSource.instant(last.get("when")),
                next == null ? "signed" : "with " + titles.getOrDefault(next, next));
    }

    private Map<String, Object> run(String id, Principal p) {
        VarRegulatory reg = regulatory.getIfAvailable();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([^:]+):([^:]+):v(\\d+)").matcher(id);
        if (reg == null || !m.matches() || !seesAll(p)) {
            throw new DrishtiException(ErrorCode.PROPOSAL_NOT_FOUND, "no report run " + id + " for you");
        }
        return reg.reports().run(m.group(1), m.group(2), Long.parseLong(m.group(3)));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Detail detail(String id, Principal p) {
        Map<String, Object> run = run(id, p);
        Map<String, Object> rec = regulatory.getObject().signoff().record(run);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (run.get("rows") instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> r = (Map<String, Object>) o;
                Map<String, Object> cells = r.get("cells") instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of();
                rows.add(ApprovalSource.row(ApprovalSource.text(r.get("label")), value(cells.get("prior-period-end")), value(cells.get("period-end"))));
            }
        }
        List<String> effect = new ArrayList<>();
        effect.add("The figures of " + run.get("period") + " (period end) against " + run.getOrDefault("priorPeriod", "the prior period") + " (before)");
        effect.add("The last approval releases the files " + run.get("outputs") + " for submission; until then only a draft, marked so, can be downloaded");
        if (run.get("checksSummary") != null) {
            effect.add("Checks: " + run.get("checksSummary"));
        }
        List<String> rules = List.of("The steps go in order: " + run.get("signOff") + "; one person signs one step (four eyes)",
                "A failed check blocks every approval; a check that needs a note needs the maker's comment",
                "Sending a run back (reject) returns it to the maker and needs a reason", "Only someone who sees every node may sign");
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("checks", run.get("checks"));
        facts.put("history", rec.get("history"));
        facts.put("narrative", rec.get("narrative"));
        return new Detail(item(run, rec, p), ApprovalSource.fieldDiff(rows), effect, rules, facts);
    }

    private static Object value(Object cell) {
        return cell instanceof Map<?, ?> m ? (m.get("value") != null ? m.get("value") : m.get("text")) : null;
    }

    @Override
    public Item decide(String id, String action, String comment, Map<String, Object> params, Principal p) {
        Map<String, Object> run = run(id, p);
        String act = switch (action) {
            case "approve" -> "approve";
            case "reject" -> "return";
            default -> throw new DrishtiException(ErrorCode.BAD_REQUEST, "a report run is approved (the next step signs) or sent back to the maker");
        };
        Map<String, Object> rec = regulatory.getObject().signoff().act(run, p.user(), act, params.get("step") == null ? null : params.get("step").toString(), comment, null);
        var.getObject().audit(p.user(), "var-report-signoff", String.valueOf(run.get("report")), "period=" + run.get("period") + " version=" + run.get("version")
                + " action=" + act + " state=" + rec.get("state"));
        return item(run, rec, p);
    }
}
