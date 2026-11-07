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
import com.ash.drishti.server.approvals.ApprovalSource;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Stress window proposals (VAR_GUIDE.md section 18.9) in the Approvals queue: a node's stressed-VaR window, frozen with its analysis, waits
 * for another person to approve or reject it (maker-checker). The effect is the node's stressed VaR under the current window and under the
 * proposed one.
 */
@Component
public class StressWindowApprovals implements ApprovalSource {

    private final ObjectProvider<VarStressWindowController> windows;

    public StressWindowApprovals(ObjectProvider<VarStressWindowController> windows) {
        this.windows = windows;
    }

    @Override
    public String kind() {
        return "stress-window";
    }

    @Override
    public String group() {
        return "risk";
    }

    @Override
    public String label() {
        return "Stress window";
    }

    @Override
    public boolean enabled() {
        return windows.getIfAvailable() != null;
    }

    @Override
    public List<Item> items(Principal p, int limit) {
        VarStressWindowController c = windows.getIfAvailable();
        if (c == null) {
            return List.of();
        }
        try {
            List<Item> out = new ArrayList<>();
            for (Map<String, Object> r : c.proposals().list(p, limit)) {
                out.add(item(r, p));
            }
            return out;
        } catch (DrishtiException e) {
            return List.of();                                        // switched off, or the person does not see every node
        }
    }

    @SuppressWarnings("unchecked")
    private Item item(Map<String, Object> r, Principal p) {
        String by = ApprovalSource.text(r.get("proposedBy"));
        boolean mine = p != null && p.user().equals(by);
        String status = "PROPOSED".equals(r.get("status")) ? PENDING : ApprovalSource.text(r.get("status")).toLowerCase(java.util.Locale.ROOT);
        Map<String, Object> node = r.get("node") instanceof Map<?, ?> n ? (Map<String, Object>) n : Map.of();
        Map<String, Object> w = r.get("proposed") instanceof Map<?, ?> x ? (Map<String, Object>) x : Map.of();
        String title = "Stress window of " + node.getOrDefault("id", "?") + " from " + w.getOrDefault("first", "?") + " (" + r.get("length") + " days)";
        return new Item(kind(), ApprovalSource.text(r.get("id")), title, by, ApprovalSource.instant(r.get("proposedAt")), status, ApprovalSource.text(r.get("note")),
                null, PENDING.equals(status) && !mine, mine, false, "/var/stress-window?scope=" + node.getOrDefault("id", ""), (String) r.get("decidedBy"),
                ApprovalSource.instant(r.get("decidedAt")), null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Detail detail(String id, Principal p) {
        Map<String, Object> r = proposals().get(p, id);
        Map<String, Object> cur = r.get("current") instanceof Map<?, ?> x ? (Map<String, Object>) x : Map.of();
        Map<String, Object> next = r.get("proposed") instanceof Map<?, ?> x ? (Map<String, Object>) x : Map.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String k : List.of("first", "last", "var", "var10", "scenarios")) {
            if (cur.containsKey(k) || next.containsKey(k)) {
                rows.add(ApprovalSource.row(k, cur.get(k), next.get(k)));
            }
        }
        List<String> effect = new ArrayList<>();
        effect.add("Stressed VaR of " + ((Map<String, Object>) r.getOrDefault("node", Map.of())).getOrDefault("id", "the node") + ": "
                + num(cur.get("var")) + " under the current window, " + num(next.get("var")) + " under the proposed one");
        effect.add("Once approved it is the window the pricing system's stressed VaR runs on; the stress window screen shows who approved it");
        List<String> rules = List.of("Another person decides: never the one who proposed it (maker-checker)",
                "Only someone who sees every node (the long-history P&L of the bank) may decide", "A note is kept with the decision");
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("date", r.get("date"));
        facts.put("confidence", r.get("confidence"));
        facts.put("alert", r.get("alert"));
        if (r.get("decisionNote") != null) {
            facts.put("decision", r.get("decisionNote"));
        }
        return new Detail(item(r, p), ApprovalSource.fieldDiff(rows), effect, rules, facts);
    }

    private static String num(Object o) {
        return o instanceof Number n ? String.format(java.util.Locale.ROOT, "%,.0f", n.doubleValue()) : "n/a";
    }

    private VarStressProposals proposals() {
        VarStressWindowController c = windows.getIfAvailable();
        if (c == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the stress window selection is not running on this server");
        }
        return c.proposals();
    }

    @Override
    public Item decide(String id, String action, String comment, Map<String, Object> params, Principal p) {
        if (!"approve".equals(action) && !"reject".equals(action)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a stress window proposal is approved or rejected");
        }
        if ("reject".equals(action) && (comment == null || comment.isBlank())) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "say why the proposal is rejected");
        }
        return item(proposals().decide(p, id, action, comment), p);
    }
}
