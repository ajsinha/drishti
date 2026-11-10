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
package com.ash.drishti.server.edit;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.impact.ImpactService;
import com.ash.drishti.server.approvals.ApprovalSource;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.kernel.api.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Corrections and edits (write-back, APPROVALS_GUIDE.md) in the Approvals queue: a change that needs approval waits here until an approver who
 * is not its submitter approves it (the policy may ask for several); then it takes its place in its record's lane. The effect is the
 * impact service's answer for the record: what reads it downstream.
 */
@Component
public class ChangeApprovals implements ApprovalSource {

    private final ObjectProvider<EditService> edits;
    private final ObjectProvider<ImpactService> impact;
    private final Entitlements entitlements;

    public ChangeApprovals(ObjectProvider<EditService> edits, ObjectProvider<ImpactService> impact, Entitlements entitlements) {
        this.edits = edits;
        this.impact = impact;
        this.entitlements = entitlements;
    }

    @Override
    public String kind() {
        return "change";
    }

    @Override
    public String group() {
        return "data";
    }

    @Override
    public String label() {
        return "Correction";
    }

    /** Present whenever write-back is: with editing switched off it lists nothing and a decision says so (DRS-9301). */
    @Override
    public boolean enabled() {
        return edits.getIfAvailable() != null;
    }

    private boolean on() {
        EditService e = edits.getIfAvailable();
        return e != null && e.settings().enabled();
    }

    private EditService edits() {
        EditService e = edits.getIfAvailable();
        if (e == null || !e.settings().enabled()) {
            throw new DrishtiException(ErrorCode.EDIT_OFF, "editing is off on this server (edit.enabled in config/edit/edit.yaml)");
        }
        return e;
    }

    @Override
    public List<Item> items(Principal p, int limit) {
        if (!on()) {
            return List.of();
        }
        List<Item> out = new ArrayList<>();
        for (String state : List.of(ChangeRequests.AWAITING, ChangeRequests.REJECTED)) {
            for (Map<String, Object> m : edits().list(p, state, null, null, false, limit)) {
                out.add(item(m, p));
            }
        }
        return out;
    }

    private Item item(Map<String, Object> m, Principal p) {
        String state = ApprovalSource.text(m.get("state"));
        boolean mine = p.user().equals(m.get("by"));
        String status = ChangeRequests.AWAITING.equals(state) ? PENDING : state;
        Object approvals = m.get("approvals");
        String stage = m.get("needed") instanceof Number n && n.intValue() > 1
                ? (approvals instanceof List<?> l ? l.size() : 0) + " of " + n.intValue() + " approvals" : null;
        String title = m.get("kind") + " " + m.get("entity") + ": " + (m.get("items") instanceof List<?> l ? l.size() : 0) + " field(s)";
        return new Item(kind(), ApprovalSource.text(m.get("id")), title, ApprovalSource.text(m.get("by")), ApprovalSource.instant(m.get("at")), status,
                ApprovalSource.text(m.get("reason")), null, Boolean.TRUE.equals(m.get("mayDecide")), mine, PENDING.equals(status) && (mine || entitlements.isAdmin(p)),
                "/v/" + m.get("kind") + "/" + m.get("entity"), null, null, stage);
    }

    @Override
    public Detail detail(String id, Principal p) {
        Map<String, Object> m = edits().get(p, id);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (m.get("items") instanceof List<?> items) {
            for (Object o : items) {
                if (o instanceof Map<?, ?> it) {
                    rows.add(ApprovalSource.row(ApprovalSource.text(it.get("path")), it.get("from"), it.get("to")));
                }
            }
        }
        String kind = ApprovalSource.text(m.get("kind"));
        String entity = ApprovalSource.text(m.get("entity"));
        List<String> effect = new ArrayList<>();
        effect.add("Applied in order in the lane of " + kind + " " + entity + " (business date " + m.get("businessDate") + ")"
                + (m.get("connector") != null ? "; sent to the source " + m.get("connector") : ""));
        ImpactService is = impact.getIfAvailable();
        if (is != null && entitlements.mayOpen(p, kind)) {
            try {
                ImpactService.Impact im = is.analyse(EntityRef.of(kind, entity), k -> entitlements.mayOpen(p, k), AsOf.LATEST, entitlements.redactor(p));
                for (ImpactService.Group g : im.groups()) {
                    effect.add("Level " + g.level() + ": " + (g.items().size() + g.more()) + " " + g.kind() + (g.total() == null ? "" : " (total " + g.total() + ")")
                            + (g.hidden() > 0 ? ", " + g.hidden() + " you may not see" : ""));
                }
                if (im.groups().isEmpty()) {
                    effect.add("Nothing downstream reads this record");
                }
            } catch (RuntimeException e) {
                effect.add("What reads it downstream could not be worked out: " + e.getMessage());
            }
        }
        List<String> rules = List.of("An approver of changes decides (the policy's roles, else the approve power or an administrator)",
                "Never the person who submitted it, unless the policy allows self-approval", "A reason is required to reject; the draft is kept, marked");
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("target", m.get("target"));
        facts.put("approvals", m.get("approvals"));
        facts.put("needed", m.get("needed"));
        if (m.get("stateReason") != null) {
            facts.put("decision", m.get("stateReason"));
        }
        return new Detail(item(m, p), ApprovalSource.fieldDiff(rows), effect, rules, facts);
    }

    @Override
    public Item decide(String id, String action, String comment, Map<String, Object> params, Principal p) {
        EditService e = edits();
        switch (action) {
            case "approve" -> e.approve(p, id, comment);
            case "reject" -> e.reject(p, id, comment);
            case "withdraw" -> e.withdraw(p, id);
            default -> throw new DrishtiException(ErrorCode.BAD_REQUEST, "a change is approved, rejected or withdrawn");
        }
        return item(e.get(p, id), p);
    }
}
