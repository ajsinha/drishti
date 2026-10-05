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
import java.util.List;
import org.springframework.web.bind.annotation.RequestHeader;
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
            @RequestParam(required = false) String locale, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        entitlements.requireOpen(principal, kind);
        ExplainService.Caller caller = new ExplainService.Caller(principal.user(), entitlements.redactor(principal),
                k -> entitlements.mayOpen(principal, k), v -> entitlements.restrict(principal, v));
        return explain.explain(EntityRef.of(kind, id), asOf, caller, panel, generation, languages(locale, acceptLanguage));
    }

    /** The caller's languages, best first: {@code ?locale=}, then the {@code Accept-Language} tags by quality. */
    static List<String> languages(String locale, String acceptLanguage) {
        List<String> out = new java.util.ArrayList<>();
        if (locale != null && !locale.isBlank()) {
            out.add(locale);
        }
        if (acceptLanguage != null) {
            List<String[]> tags = new java.util.ArrayList<>();
            for (String part : acceptLanguage.split(",")) {
                String[] bits = part.strip().split(";");
                double q = 1.0;
                for (int i = 1; i < bits.length; i++) {
                    String b = bits[i].strip();
                    if (b.startsWith("q=")) {
                        try {
                            q = Double.parseDouble(b.substring(2));
                        } catch (NumberFormatException ignored) {
                            q = 0;
                        }
                    }
                }
                if (!bits[0].isBlank() && !"*".equals(bits[0].strip()) && q > 0) {
                    tags.add(new String[] {bits[0].strip(), Double.toString(q)});
                }
            }
            tags.sort((a, b) -> Double.compare(Double.parseDouble(b[1]), Double.parseDouble(a[1])));
            tags.forEach(t -> out.add(t[0]));
        }
        return out;
    }
}
