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
package com.ash.drishti.server.admission;

import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.kernel.api.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin, Health's "Busy protection" card (docs/guides/OPERATIONS_GUIDE.md, "Busy protection"): {@code GET /api/v1/admin/busy} the state, the
 * readings against their levels and the last refusals; {@code PUT /api/v1/admin/busy/override} an operator's override with a reason that
 * ends by itself (audited; never a token).
 */
@RestController
public class AdmissionController {

    /** The override asked: {@code automatic}, {@code open} (let everything through) or {@code hold} (hold all new heavy work). */
    public record OverrideRequest(String mode, String reason, Integer minutes) {}

    private final AdmissionGate gate;
    private final Entitlements entitlements;

    public AdmissionController(AdmissionGate gate, Entitlements entitlements) {
        this.gate = gate;
        this.entitlements = entitlements;
    }

    @GetMapping("/api/v1/admin/busy")
    public Map<String, Object> busy(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return gate.describe();
    }

    @PutMapping("/api/v1/admin/busy/override")
    public Map<String, Object> override(@RequestBody OverrideRequest req, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        gate.override(AdmissionGate.Mode.of(req.mode()), req.reason(), req.minutes() == null ? 0 : req.minutes(), p.user());
        return gate.describe();
    }
}
