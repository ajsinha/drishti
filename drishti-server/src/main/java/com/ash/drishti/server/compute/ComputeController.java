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

import com.ash.drishti.common.Branding;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.ComputeException;
import com.ash.drishti.compute.ComputeRequest;
import com.ash.drishti.compute.ComputeResult;
import com.ash.drishti.compute.ComputeService;
import com.ash.drishti.compute.approval.ApprovalRequest;
import com.ash.drishti.compute.arrow.ArrowTable;
import com.ash.drishti.compute.env.Environments;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.net.NetCaller;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compute workers' REST face (docs/architecture/PYTHON_COMPUTE.md section 15). Administrators: {@code GET /api/v1/admin/compute} (Admin →
 * Compute: the switch, pools, workers, queues, environments, approval requests), approve, reject and revoke (four eyes: never the person
 * who asked; a reason always), build a pool's approved environment, test a pool with the sample engine, cancel a running request. Other
 * Drishti*net members: a pool's load and a fan-out chunk run here on behalf of its viewer (one hop: forwarded calls only).
 */
@RestController
@RequestMapping("/api/v1")
public class ComputeController {

    /** A decision's body: why. */
    public record Decision(String reason) {}

    private final ComputeService compute;
    private final Entitlements entitlements;
    private final AuditLog audit;

    public ComputeController(ComputeService compute, Entitlements entitlements, AuditLog audit) {
        this.compute = compute;
        this.entitlements = entitlements;
        this.audit = audit;
    }

    /** Administrators, and when {@code compute.approval.roles} names roles, only administrators holding one of them, may decide. */
    private void requireApprover(Principal p) {
        entitlements.requireAdmin(p);
        List<String> roles = compute.settings().approvalRoles();
        if (!roles.isEmpty() && p.roles().stream().noneMatch(roles::contains) && !p.roles().contains("*")) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, p.user() + " may not approve compute requirements (compute.approval.roles: " + roles + ")");
        }
    }

    static DrishtiException wrap(ComputeException e) {
        ErrorCode code = switch (e.code()) {
            case OFF -> ErrorCode.COMPUTE_OFF;
            case REFUSED -> ErrorCode.COMPUTE_REFUSED;
            case BUSY -> ErrorCode.COMPUTE_BUSY;
            case TIMEOUT -> ErrorCode.COMPUTE_TIMEOUT;
            default -> ErrorCode.COMPUTE_FAILED;
        };
        return new DrishtiException(code, e.getMessage() + (e.requestId() == null || e.getMessage().contains(e.requestId()) ? "" : " (request " + e.requestId() + ")"));
    }

    @GetMapping("/admin/compute")
    public Map<String, Object> overview(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Map<String, Object> m = compute.overview();
        m.put("approvalRoles", compute.settings().approvalRoles());
        m.put("me", p.user());
        return m;
    }

    @PostMapping("/admin/compute/requests/{id}/approve")
    public ApprovalRequest approve(@PathVariable String id, @RequestBody Decision body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        requireApprover(p);
        try {
            return compute.approvals().approve(id, p.user(), body == null ? null : body.reason());
        } catch (ComputeException e) {
            throw notFoundOr(e);
        }
    }

    @PostMapping("/admin/compute/requests/{id}/reject")
    public ApprovalRequest reject(@PathVariable String id, @RequestBody Decision body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        requireApprover(p);
        try {
            return compute.approvals().reject(id, p.user(), body == null ? null : body.reason());
        } catch (ComputeException e) {
            throw notFoundOr(e);
        }
    }

    @PostMapping("/admin/compute/requests/{id}/revoke")
    public ApprovalRequest revoke(@PathVariable String id, @RequestBody Decision body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        requireApprover(p);
        try {
            return compute.approvals().revoke(id, p.user(), body == null ? null : body.reason());
        } catch (ComputeException e) {
            throw notFoundOr(e);
        }
    }

    private static DrishtiException notFoundOr(ComputeException e) {
        return e.getMessage().startsWith("no approval request") ? new DrishtiException(ErrorCode.COMPUTE_NOT_FOUND, e.getMessage()) : wrap(e);
    }

    @PostMapping("/admin/compute/pools/{pool}/build")
    public Map<String, Object> build(@PathVariable String pool, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        try {
            Environments.Built b = compute.build(pool, p.user());
            audit.record(p.user(), "compute-build", "pool:" + pool, b.id() + (b.reused() ? " (already built)" : " built"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.id());
            m.put("dir", b.dir().toString());
            m.put("reused", b.reused());
            m.put("log", b.log().size() > 200 ? b.log().subList(b.log().size() - 200, b.log().size()) : b.log());
            return m;
        } catch (ComputeException e) {
            audit.record(p.user(), "compute-build-failed", "pool:" + pool, e.getMessage());
            throw wrap(e);
        }
    }

    /** Runs the sample engine's {@code echo} on the pool as the administrator: a quick end-to-end check of the workers. */
    @PostMapping("/admin/compute/pools/{pool}/test")
    public Map<String, Object> test(@PathVariable String pool, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        String sample = "drishti_compute.samples.vectors:VectorEngine";
        try {
            if (!compute.settings().pool(pool).engines().contains(sample)) {
                throw new ComputeException(ComputeException.Code.REFUSED, "pool " + pool + " does not load the sample engine (" + sample + ")");
            }
            long t0 = System.nanoTime();
            ComputeResult r = compute.run(ComputeRequest.on(pool, "sum", p.user()).engine(sample).timeout(Duration.ofSeconds(60))
                    .input("values", ArrowTable.builder().doubles("X", 1, 2, 3, 4).build()).build());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("worker", r.worker());
            m.put("rows", r.rows());
            m.put("queuedMs", r.queuedMs());
            m.put("runMs", r.runMs());
            m.put("totalMs", (System.nanoTime() - t0) / 1_000_000);
            return m;
        } catch (ComputeException e) {
            throw wrap(e);
        }
    }

    @PostMapping("/admin/compute/running/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        if (!compute.cancel(id)) {
            throw new DrishtiException(ErrorCode.COMPUTE_NOT_FOUND, "no computation " + id + " is waiting or running on this node");
        }
        audit.record(p.user(), "compute-cancel", id, "");
        return Map.of("cancelled", id);
    }

    // ------------------------------------------------------------------------------------------------ Drishti*net members only

    private static void requirePeer() {
        if (!NetCaller.fromPeer()) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "only another " + Branding.netName() + " member may call this");
        }
    }

    @GetMapping("/compute/peer/load")
    public Map<String, Object> load(@RequestParam String pool) {
        requirePeer();
        var l = compute.load(pool);
        return Map.of("node", l.node(), "workers", l.workers(), "busy", l.busy(), "queued", l.queued());
    }

    /** A chunk of another node's fan-out, run on this node's workers only (one hop), as the viewer the on-behalf-of token names. */
    @PostMapping("/compute/peer/run")
    public Map<String, Object> run(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        requirePeer();
        try {
            ComputeRequest r = ComputeWire.request(body);
            if (!r.viewer().user().equals(p.user())) {
                throw new ComputeException(ComputeException.Code.REFUSED, "the chunk's viewer is not the user the call is made for");
            }
            java.time.Duration budget = r.timeout() != null ? r.timeout() : compute.settings().timeout(compute.settings().pool(r.pool()), r.cls());
            return ComputeWire.result(compute.submit(r, null).await(budget.plusSeconds(10)));
        } catch (ComputeException e) {
            return ComputeWire.error(e);
        }
    }

    @PostMapping("/compute/peer/cancel/{id}")
    public Map<String, Object> peerCancel(@PathVariable String id) {
        requirePeer();
        return Map.of("cancelled", compute.cancel(id));
    }
}
