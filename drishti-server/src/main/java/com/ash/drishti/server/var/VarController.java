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
 * The VaR engine's API (VAR_GUIDE.md section 11). A scope is the scope grammar's text in the {@code scope} parameter
 * ({@code DESK:RATES - TRD:MX-20000001}); every answer is computed as the caller, over the trades they may see, on the request's business
 * date (the newest date with vectors when it is later). Errors are the usual problem details: a scope that does not parse is 400, a
 * scope naming positions an aggregate-only viewer may not see is 403.
 *
 * <pre>
 *   GET  /api/v1/var/scope?scope=                        the headline: VaR, ES, diversification, limit, backtest, provenance
 *   GET  /api/v1/var/scope/path?scope=&amp;page=&amp;size=      histogram, worst 25, series, a page of the ranked scenarios
 *   GET  /api/v1/var/scope/history?scope=                VaR, ES and hypothetical P&amp;L over 250 days, exceptions, traffic light
 *   GET  /api/v1/var/scope/contributions?scope=&amp;by=&amp;scenario=   standalone, component, incremental and marginal VaR of the parts
 *   GET  /api/v1/var/hints?q=&amp;limit=                  the basket tray's type-ahead: type, id, name, trades, standalone VaR
 *   POST /api/v1/var/what-if {scope, items}             a pre-deal what-if: copies of booked trades scaled, before and after (Python)
 *   GET  /api/v1/var/tree?hierarchy=MGMT                 every node with its figures; the stored portfolios
 *   GET  /api/v1/var/status                              what is loaded, the settings
 *   GET|POST /api/v1/var/baskets, GET|DELETE /api/v1/var/baskets/{owner}/{name}
 *   POST /api/v1/var/captures {scope, note, ccy}; GET /api/v1/var/captures?scope=; GET /api/v1/var/captures/{id}[/csv]; GET /api/v1/var/compare?a=&amp;b=
 *   GET  /api/v1/var/official?asOf=&amp;ccy=               a date's official end-of-day record; POST runs it (administrators)
 * </pre>
 *
 * <p>Every answer of a scope, the tree, the hints, a capture, the what-if ({@code "ccy"} in its body) and the official record take a
 * reporting currency, {@code ccy=EUR} (VAR_GUIDE.md section 15.10; none: USD), and say it in {@code currency}: the vectors are converted
 * through USD with each scenario's FX, so the currency move is part of the risk.
 */
@RestController
@RequestMapping("/api/v1/var")
public class VarController {

    private final VarService svc;
    private final VarViews views;
    private final VarCaptures captures;
    private final VarOfficial official;
    private final VarWhatIf whatIf;
    private final Entitlements entitlements;

    VarController(VarService svc, VarViews views, VarOfficial official, Entitlements entitlements) {
        this.svc = svc;
        this.official = official;
        this.views = views;
        this.captures = new VarCaptures(svc, views);
        this.whatIf = new VarWhatIf(svc);
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
    public Map<String, Object> headline(@RequestParam String scope, @RequestParam(required = false) String ccy, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.headline(p, scope(scope), date(asOf), ccy);
    }

    /** The design's path form for a simple scope ({@code /var/scopes/DESK:RATES}); a scope with a slash uses {@code ?scope=}. */
    @GetMapping("/scopes/{scope}")
    public Map<String, Object> headlinePath(@PathVariable String scope, @RequestParam(required = false) String ccy, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.headline(p, scope(scope), date(asOf), ccy);
    }

    @GetMapping("/scope/path")
    public Map<String, Object> path(@RequestParam String scope, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "0") int size,
            @RequestParam(required = false) String ccy, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.path(p, scope(scope), date(asOf), page, size, ccy);
    }

    @GetMapping("/scope/history")
    public Map<String, Object> history(@RequestParam String scope, @RequestParam(required = false) String ccy, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.history(p, scope(scope), date(asOf), ccy);
    }

    @GetMapping("/scope/contributions")
    public Map<String, Object> contributions(@RequestParam String scope, @RequestParam(required = false) String by,
            @RequestParam(required = false) String scenario, @RequestParam(required = false) String ccy, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.contributions(p, scope(scope), date(asOf), by, scenario, ccy);
    }

    /** The basket tray's type-ahead: what {@code q} may name, each with its type, id, name, trade count and standalone VaR as the caller. */
    @GetMapping("/hints")
    public List<Map<String, Object>> hints(@RequestParam(required = false) String q, @RequestParam(defaultValue = "8") int limit,
            @RequestParam(required = false) String ccy, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.selection().hints(p, q, limit, date(asOf), ccy);
    }

    /** A pre-deal what-if: {@code {scope, items: [{copyOf, factor, label}]}}, by the Python VaR engine (503 DRS-5205 when it is not running). */
    @PostMapping("/what-if")
    public Map<String, Object> whatIf(@RequestBody Map<String, Object> body, @RequestParam(required = false) String ccy, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, svc.props().kinds().vector());
        Map<String, Object> b = body;
        if (ccy != null && !body.containsKey("ccy")) {
            b = new LinkedHashMap<>(body);
            b.put("ccy", ccy);
        }
        return whatIf.whatIf(p, b, date(asOf));
    }

    @GetMapping("/tree")
    public Map<String, Object> tree(@RequestParam(required = false) String hierarchy, @RequestParam(required = false) String ccy, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.tree(p, hierarchy, date(asOf), ccy);
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
        m.put("tier", svc.loader().tier().describe());
        m.put("kinds", pr.kinds().all());
        m.put("positionRules", svc.loader().rules().toString());
        m.put("official", Map.of("enabled", pr.official().enabled(), "publish", pr.official().publish(), "retentionYears",
                pr.official().retentionYears(), "dates", svc.official().dates().size(), "currencies", pr.official().currencies()));
        m.put("currencies", "USD at load; any currency with scenario FX on first ask (ccy=EUR), cached per date");
        m.put("loaded", svc.loaded());
        return m;
    }

    /** Drops the loaded dates, so the next request reads the lake again (after a new load). Administrators only. */
    @PostMapping("/reload")
    public Map<String, Object> reload(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        svc.reload();
        svc.audit(p.user(), "var-reload", "all dates", "an administrator's reload");
        return Map.of("reloaded", true);
    }

    // ------------------------------------------------------------------------------------------------------------------- official

    /** A date's official end-of-day record (every node's figures and capture id), for a viewer who sees every node. */
    @GetMapping("/official")
    public Map<String, Object> officialRecord(@RequestParam(required = false) String ccy, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if (!svc.access().seesAll(p)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "the official record covers every node: your role sees only some of them");
        }
        String c = VarCurrencies.normalise(ccy);
        LocalDate d = svc.date(date(asOf)).date();
        return svc.official().index(d, c).orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no official VaR record of " + d
                + (c.equals(VarDate.USD) ? "" : " in " + c + " (the currencies captured officially: " + svc.props().official().currencies()
                + ", drishti.var.official.currencies)")));
    }

    /** Captures a date officially now (the data-load signal does it by itself). Administrators only; says what it did. */
    @PostMapping("/official")
    public Map<String, Object> captureOfficial(AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        LocalDate d = svc.date(date(asOf)).date();
        return official.capture(d);
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
        Map<String, Object> b = svc.saved().saveBasket(p, body);
        svc.audit(p.user(), "var-basket-save", String.valueOf(b.get("id")), "version=" + b.get("version") + " scope=" + b.get("scope")
                + " sharedWith=" + b.get("sharedWith"));
        return b;
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
        svc.audit(p.user(), "var-basket-delete", owner + "/" + name, "deleted by its owner");
    }

    // ------------------------------------------------------------------------------------------------------------------- captures

    /** Captures a scope's VaR now: {@code {"scope": "BASKET:me/x", "note": "before the unwind"}}. */
    @PostMapping("/captures")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> capture(@RequestBody Map<String, Object> body, @RequestParam(required = false) String ccy, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Object c = body.get("ccy") != null ? body.get("ccy") : ccy;
        return captures.capture(p, scope((String) body.get("scope")), date(asOf), body.get("note") == null ? null : String.valueOf(body.get("note")),
                c == null ? null : String.valueOf(c));
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
