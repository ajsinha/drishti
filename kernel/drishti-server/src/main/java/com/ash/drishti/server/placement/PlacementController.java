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
package com.ash.drishti.server.placement;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.server.net.NetCallFilter;
import com.ash.drishti.server.security.Entitlements;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Module placement over HTTP (docs/guides/API_REFERENCE.md, CLUSTER_GUIDE.md "Module placement"): the node × part matrix and the plans for
 * administrators (Admin → Cluster → Modules), and the placement a member sends or asks for ({@code /api/v1/net/placement}, signed, nodes
 * only).
 */
@RestController
public class PlacementController {

    /** A plan: the whole placement file's text and why. */
    public record PlanIn(String placement, String reason) {}

    /** A decision's comment (optional). */
    public record DecideIn(String comment) {}

    /** A member's placement. */
    public record PeerIn(String text) {}

    private final PlacementService service;
    private final Entitlements entitlements;

    public PlacementController(PlacementService service, Entitlements entitlements) {
        this.service = service;
        this.entitlements = entitlements;
    }

    @GetMapping("/api/v1/admin/cluster/modules")
    public Map<String, Object> matrix(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return service.status();
    }

    @PostMapping("/api/v1/admin/cluster/placement/reload")
    public Map<String, Object> reload(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        service.refresh();
        return service.status();
    }

    @PostMapping("/api/v1/admin/cluster/placement/plans")
    public PlacementService.Plan plan(@RequestBody PlanIn in, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return service.plan(in.placement(), in.reason(), p.user());
    }

    @PostMapping("/api/v1/admin/cluster/placement/plans/{id}/apply")
    public PlacementService.Plan apply(@PathVariable String id, @RequestBody(required = false) DecideIn in,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return service.apply(id, in == null ? "" : in.comment(), p.user());
    }

    @PostMapping("/api/v1/admin/cluster/placement/plans/{id}/reject")
    public PlacementService.Plan reject(@PathVariable String id, @RequestBody(required = false) DecideIn in,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return service.close(id, false, in == null ? "" : in.comment(), p.user());
    }

    @PostMapping("/api/v1/admin/cluster/placement/plans/{id}/withdraw")
    public PlacementService.Plan withdraw(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return service.close(id, true, "", p.user());
    }

    /** A member asks for this node's placement (it heard of a higher version in the digest). */
    @GetMapping("/api/v1/net/placement")
    public Map<String, Object> peerGet(@RequestAttribute(value = NetCallFilter.PEER, required = false) String peer) {
        requirePeer(peer);
        return Map.of("version", service.current().version(), "text", service.current().toYaml());
    }

    /** A member sends an applied placement: adopted when its version is higher. */
    @PostMapping("/api/v1/net/placement")
    public Map<String, Object> peerPost(@RequestBody PeerIn in, @RequestAttribute(value = NetCallFilter.PEER, required = false) String peer) {
        requirePeer(peer);
        return Map.of("adopted", service.adopt(in.text(), peer), "version", service.current().version());
    }

    private static void requirePeer(String peer) {
        if (peer == null) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "this endpoint is for the cluster's nodes only");
        }
    }
}
