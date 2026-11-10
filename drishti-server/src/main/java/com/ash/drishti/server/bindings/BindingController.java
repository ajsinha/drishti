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
package com.ash.drishti.server.bindings;

import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.server.security.Entitlements;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin → Data bindings (docs/guides/ADMIN_GUIDE.md, "Data bindings"), administrators only: every kind with its state and source, the
 * functions an unserved need switched off, the deprecated {@code kinds:} lists, and the four-eyes proposals (propose, approve, reject,
 * withdraw). {@code POST /reload} applies an edited {@code bindings.yaml} at once.
 */
@RestController
@RequestMapping("/api/v1/admin/bindings")
public class BindingController {

    /** A proposal: the kind, its new binding ({@code null} removes it) and why. */
    public record ProposeIn(String kind, Map<String, Object> binding, String reason) {}

    /** A decision's comment (optional). */
    public record DecideIn(String comment) {}

    private final BindingService service;
    private final Entitlements entitlements;

    public BindingController(BindingService service, Entitlements entitlements) {
        this.service = service;
        this.entitlements = entitlements;
    }

    @GetMapping
    public Map<String, Object> status(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return service.status();
    }

    @PostMapping("/reload")
    public Map<String, Object> reload(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        service.refresh();
        return service.status();
    }

    @PostMapping("/proposals")
    public BindingService.Proposal propose(@RequestBody ProposeIn in, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return service.propose(in.kind(), in.binding(), in.reason(), p.user());
    }

    @PostMapping("/proposals/{id}/approve")
    public BindingService.Proposal approve(@PathVariable String id, @RequestBody(required = false) DecideIn in,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return service.decide(id, true, in == null ? "" : in.comment(), p.user());
    }

    @PostMapping("/proposals/{id}/reject")
    public BindingService.Proposal reject(@PathVariable String id, @RequestBody(required = false) DecideIn in,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return service.decide(id, false, in == null ? "" : in.comment(), p.user());
    }

    @PostMapping("/proposals/{id}/withdraw")
    public BindingService.Proposal withdraw(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return service.withdraw(id, p.user());
    }
}
