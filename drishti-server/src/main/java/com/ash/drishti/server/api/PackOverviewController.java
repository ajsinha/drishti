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
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.engine.search.SearchProperties;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.PackAccess;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A pack's overview ({@code MKT <GO>}): what the pack is, what it builds on, and for each kind the caller may open its
 * mnemonic, how many entities the sources hold for the business date, an example id and the key columns. Kinds the
 * caller may not open are left out.
 */
@RestController
@RequestMapping("/api/v1/packs")
public class PackOverviewController {

    private final PackRegistry registry;
    private final PackAccess access;
    private final Entitlements entitlements;
    private final Mnemonics mnemonics;
    private final SourceRouter router;
    private final SearchProperties search;

    public PackOverviewController(PackRegistry registry, PackAccess access, Entitlements entitlements, Mnemonics mnemonics,
            SourceRouter router, SearchProperties search) {
        this.registry = registry;
        this.access = access;
        this.entitlements = entitlements;
        this.mnemonics = mnemonics;
        this.router = router;
        this.search = search;
    }

    @GetMapping("/{name}/overview")
    public Map<String, Object> overview(@PathVariable String name, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        Pack pack = access.byCodeOrName(principal.user(), name).flatMap(n -> registry.packs().stream().filter(p -> p.name().equals(n)).findFirst())
                .orElseThrow(() -> new DrishtiException(ErrorCode.BAD_REQUEST, "no pack '" + name + "' is switched on"));
        List<Map<String, Object>> kinds = new ArrayList<>();
        for (String kind : pack.kinds()) {
            if (!entitlements.mayOpen(principal, kind)) {
                continue;
            }
            var listing = router.list(kind, "", search.maxScan() + 1, search.budget(), asOf, new com.ash.drishti.engine.source.SourceFailures());
            List<EntityHit> hits = listing.hits();
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("kind", kind);
            String code = mnemonics.codeFor(kind);
            k.put("mnemonic", code);
            k.put("label", code == null ? kind : mnemonics.of(code).map(m -> m.label()).orElse(kind));
            k.put("count", Math.min(hits.size(), search.maxScan()));
            k.put("more", hits.size() > search.maxScan());
            k.put("failed", listing.failed());                 // sources that could not list the kind: the count may be short
            k.put("example", hits.isEmpty() ? null : hits.get(0).ref().id());
            k.put("columns", search.columnsOf(kind).stream().map(c -> c.substring(2)).toList());
            kinds.add(k);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", pack.name());
        out.put("code", pack.code());
        out.put("title", pack.title());
        out.put("description", pack.description());
        out.put("version", pack.version());
        out.put("extends", pack.parents());
        out.put("kinds", kinds);
        return out;
    }
}
