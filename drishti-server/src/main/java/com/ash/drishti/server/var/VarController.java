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
package com.ash.drishti.server.var;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The VaR engine's API (VAR_ENGINE.md section 14). A scope is the scope grammar's text in the {@code scope} parameter
 * ({@code DESK:RATES - TRD:MX-20000001}); every answer is computed as the caller, over the trades they may see, on the request's business
 * date (the newest date with vectors when it is later). Errors are the usual problem details: a scope that does not parse is 400, a
 * scope naming positions an aggregate-only viewer may not see is 403.
 *
 * <pre>
 *   GET  /api/v1/var/scope?scope=                        the headline: VaR, ES, diversification, limit, backtest, provenance
 *   GET  /api/v1/var/scope/path?scope=&amp;page=&amp;size=      histogram, worst 25, series, a page of the ranked scenarios
 *   GET  /api/v1/var/scope/history?scope=                VaR, ES and hypothetical P&amp;L over 250 days, exceptions, traffic light
 *   GET  /api/v1/var/scope/contributions?scope=&amp;by=&amp;scenario=   standalone, component, incremental and marginal VaR of the parts
 *   GET  /api/v1/var/tree?hierarchy=MGMT                 every node with its figures; the stored portfolios
 *   GET  /api/v1/var/status                              what is loaded, the settings
 *   GET|POST /api/v1/var/baskets, GET|DELETE /api/v1/var/baskets/{owner}/{name}
 *   POST /api/v1/var/captures {scope, note}; GET /api/v1/var/captures?scope=; GET /api/v1/var/captures/{id}[/csv]; GET /api/v1/var/compare?a=&amp;b=
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/var")
public class VarController {

    private final VarService svc;
    private final VarViews views;
    private final VarCaptures captures;
    private final Entitlements entitlements;

    public VarController(VarService svc, Entitlements entitlements) {
        this.svc = svc;
        this.views = new VarViews(svc);
        this.captures = new VarCaptures(svc, views);
        this.entitlements = entitlements;
    }

    private static LocalDate date(AsOf asOf) {
        return asOf == null ? null : asOf.businessDate();
    }

    private static String scope(String scope) {
        if (scope == null || scope.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "give a scope: scope=BANK, DESK:RATES, POS:<id>, TRD:<id>, PORT:<id> ...");
        }
        return scope.trim();
    }

    @GetMapping("/scope")
    public Map<String, Object> headline(@RequestParam String scope, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.headline(p, scope(scope), date(asOf));
    }

    /** The design's path form for a simple scope ({@code /var/scopes/DESK:RATES}); a scope with a slash uses {@code ?scope=}. */
    @GetMapping("/scopes/{scope}")
    public Map<String, Object> headlinePath(@PathVariable String scope, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.headline(p, scope(scope), date(asOf));
    }

    @GetMapping("/scope/path")
    public Map<String, Object> path(@RequestParam String scope, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "0") int size,
            AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.path(p, scope(scope), date(asOf), page, size);
    }

    @GetMapping("/scope/history")
    public Map<String, Object> history(@RequestParam String scope, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.history(p, scope(scope), date(asOf));
    }

    @GetMapping("/scope/contributions")
    public Map<String, Object> contributions(@RequestParam String scope, @RequestParam(required = false) String by,
            @RequestParam(required = false) String scenario, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.contributions(p, scope(scope), date(asOf), by, scenario);
    }

    @GetMapping("/tree")
    public Map<String, Object> tree(@RequestParam(required = false) String hierarchy, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.tree(p, hierarchy, date(asOf));
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        VarProperties pr = svc.props();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", pr.enabled());
        m.put("confidence", pr.confidence());
        m.put("esConfidence", pr.esConfidence());
        m.put("rankRule", pr.rankRule());
        m.put("componentWindow", pr.componentWindow());
        m.put("threads", svc.sums().threads());
        m.put("memoryBudgetMb", pr.memoryBudgetMb());
        m.put("hotDates", pr.hotDates());
        m.put("kinds", pr.kinds().all());
        m.put("loaded", svc.loaded());
        return m;
    }

    /** Drops the loaded dates, so the next request reads the lake again (after a new load). Administrators only. */
    @PostMapping("/reload")
    public Map<String, Object> reload(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        svc.reload();
        return Map.of("reloaded", true);
    }

    // ------------------------------------------------------------------------------------------------------------------- baskets

    @GetMapping("/baskets")
    public List<Map<String, Object>> baskets(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return svc.saved().baskets(p);
    }

    /** Saves a basket: {@code {"name": "unwind", "title": "Unwind candidates", "scope": "POS:a,b + TRD:c", "sharedWith": ["risk"]}}. */
    @PostMapping("/baskets")
    public Map<String, Object> saveBasket(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, svc.props().kinds().vector());
        return svc.saved().saveBasket(p, body);
    }

    @GetMapping("/baskets/{owner}/{name}")
    public Map<String, Object> basket(@PathVariable String owner, @PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return svc.saved().basket(p, owner + "/" + name)
                .orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no basket " + owner + "/" + name + " you may read"));
    }

    @DeleteMapping("/baskets/{owner}/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBasket(@PathVariable String owner, @PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if (!owner.equals(com.ash.drishti.identity.content.ContentDocs.name(p.user())) || !svc.saved().deleteBasket(p, name)) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no basket " + owner + "/" + name + " of yours");
        }
    }

    // ------------------------------------------------------------------------------------------------------------------- captures

    /** Captures a scope's VaR now: {@code {"scope": "BASKET:me/x", "note": "before the unwind"}}. */
    @PostMapping("/captures")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> capture(@RequestBody Map<String, Object> body, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return captures.capture(p, scope((String) body.get("scope")), date(asOf), body.get("note") == null ? null : String.valueOf(body.get("note")));
    }

    @GetMapping("/captures")
    public List<Map<String, Object>> captures(@RequestParam(required = false) String scope, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, svc.props().kinds().vector());
        return captures.list(p, scope);
    }

    @GetMapping("/captures/{id}")
    public Map<String, Object> capture(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, svc.props().kinds().vector());
        return captures.get(p, id);
    }

    @GetMapping("/captures/{id}/csv")
    public ResponseEntity<String> captureCsv(@PathVariable String id, @RequestParam(required = false) String part,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, svc.props().kinds().vector());
        return ResponseEntity.ok().contentType(new MediaType("text", "csv"))
                .header("Content-Disposition", "attachment; filename=\"" + id + (part == null ? "" : "-" + part) + ".csv\"")
                .body(captures.csv(p, id, part));
    }

    /** Two captures side by side ({@code a}, {@code b}), or a capture and now ({@code b=now}). */
    @GetMapping("/compare")
    public Map<String, Object> compare(@RequestParam String a, @RequestParam(required = false) String b,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, svc.props().kinds().vector());
        return captures.compare(p, a, b);
    }
}
