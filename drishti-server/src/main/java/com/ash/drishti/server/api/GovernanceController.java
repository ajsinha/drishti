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
package com.ash.drishti.server.api;

import com.ash.drishti.server.governance.Proposal;
import com.ash.drishti.server.governance.SutraGovernance;
import com.ash.drishti.server.security.Principal;
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

/** Sutra governance (W19): proposals, review decisions and each Sutra's history. */
@RestController
@RequestMapping("/api/v1/sutras")
public class GovernanceController {

    /** @param comment the reviewer's comment (required to reject) */
    public record Decision(String comment) {}

    private final SutraGovernance governance;

    public GovernanceController(SutraGovernance governance) {
        this.governance = governance;
    }

    @GetMapping("/proposals")
    public Map<String, Object> proposals(@RequestParam(required = false) String status, @RequestParam(required = false) String name,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        List<Proposal> list = governance.list(status, name, p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", governance.enabled());
        out.put("proposals", list.stream().map(x -> summary(x, p)).toList());
        return out;
    }

    @GetMapping("/proposals/{id}")
    public Map<String, Object> proposal(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Proposal pr = governance.get(id, p);
        Map<String, Object> out = summary(pr, p);
        out.put("text", pr.text());
        out.put("baseText", pr.baseText());
        out.put("liveText", governance.liveText(pr));
        out.put("previousText", governance.previousText(pr));
        return out;
    }

    @PostMapping("/proposals/{id}/approve")
    public Map<String, Object> approve(@PathVariable String id, @RequestBody(required = false) Decision d, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return summary(governance.approve(id, d == null ? null : d.comment(), p), p);
    }

    @PostMapping("/proposals/{id}/reject")
    public Map<String, Object> reject(@PathVariable String id, @RequestBody(required = false) Decision d, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return summary(governance.reject(id, d == null ? null : d.comment(), p), p);
    }

    @PostMapping("/proposals/{id}/withdraw")
    public Map<String, Object> withdraw(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return summary(governance.withdraw(id, p), p);
    }

    /** Every decided proposal for a Sutra, newest first: who proposed and who approved each version. */
    @GetMapping("/{name}/history")
    public List<Map<String, Object>> history(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return governance.list(null, name, p).stream().map(x -> summary(x, p)).toList();
    }

    private Map<String, Object> summary(Proposal x, Principal p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", x.id());
        m.put("name", x.name());
        m.put("version", x.version());
        m.put("note", x.note());
        m.put("author", x.author());
        m.put("createdAt", x.createdAt());
        m.put("status", x.status());
        m.put("reviewer", x.reviewer());
        m.put("reviewedAt", x.reviewedAt());
        m.put("comment", x.comment());
        m.put("newVersion", x.baseText() == null || x.baseText().isEmpty());
        m.put("stale", governance.stale(x));
        m.put("mayApprove", governance.mayApprove(x, p));
        m.put("mayWithdraw", x.pending() && x.author().equals(p.user()));
        return m;
    }
}
