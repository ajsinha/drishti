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
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.rachana.SutraProblem;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Raw entities (F9), the source plugins and the loaded Sutras. */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    private final SourceRouter router;
    private final SourceRegistry sources;
    private final SutraRegistry sutras;
    private final Entitlements entitlements;
    private final java.util.Optional<org.springframework.boot.info.BuildProperties> build;
    private final com.ash.drishti.server.security.SecurityProperties security;
    private final com.ash.drishti.packs.PackRegistry packRegistry;
    private final com.ash.drishti.server.security.PackAccess packAccess;

    public CatalogController(SourceRouter router, SourceRegistry sources, SutraRegistry sutras, Entitlements entitlements,
            org.springframework.beans.factory.ObjectProvider<org.springframework.boot.info.BuildProperties> build,
            com.ash.drishti.server.security.SecurityProperties security, com.ash.drishti.packs.PackRegistry packRegistry,
            com.ash.drishti.server.security.PackAccess packAccess) {
        this.packAccess = packAccess;
        this.packRegistry = packRegistry;
        this.entitlements = entitlements;
        this.build = java.util.Optional.ofNullable(build.getIfAvailable());
        this.security = security;
        this.router = router;
        this.sources = sources;
        this.sutras = sutras;
    }

    @GetMapping("/entities/{kind}/{id}/raw")
    public ApiDtos.RawEntity raw(@PathVariable String kind, @PathVariable String id, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        entitlements.requireOpen(principal, kind);
        try {
            EntityDocument d = router.fetch(EntityRef.of(kind, id), asOf).join();
            return new ApiDtos.RawEntity(new ViewModel.Ref(kind, id), d.provenance(), entitlements.redact(principal, d.data()));
        } catch (CompletionException e) {
            throw e.getCause() instanceof DrishtiException de ? de : new DrishtiException(ErrorCode.SOURCE_FAILED, e.getMessage());
        }
    }

    /** Version, build and what is loaded: the About page. */
    @GetMapping("/about")
    public Map<String, Object> about() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("product", "Drishti");
        m.put("version", build.map(b -> b.getVersion()).orElse("dev"));
        m.put("built", build.map(b -> String.valueOf(b.getTime())).orElse(""));
        m.put("java", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        m.put("uptimeSeconds", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        m.put("sutras", sutras.all().stream().map(s -> s.name() + " v" + s.version()).sorted().toList());
        m.put("sources", sources().sources());
        m.put("securityEnabled", security.enabled());
        m.put("packs", packs(null));
        m.put("copyright", "Copyright (c) 2026 Ashutosh Sinha. All rights reserved. Proprietary and confidential.");
        return m;
    }

    /** The enabled domain packs (not switched off in Admin → Packs), with what the console needs from each. */
    @GetMapping("/packs")
    public List<Map<String, Object>> packs(@RequestAttribute(value = Principal.ATTRIBUTE, required = false) Principal who) {
        List<String> assigned = who == null ? packAccess.installed() : packAccess.assigned(who.user());
        List<String> active = who == null ? packAccess.installed() : packAccess.active(who.user());
        return packRegistry.packs().stream().filter(p -> packAccess.isEnabled(p.name())).map(p -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("name", p.name());
            m.put("version", p.version());
            m.put("title", p.title());
            m.put("description", p.description());
            m.put("console", p.manifest().getOrDefault("console", Map.of()));
            m.put("kinds", p.kinds());
            m.put("assigned", assigned.contains(p.name()));
            m.put("active", active.contains(p.name()));
            return m;
        }).toList();
    }

    /** The caller's active packs; {@code PUT} chooses among the packs assigned to them. */
    @GetMapping("/me/packs")
    public Map<String, Object> myPacks(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return Map.of("assigned", packAccess.assigned(p.user()), "active", packAccess.active(p.user()));
    }

    @org.springframework.web.bind.annotation.PutMapping("/me/packs")
    public Map<String, Object> choosePacks(@org.springframework.web.bind.annotation.RequestBody Map<String, List<String>> body,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        packAccess.choose(p.user(), body.get("active"));
        return myPacks(p);
    }

    @GetMapping("/sources")
    public ApiDtos.SourcesResponse sources() {
        List<ApiDtos.SourceInfo> out = new ArrayList<>();
        for (SourcePlugin p : sources.plugins()) {
            var m = p.manifest();
            out.add(new ApiDtos.SourceInfo(m.name(), m.version(), m.kinds(), m.capabilities().live(), m.capabilities().search(),
                    m.capabilities().reverseLookup(), p.health()));
        }
        return new ApiDtos.SourcesResponse(out, sources.failures());
    }

    @GetMapping("/sutras")
    public List<ApiDtos.SutraInfo> sutras() {
        return sutras.all().stream().map(s -> new ApiDtos.SutraInfo(s.name(), s.version(), sutras.versions(s.name()), s.domain(),
                s.match().kind(), s.match().where(), s.match().priority())).toList();
    }

    @GetMapping("/sutras/{name}/{version}")
    public Sutra sutra(@PathVariable String name, @PathVariable int version) {
        return sutras.require(name, version);
    }

    @GetMapping("/sutras/problems")
    public Map<String, List<SutraProblem>> problems() {
        return sutras.problems();
    }
}
