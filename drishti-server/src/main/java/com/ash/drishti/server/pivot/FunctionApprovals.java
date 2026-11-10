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

import com.ash.drishti.bicompute.custom.CustomFunctions;
import com.ash.drishti.bicompute.custom.FunctionDef;
import com.ash.drishti.kernel.api.pivot.Viewer;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.approvals.ApprovalSource;
import com.ash.drishti.kernel.api.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Pivot custom functions submitted for sharing (PIVOT_GUIDE.md) in the Approvals queue. A version stays its author's until an approver who
 * is not its author approves it for {@code everyone} or {@code group:<name>} (the decision's {@code scope}); a Java plug-in is approved by
 * an administrator; a version without a passed harness report cannot be approved. Withdrawing an approved function stays on the pivot's
 * Functions panel.
 */
@Component
public class FunctionApprovals implements ApprovalSource {

    private final ObjectProvider<PivotController> pivot;
    private final ObjectProvider<CustomFunctions> functions;

    public FunctionApprovals(ObjectProvider<PivotController> pivot, ObjectProvider<CustomFunctions> functions) {
        this.pivot = pivot;
        this.functions = functions;
    }

    @Override
    public String kind() {
        return "function";
    }

    @Override
    public String group() {
        return "data";
    }

    @Override
    public String label() {
        return "Pivot function";
    }

    @Override
    public boolean enabled() {
        return pivot.getIfAvailable() != null && functions.getIfAvailable() != null;
    }

    @Override
    public List<Item> items(Principal p, int limit) {
        if (!enabled()) {
            return List.of();
        }
        try {
            Viewer v = pivot.getObject().on(p);
            CustomFunctions f = functions.getObject();
            List<Item> out = new ArrayList<>();
            for (FunctionDef d : f.reviewable(v)) {
                if (out.size() < limit) {
                    out.add(item(d, v, f, p));
                }
            }
            return out;
        } catch (DrishtiException e) {
            return List.of();                                          // the pivot engine is off for them
        }
    }

    private Item item(FunctionDef d, Viewer v, CustomFunctions f, Principal p) {
        boolean mine = d.owner().equals(p.user());
        String status = "submitted".equals(d.status()) ? PENDING : d.status();
        Map<String, Object> last = d.approvals().isEmpty() ? Map.of() : d.approvals().get(d.approvals().size() - 1);
        return new Item(kind(), d.owner() + ":" + d.name() + "@" + d.version(), d.ref() + " (" + d.language().word() + ")", d.owner(),
                ApprovalSource.instant(d.createdAt()), status, d.description(), null, f.mayApprove(v, d), mine, false, "/pivot?functions=1",
                last.isEmpty() ? null : ApprovalSource.text(last.get("by")), ApprovalSource.instant(last.get("at")),
                last.isEmpty() ? null : "for " + last.get("scope"));
    }

    private FunctionDef def(CustomFunctions f, Viewer v, String id) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([^:]+):([^@]+)@(\\d+)").matcher(id);
        if (!m.matches()) {
            throw new DrishtiException(ErrorCode.PROPOSAL_NOT_FOUND, "a pivot function is named owner:name@version, not " + id);
        }
        // the version as the caller may see it: their own, one approved for them, or (an approver) any version, submitted or not
        return f.get(v, m.group(1), m.group(2), Integer.parseInt(m.group(3)));
    }

    @Override
    public Detail detail(String id, Principal p) {
        Viewer v = pivot.getObject().on(p);
        CustomFunctions f = functions.getObject();
        FunctionDef d = def(f, v, id);
        String before = f.reviewable(v).stream().filter(x -> x.owner().equals(d.owner()) && x.name().equals(d.name()) && x.version() < d.version())
                .reduce((a, b) -> b).map(FunctionDef::code).orElse("");
        List<String> effect = List.of("Once approved, " + d.ref() + " can be used in pivot measures by everyone the scope names (everyone, or group:<name>)",
                "Views that call it compute with this version; the author's earlier approved versions keep working");
        List<String> rules = List.of("An approver (an administrator or a delegated approver) decides; a Java plug-in, an administrator",
                "Never its author", "A passed harness report is required; a reason is required; the scope says who may use it");
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("language", d.language().word());
        facts.put("harness", d.harness());
        facts.put("approvals", d.approvals());
        return new Detail(item(d, v, f, p), ApprovalSource.textDiff(before, d.code()), effect, rules, facts);
    }

    @Override
    public Item decide(String id, String action, String comment, Map<String, Object> params, Principal p) {
        Viewer v = pivot.getObject().on(p);
        CustomFunctions f = functions.getObject();
        FunctionDef d = def(f, v, id);
        FunctionDef done = switch (action) {
            case "approve" -> f.approve(v, d.owner(), d.name(), d.version(), params.get("scope") == null ? null : params.get("scope").toString(), comment);
            case "reject" -> {
                if (comment == null || comment.isBlank()) {
                    throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_APPROVAL, "say why it is rejected");
                }
                yield f.revoke(v, d.owner(), d.name(), d.version(), comment);
            }
            default -> throw new DrishtiException(ErrorCode.BAD_REQUEST, "a pivot function is approved or rejected");
        };
        return item(done, v, f, p);
    }
}
