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
import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.engine.search.SearchQuery;
import com.ash.drishti.engine.search.StructuredSearch;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Structured search (W17) and pick lists: {@code GET /api/v1/search?q=TRD where mtm > 1m order by mtm desc limit 20},
 * {@code q=TRD T-100}, {@code q=TRD productType=Revolver} (see {@link SearchQuery#pick}). The caller must
 * be allowed to open the kind; the condition is evaluated on each document as the caller may see it (redacted), so
 * hidden fields cannot be probed. Follows the business date and "known at" of the request like every read.
 */
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final StructuredSearch search;
    private final Entitlements entitlements;
    private final Mnemonics mnemonics;

    public SearchController(StructuredSearch search, Entitlements entitlements, Mnemonics mnemonics) {
        this.search = search;
        this.entitlements = entitlements;
        this.mnemonics = mnemonics;
    }

    @GetMapping
    public Map<String, Object> search(@RequestParam String q, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        SearchQuery query = SearchQuery.pick(q);           // TRD where …, TRD T-100, TRD productType=Revolver, TRD
        String kind = search.kindOf(query);
        entitlements.requireOpen(principal, kind);
        StructuredSearch.Result r = search.run(query, asOf, data -> entitlements.redact(principal, data));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("query", q);
        out.put("kind", r.kind());
        out.put("mnemonic", mnemonics.codeFor(r.kind()));
        out.put("condition", r.condition());
        out.put("orderBy", r.orderBy());
        out.put("descending", query.descending());
        out.put("limit", query.limit());
        out.put("columns", r.columns());
        out.put("labels", r.columns().stream().collect(java.util.stream.Collectors.toMap(c -> c, c -> HistoryController.label(c.substring(2)),
                (a, b) -> a, java.util.LinkedHashMap::new)));
        out.put("rows", r.rows());
        out.put("scanned", r.scanned());
        out.put("matched", r.matched());
        out.put("partial", r.partial());
        out.put("elapsedMs", r.elapsedMs());
        return out;
    }
}
