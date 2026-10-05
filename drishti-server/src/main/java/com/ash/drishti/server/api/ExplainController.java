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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.explain.ExplainService;
import com.ash.drishti.engine.explain.PageContext;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * About this page: the explanation of a view for the caller (docs/architecture/CONTEXT_HELP.md). Exactly the view's rules:
 * the same right to open the kind ({@code DRS-5002}), the same business date, and the explanation is derived from the view
 * rebuilt for the caller, so a field masked for them is never read and a panel they may not open is named only as the view
 * names it.
 */
@RestController
@RequestMapping("/api/v1/views")
public class ExplainController {

    private final ExplainService explain;
    private final Entitlements entitlements;

    public ExplainController(ExplainService explain, Entitlements entitlements) {
        this.explain = explain;
        this.entitlements = entitlements;
    }

    /**
     * @param panel narrows the answer to one panel ({@code DRS-4006} when the view has none of that id)
     * @param generation the generation of the page the caller shows; the answer says when the server holds a newer one
     */
    @GetMapping("/{kind}/{id}/explain")
    public PageContext explain(@PathVariable String kind, @PathVariable String id, AsOf asOf,
            @RequestParam(required = false) String panel, @RequestParam(required = false) Long generation,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        entitlements.requireOpen(principal, kind);
        ExplainService.Caller caller = new ExplainService.Caller(principal.user(), entitlements.redactor(principal),
                k -> entitlements.mayOpen(principal, k), v -> entitlements.restrict(principal, v));
        return explain.explain(EntityRef.of(kind, id), asOf, caller, panel, generation);
    }
}
