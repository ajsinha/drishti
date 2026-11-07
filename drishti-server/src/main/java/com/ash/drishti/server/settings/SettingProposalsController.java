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
package com.ash.drishti.server.settings;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin → Configuration's proposals (docs/admin/OPERATIONS.md, "The configuration editor"), administrators only: list, propose, approve,
 * reject, withdraw, and a console's word that it wrote an approved console key ({@link SettingProposals}).
 */
@RestController
@RequestMapping("/api/v1/admin/settings/proposals")
public class SettingProposalsController {

    /** A proposal as the page sends it. */
    public record ProposeIn(String key, String area, String value, String was, String reason) {}

    /** A decision: its comment (optional). */
    public record DecideIn(String comment) {}

    private final SettingProposals proposals;
    private final Entitlements entitlements;

    public SettingProposalsController(Entitlements entitlements, AuditLog audit, @Value("${drishti.data.dir:./data}") String dataDir) {
        this.entitlements = entitlements;
        this.proposals = new SettingProposals(Path.of(dataDir, "settings", "proposals.json"), Path.of("application.local.yaml"), audit,
                Clock.systemUTC());
    }

    @GetMapping
    public Map<String, Object> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return SettingProposals.answer(proposals.list());
    }

    @PostMapping
    public SettingProposals.Proposal propose(@RequestBody ProposeIn in, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return proposals.propose(in.key(), in.area(), in.value(), in.was(), in.reason(), p.user());
    }

    @PostMapping("/{id}/approve")
    public SettingProposals.Proposal approve(@PathVariable String id, @RequestBody(required = false) DecideIn in,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return proposals.decide(id, true, in == null ? "" : in.comment(), p.user());
    }

    @PostMapping("/{id}/reject")
    public SettingProposals.Proposal reject(@PathVariable String id, @RequestBody(required = false) DecideIn in,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return proposals.decide(id, false, in == null ? "" : in.comment(), p.user());
    }

    @PostMapping("/{id}/withdraw")
    public SettingProposals.Proposal withdraw(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return proposals.withdraw(id, p.user());
    }

    @PostMapping("/{id}/applied")
    public SettingProposals.Proposal applied(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return proposals.markApplied(id, p.user());
    }
}
