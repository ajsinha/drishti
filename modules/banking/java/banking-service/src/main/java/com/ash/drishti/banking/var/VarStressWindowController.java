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
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.kernel.api.security.Principal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The stress window selection's API (VAR_GUIDE.md section 18.9; API_REFERENCE.md): the analysis of a node, and the frozen proposals with
 * the maker-checker decision is made in the one Approvals queue ({@code /api/v1/approvals}, kind {@code stress-window}).
 *
 * <pre>
 * GET  /api/v1/risk/var/stress-window?scope=BANK&amp;hierarchy=MGMT&amp;length=250&amp;confidence=0.99&amp;asOf=2026-09-30
 * POST /api/v1/risk/var/stress-window/proposals              {"scope":"BANK","hierarchy":"MGMT","first":"2007-11-30","note":"..."}
 * GET  /api/v1/risk/var/stress-window/proposals
 * GET  /api/v1/risk/var/stress-window/proposals/{id}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/risk/var/stress-window")
public class VarStressWindowController {

    private final VarStressWindow tool;
    private final VarStressProposals proposals;

    VarStressWindowController(VarService svc, StressWindowProperties props, ContentStore store, ObjectProvider<AuditLog> audit) {
        this.tool = new VarStressWindow(svc, props);
        this.proposals = new VarStressProposals(tool, store, audit.getIfAvailable());
    }

    private static LocalDate date(AsOf asOf) {
        return asOf == null ? null : asOf.businessDate();
    }

    /** The analysis of one node, with the approval of its current window when there is one. */
    @GetMapping
    @SuppressWarnings("unchecked")
    public Map<String, Object> analyse(@RequestParam(required = false) String scope, @RequestParam(required = false) String hierarchy,
            @RequestParam(required = false) Integer length, @RequestParam(required = false) Double confidence, AsOf asOf,
            @RequestAttribute(name = Principal.ATTRIBUTE, required = false) Principal p) {
        Map<String, Object> a = tool.analyse(p, scope, hierarchy, date(asOf), length, confidence);
        Map<String, Object> cur = (Map<String, Object>) a.get("current");
        String node = (String) ((Map<String, Object>) a.get("node")).get("id");
        Map<String, Object> approval = null;
        if (cur != null) {
            approval = proposals.approved(node, (String) cur.get("first")).map(m -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("id", m.get("id"));
                x.put("by", m.get("decidedBy"));
                x.put("at", m.get("decidedAt"));
                return x;
            }).orElse(null);
        }
        a.put("approval", approval);
        a.put("alertThresholdSetting", "drishti.var.stress-window.alert-threshold");
        return a;
    }

    @PostMapping("/proposals")
    public Map<String, Object> propose(@RequestBody Map<String, Object> body, AsOf asOf,
            @RequestAttribute(name = Principal.ATTRIBUTE, required = false) Principal p) {
        Object length = body.get("length");
        Object conf = body.get("confidence");
        Object date = body.get("date");
        LocalDate d = date == null ? date(asOf) : LocalDate.parse(date.toString());
        return proposals.propose(p, str(body.get("scope")), str(body.get("hierarchy")), d,
                length == null ? null : ((Number) length).intValue(), conf == null ? null : ((Number) conf).doubleValue(),
                str(body.get("first")), str(body.get("note")));
    }

    @GetMapping("/proposals")
    public Map<String, Object> list(@RequestParam(defaultValue = "50") int limit,
            @RequestAttribute(name = Principal.ATTRIBUTE, required = false) Principal p) {
        List<Map<String, Object>> items = proposals.list(p, limit);
        return Map.of("items", items, "count", items.size());
    }

    @GetMapping("/proposals/{id}")
    public Map<String, Object> get(@PathVariable String id, @RequestAttribute(name = Principal.ATTRIBUTE, required = false) Principal p) {
        return proposals.get(p, id);
    }

    /** The proposals, for the Approvals queue: a decision is made there ({@code POST /api/v1/approvals/stress-window/{id}/approve}). */
    VarStressProposals proposals() {
        return proposals;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
