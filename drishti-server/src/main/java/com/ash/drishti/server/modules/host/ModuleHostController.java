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
package com.ash.drishti.server.modules.host;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.module.runtime.ModuleRuntime;
import com.ash.drishti.server.security.Entitlements;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The {@code .dmod} modules of this server for an administrator (docs/guides/ADMIN_GUIDE.md, Admin → Modules): what loaded and what each
 * contributed; unload one (off, or removed with its tombstone); look in the module folders again (a new install, or a removed one put back).
 */
@RestController
public class ModuleHostController {

    /** An unload: removed (410 and a tombstone) or only off (404), and why. */
    public record Unload(Boolean remove, String reason) {
    }

    private final KernelModuleHost host;
    private final Entitlements entitlements;
    private final AuditLog audit;

    public ModuleHostController(KernelModuleHost host, Entitlements entitlements, AuditLog audit) {
        this.host = host;
        this.entitlements = entitlements;
        this.audit = audit;
    }

    @GetMapping("/api/v1/admin/modules/host")
    public Map<String, Object> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return host.describe();
    }

    @PostMapping("/api/v1/admin/modules/host/{id}/unload")
    public Map<String, Object> unload(@PathVariable("id") String id, @RequestBody Unload u, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        String reason = u == null || u.reason() == null ? "" : u.reason().strip();
        if (reason.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "an unload needs a reason");
        }
        boolean remove = u.remove() != null && u.remove();
        ModuleRuntime.UnloadReport r;
        try {
            r = host.unload(id, remove);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        audit.record(p.user(), "module-unload", "module/" + id, (remove ? "removed" : "off") + " | " + reason);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("module", id);
        out.put("steps", r.steps());
        out.put("failures", r.failures());
        out.put("leakFree", r.leakFree());
        return out;
    }

    @PostMapping("/api/v1/admin/modules/host/rescan")
    public Map<String, Object> rescan(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        host.rescan();
        audit.record(p.user(), "module-rescan", "modules", "");
        return host.describe();
    }
}
