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
import com.ash.drishti.server.explain.AskService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/views/{kind}/{id}/ask}: a question about the page the caller is looking at (docs/architecture/CONTEXT_HELP.md,
 * Optional: Ask). The same right to open the kind as the view ({@code DRS-5002}); {@code DRS-4007} when Ask is off, {@code DRS-4008}
 * when the model endpoint fails or is slow, {@code DRS-4009} over the rate. The answer is plain text.
 */
@RestController
@RequestMapping("/api/v1/views")
public class AskController {

    /** The request body. */
    public record Question(String question, String locale) {}

    private final AskService ask;
    private final Entitlements entitlements;

    public AskController(AskService ask, Entitlements entitlements) {
        this.ask = ask;
        this.entitlements = entitlements;
    }

    @PostMapping("/{kind}/{id}/ask")
    public AskService.Answer ask(@PathVariable String kind, @PathVariable String id, AsOf asOf, @RequestBody Question body,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        entitlements.requireOpen(principal, kind);
        ExplainService.Caller caller = new ExplainService.Caller(principal.user(), entitlements.redactor(principal),
                k -> entitlements.mayOpen(principal, k), v -> entitlements.restrict(principal, v));
        List<String> languages = ExplainController.languages(body.locale(), acceptLanguage);
        return ask.ask(EntityRef.of(kind, id), asOf, caller, body.question(), languages);
    }
}
