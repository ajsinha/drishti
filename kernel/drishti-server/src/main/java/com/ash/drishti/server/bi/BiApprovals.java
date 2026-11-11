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
import com.ash.drishti.kernel.api.approvals.ApprovalSource;
import com.ash.drishti.kernel.api.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * BI items (datasets, models, reports, boards) proposed for review in the Approvals queue. The rules are {@link BiService}'s: a bi-steward,
 * or a member of the workspace whose role has the approve power, decides; with four eyes on, never the person who proposed it. The effect
 * is the governance impact: the published reports a change would break or alter.
 */
@Component
public class BiApprovals implements ApprovalSource {

    private final ObjectProvider<BiService> bi;
    private final ObjectProvider<BiGovernance> gov;

    public BiApprovals(ObjectProvider<BiService> bi, ObjectProvider<BiGovernance> gov) {
        this.bi = bi;
        this.gov = gov;
    }

    @Override
    public String kind() {
        return "bi";
    }

    @Override
    public String group() {
        return "data";
    }

    @Override
    public String label() {
        return "BI item";
    }

    @Override
    public boolean enabled() {
        return bi.getIfAvailable() != null && gov.getIfAvailable() != null;
    }

    @Override
    public List<Item> items(Principal p, int limit) {
        BiService s = bi.getIfAvailable();
        if (s == null) {
            return List.of();
        }
        List<Item> out = new ArrayList<>();
        try {
            for (Map<String, Object> w : s.workspaces(p)) {
                if (Boolean.TRUE.equals(w.get("personal"))) {
                    continue;                                          // a personal workspace is never proposed from
                }
                for (Map<String, Object> i : s.items(p, String.valueOf(w.get("id")))) {
                    if ("proposed".equals(i.get("status")) && out.size() < limit) {
                        out.add(item(i, p));
                    }
                }
            }
        } catch (DrishtiException e) {
            return out;                                                // BI is off for them, or they read no workspace
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Item item(Map<String, Object> i, Principal p) {
        Map<String, Object> can = i.get("can") instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of();
        String by = ApprovalSource.text(i.get("proposedBy"));
        boolean mine = p.user().equals(by);
        String status = "proposed".equals(i.get("status")) ? PENDING : ApprovalSource.text(i.get("status"));
        return new Item(kind(), ApprovalSource.text(i.get("id")), i.get("kind") + " '" + i.get("title") + "' in " + i.get("workspaceName"), by,
                ApprovalSource.instant(i.get("proposedAt")), status, "", null, Boolean.TRUE.equals(can.get("approve")), mine,
                Boolean.TRUE.equals(can.get("withdraw")), "/bi/items/" + i.get("id"), null, null, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Detail detail(String id, Principal p) {
        BiService s = need(bi.getIfAvailable());
        Map<String, Object> view = s.item(p, id, false);
        Map<String, Object> review = need(gov.getIfAvailable()).review(p, id);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("format", "lines");
        if (review.get("diff") instanceof Map<?, ?> d) {
            diff.putAll((Map<String, Object>) d);
        }
        List<String> effect = new ArrayList<>();
        effect.add("Approving publishes it: readers of the workspace see the new version");
        if (review.get("impact") instanceof Map<?, ?> im) {
            effect.add("Published items it breaks: " + size(im.get("published")) + (Boolean.TRUE.equals(im.get("breaksPublished")) ? " (a breaking change)" : ""));
            effect.add("Items it alters: " + size(im.get("altered")) + "; drafts that read it: " + size(im.get("drafts")));
            if (im.get("wouldRevokeCertification") instanceof List<?> l && !l.isEmpty()) {
                effect.add("Certification revoked from: " + l);
            }
        }
        Map<String, Object> request = review.get("request") instanceof Map<?, ?> r ? (Map<String, Object>) r : Map.of();
        Item it = item(view, p);
        Item withNote = new Item(it.kind(), it.id(), it.title(), it.maker(), it.at(), it.status(), ApprovalSource.text(request.get("note")), it.due(),
                it.mayDecide(), it.mine(), it.mayWithdraw(), it.link(), it.decidedBy(), it.decidedAt(), it.stage());
        List<String> rules = List.of("A bi-steward, or a member of the workspace whose role has the approve power, decides",
                "With four eyes on, never the person who proposed it", "A reason is required to reject; the item goes back to draft");
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("check", review.get("check"));
        facts.put("events", review.get("events"));
        return new Detail(withNote, diff, effect, rules, facts);
    }

    private static int size(Object o) {
        return o instanceof List<?> l ? l.size() : 0;
    }

    private static <T> T need(T t) {
        if (t == null) {
            throw new DrishtiException(ErrorCode.BI_OFF, "BI is not running on this server");
        }
        return t;
    }

    @Override
    public Item decide(String id, String action, String comment, Map<String, Object> params, Principal p) {
        BiService s = need(bi.getIfAvailable());
        boolean force = Boolean.TRUE.equals(params.get("force"));
        switch (action) {
            case "approve" -> s.approve(p, id, comment, force, Boolean.TRUE.equals(params.get("applyRenames")));
            case "reject" -> s.reject(p, id, comment);
            case "withdraw" -> s.withdraw(p, id);
            default -> throw new DrishtiException(ErrorCode.BAD_REQUEST, "a BI item is approved, rejected or withdrawn");
        }
        String status = "approve".equals(action) ? "approved" : "reject".equals(action) ? "rejected" : "withdrawn";
        Map<String, Object> now;
        try {
            now = s.item(p, id, false);
        } catch (DrishtiException e) {                                    // decided by someone outside the item's audience: done, though they may not read it now
            return new Item(kind(), id, id, "", null, status, "", null, false, false, false, "/bi/items/" + id, p.user(), java.time.Instant.now(), null);
        }
        Item it = item(now, p);
        return new Item(it.kind(), it.id(), it.title(), it.maker(), it.at(), status, it.reason(), null, false, it.mine(), false, it.link(), p.user(),
                java.time.Instant.now(), null);
    }
}
