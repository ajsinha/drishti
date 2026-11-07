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
package com.ash.drishti.server.compute;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.ComputeException;
import com.ash.drishti.compute.ComputeService;
import com.ash.drishti.compute.approval.ApprovalItem;
import com.ash.drishti.compute.approval.ApprovalRequest;
import com.ash.drishti.server.approvals.ApprovalSource;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Compute requirements (the Python packages, Java libraries and plug-ins a connector or a pool needs, COMPUTE.md) in the Approvals queue.
 * Administrators decide, and when {@code compute.approval.roles} names roles only administrators holding one of them; never the person who
 * asked (four eyes); a reason is always given. Revoking an approval stays on Admin → Compute.
 */
@Component
public class ComputeApprovals implements ApprovalSource {

    private final ObjectProvider<ComputeService> compute;
    private final Entitlements entitlements;

    public ComputeApprovals(ObjectProvider<ComputeService> compute, Entitlements entitlements) {
        this.compute = compute;
        this.entitlements = entitlements;
    }

    @Override
    public String kind() {
        return "compute";
    }

    @Override
    public String group() {
        return "access";
    }

    @Override
    public String label() {
        return "Compute package";
    }

    @Override
    public boolean enabled() {
        return compute.getIfAvailable() != null;
    }

    /** Administrators, and when {@code compute.approval.roles} names roles, only administrators holding one of them. */
    boolean approver(Principal p) {
        ComputeService c = compute.getIfAvailable();
        if (c == null || !entitlements.isAdmin(p)) {
            return false;
        }
        List<String> roles = c.settings().approvalRoles();
        return roles.isEmpty() || p.roles().contains("*") || p.roles().stream().anyMatch(roles::contains);
    }

    @Override
    public List<Item> items(Principal p, int limit) {
        ComputeService c = compute.getIfAvailable();
        if (c == null) {
            return List.of();
        }
        boolean approver = approver(p);
        List<Item> out = new ArrayList<>();
        for (ApprovalRequest r : c.approvals().recent(Math.max(limit, 50))) {
            if ((approver || p.user().equalsIgnoreCase(r.requestedBy())) && !ApprovalRequest.SUPERSEDED.equals(r.state()) && out.size() < limit) {
                out.add(item(r, p, approver));
            }
        }
        return out;
    }

    private Item item(ApprovalRequest r, Principal p, boolean approver) {
        boolean mine = p.user().equalsIgnoreCase(r.requestedBy());
        return new Item(kind(), r.id(), r.subject() + ": " + r.items().size() + " package(s)", r.requestedBy(), r.requestedAt(), r.state(), "", null,
                approver && !mine, mine, false, "/admin/compute", r.decidedBy(), r.decidedAt(), null);
    }

    @Override
    public Detail detail(String id, Principal p) {
        ApprovalRequest r = request(id, p);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ApprovalItem i : r.items()) {
            rows.add(ApprovalSource.row(i.kind() + " " + i.name(), null, (i.version().isEmpty() ? "" : i.version() + " ") + "from " + i.source()
                    + (i.nativeCode() ? " (native code)" : "") + (i.hash().isEmpty() ? "" : " sha256 " + i.hash())));
        }
        List<String> effect = List.of("The " + r.items().size() + " package(s) become approved for " + r.subject()
                + ": its environment can be built and its computations run", "Every other subject that asks for the same packages finds them approved");
        List<String> rules = List.of("An administrator decides (and, when compute.approval.roles names roles, one holding one of them)",
                "Never the person who asked (four eyes)", "A reason is required to approve or reject");
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("subject", r.subject());
        facts.put("fingerprint", r.fingerprint());
        if (r.reason() != null) {
            facts.put("decision", r.reason());
        }
        return new Detail(item(r, p, approver(p)), ApprovalSource.fieldDiff(rows), effect, rules, facts);
    }

    private ApprovalRequest request(String id, Principal p) {
        ComputeService c = compute.getIfAvailable();
        ApprovalRequest r = c == null ? null : c.approvals().get(id).orElse(null);
        if (r == null || !approver(p) && !p.user().equalsIgnoreCase(r.requestedBy())) {
            throw new DrishtiException(ErrorCode.COMPUTE_NOT_FOUND, "no compute request " + id);
        }
        return r;
    }

    @Override
    public Item decide(String id, String action, String comment, Map<String, Object> params, Principal p) {
        request(id, p);
        if (!approver(p)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, p.user() + " may not approve compute requirements (an administrator"
                    + " holding a role of compute.approval.roles, when it names any)");
        }
        ComputeService c = compute.getObject();
        try {
            ApprovalRequest done = switch (action) {
                case "approve" -> c.approvals().approve(id, p.user(), comment);
                case "reject" -> c.approvals().reject(id, p.user(), comment);
                default -> throw new DrishtiException(ErrorCode.BAD_REQUEST, "a compute request is approved or rejected");
            };
            return item(done, p, true);
        } catch (ComputeException e) {
            throw e.getMessage().startsWith("no approval request") ? new DrishtiException(ErrorCode.COMPUTE_NOT_FOUND, e.getMessage())
                    : ComputeController.wrap(e);
        }
    }
}
