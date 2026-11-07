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
package com.ash.drishti.server.governance;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.admin.ArtifactImpact;
import com.ash.drishti.server.approvals.ApprovalSource;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Sutra proposals in the Approvals queue: a Sutra saved from the workbench, a designed screen or a terminal layout promoted to the next
 * version waits here for an approver. The rules are {@link SutraGovernance}'s (the approve power, four eyes, a proposal on a version that
 * changed since is refused).
 */
@Component
public class SutraApprovals implements ApprovalSource {

    private final SutraGovernance governance;
    private final ObjectProvider<ArtifactImpact> impact;

    public SutraApprovals(SutraGovernance governance, ObjectProvider<ArtifactImpact> impact) {
        this.governance = governance;
        this.impact = impact;
    }

    @Override
    public String kind() {
        return "sutra";
    }

    @Override
    public String group() {
        return "screens";
    }

    @Override
    public String label() {
        return "Sutra";
    }

    @Override
    public boolean enabled() {
        return governance.enabled();
    }

    @Override
    public List<Item> items(Principal p, int limit) {
        List<Item> out = new ArrayList<>();
        try {
            for (Proposal x : governance.list(null, null, p)) {
                if (out.size() >= limit) {
                    break;
                }
                out.add(item(x, p));
            }
        } catch (DrishtiException e) {
            return List.of();                                    // no reader right: nothing of this kind for them
        }
        return out;
    }

    private Item item(Proposal x, Principal p) {
        boolean mine = SutraGovernance.same(x.author(), p.user());
        String title = x.name() + " v" + x.version() + (x.baseText() == null || x.baseText().isEmpty() ? " (new version)" : " (change)");
        return new Item(kind(), x.id(), title, x.author(), x.createdAt(), x.status(), x.note(), null, governance.mayApprove(x, p), mine,
                x.pending() && mine, "/build/design", x.reviewer(), x.reviewedAt(), governance.stale(x) ? "stale" : null);
    }

    @Override
    public Detail detail(String id, Principal p) {
        Proposal x = governance.get(id, p);
        String before = governance.liveText(x).isEmpty() ? governance.previousText(x) : x.baseText();
        List<String> effect = new ArrayList<>();
        effect.add(x.baseText() == null || x.baseText().isEmpty() ? "Version " + x.version() + " of " + x.name() + " becomes live: views of it use it"
                : "The live version " + x.version() + " of " + x.name() + " is replaced: every view drawn from it changes at once");
        ArtifactImpact ai = impact.getIfAvailable();
        if (ai != null) {
            try {
                ArtifactImpact.Impact im = ai.ofKind(x.name(), x.name());
                im.groups().forEach(g -> effect.add(g.label() + " that read " + x.name() + ": " + g.count()));
            } catch (RuntimeException e) {
                effect.add("What reads it could not be counted: " + e.getMessage());
            }
        }
        List<String> rules = List.of("An approver (the approve power) decides; with four eyes on, never the person who proposed it",
                "A proposal on a version that changed since it was made cannot be approved: reject it and propose again from the live version",
                "A reason is required to reject");
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("version", x.version());
        facts.put("stale", governance.stale(x));
        facts.put("evidence", governance.evidenceFor(x, p));
        if (x.comment() != null && !x.pending()) {
            facts.put("decision", x.comment());
        }
        return new Detail(item(x, p), ApprovalSource.textDiff(before, x.text()), effect, rules, facts);
    }

    @Override
    public Item decide(String id, String action, String comment, Map<String, Object> params, Principal p) {
        Proposal done = switch (action) {
            case "approve" -> governance.approve(id, comment, p);
            case "reject" -> governance.reject(id, comment, p);
            case "withdraw" -> governance.withdraw(id, p);
            default -> throw new DrishtiException(ErrorCode.BAD_REQUEST, "a Sutra proposal is approved, rejected or withdrawn");
        };
        return item(done, p);
    }
}
