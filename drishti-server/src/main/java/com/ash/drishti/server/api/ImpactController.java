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

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.impact.ImpactService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** F8 Impact: what depends on an entity, grouped by kind and level, within what the caller may open. */
@RestController
@RequestMapping("/api/v1/impact")
public class ImpactController {

    private final ImpactService impact;
    private final Entitlements entitlements;

    public ImpactController(ImpactService impact, Entitlements entitlements) {
        this.impact = impact;
        this.entitlements = entitlements;
    }

    @GetMapping("/{kind}/{id}")
    public ImpactService.Impact analyse(@PathVariable String kind, @PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, kind);
        return impact.analyse(EntityRef.of(kind, id), k -> entitlements.mayOpen(p, k));
    }
}
