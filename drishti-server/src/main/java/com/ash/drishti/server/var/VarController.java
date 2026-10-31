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
import com.ash.drishti.server.bi.engine.ArrowAnswers;
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
 * <p>The answers of a scope (headline, path, history, contributions, currencies) and the tree take a lens (2.9.7, VAR_GUIDE.md section
 * 18.6): {@code horizon} ({@code 1d}, the default, or {@code 10d}), {@code method} ({@code sqrt10} or {@code sum}; 10-day only, none: the
 * setting {@code drishti.var.ten-day-method}), {@code confidence} and {@code esConfidence} (from {@code drishti.var.confidences} and
 * {@code es-confidences}, as fractions or percentages); every answer names them ({@code lens}). Official records stay 1-day.
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

    /**
     * The lens a request asks for (2.9.7): null when it names none (the default lens); a 400 in words when it is not offered.
     */
    private com.ash.drishti.compute.var.VarLens lens(String horizon, String method, Double confidence, Double esConfidence) {
        if (horizon == null && method == null && confidence == null && esConfidence == null) {
            return null;
        }
        try {
            return svc.props().lens(horizon, method, confidence, esConfidence);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    private boolean offered(Double confidence) {
        if (confidence == null) {
            return true;
        }
        double f = confidence > 1 ? confidence / 100.0 : confidence;
        return svc.props().confidences().stream().anyMatch(x -> Math.abs(x - f) < 1e-9);
    }

    /**
     * A scope's headline, through the lens asked ({@code horizon}, {@code method}, {@code confidence}, {@code esConfidence}, 2.9.7).
     * {@code window}, and a {@code confidence} or {@code es-confidence} outside the configured lists (2.9.4, VAR_GUIDE.md section 18.5),
     * recompute its 1-day VaR and ES on the newest {@code window} scenarios or at that confidence, to compare (1 year against 2): the answer
     * says so ({@code override}) and is never official.
     */
    @GetMapping("/scope")
    public Map<String, Object> headline(@RequestParam String scope, @RequestParam(required = false) String ccy,
            @RequestParam(required = false) String window, @RequestParam(required = false) Double confidence,
            @RequestParam(name = "es-confidence", required = false) Double esConfidence, @RequestParam(required = false) String horizon, @RequestParam(required = false) String method,
            @RequestParam(name = "esConfidence", required = false) Double esq, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if (window == null && esConfidence == null && (offered(confidence) || horizon != null || method != null || esq != null)) {
            return views.headline(p, scope(scope), date(asOf), ccy, lens(horizon, method, confidence, esq));
        }
        Map<String, Object> h = views.headline(p, scope(scope), date(asOf), ccy);
        try {
            return views.override(h, p, scope(scope), date(asOf), ccy, window == null ? null : VarProperties.checkWindow(window, "window"),
                    confidence == null ? null : VarProperties.checkConfidence(confidence, "confidence"),
                    esConfidence == null ? null : VarProperties.checkConfidence(esConfidence, "es-confidence"));
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    /** The design's path form for a simple scope ({@code /var/scopes/DESK:RATES}); a scope with a slash uses {@code ?scope=}. */
    @GetMapping("/scopes/{scope}")
    public Map<String, Object> headlinePath(@PathVariable String scope, @RequestParam(required = false) String ccy,
            @RequestParam(required = false) Double confidence, @RequestParam(required = false) String horizon, @RequestParam(required = false) String method,
            @RequestParam(name = "esConfidence", required = false) Double esq, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.headline(p, scope(scope), date(asOf), ccy, lens(horizon, method, confidence, esq));
    }

    @GetMapping("/scope/path")
    public Map<String, Object> path(@RequestParam String scope, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "0") int size,
            @RequestParam(required = false) String ccy, @RequestParam(required = false) Double confidence, @RequestParam(required = false) String horizon, @RequestParam(required = false) String method,
            @RequestParam(name = "esConfidence", required = false) Double esq, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.path(p, scope(scope), date(asOf), page, size, ccy, lens(horizon, method, confidence, esq));
    }

    @GetMapping("/scope/history")
    public Map<String, Object> history(@RequestParam String scope, @RequestParam(required = false) String ccy,
            @RequestParam(required = false) Double confidence, @RequestParam(required = false) String horizon, @RequestParam(required = false) String method,
            @RequestParam(name = "esConfidence", required = false) Double esq, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.history(p, scope(scope), date(asOf), ccy, lens(horizon, method, confidence, esq));
    }

    /**
     * The parts' contributions; {@code Accept: application/vnd.apache.arrow.stream} (2.9.7) answers them as Arrow, the rows the table
     * (by position or by trade a desk has hundreds to thousands of rows: 61% fewer bytes, docs/guides/DATA_FORMATS.md section 6).
     */
    @GetMapping("/scope/contributions")
    public ResponseEntity<Object> contributions(@RequestParam String scope, @RequestParam(required = false) String by,
            @RequestParam(required = false) String scenario, @RequestParam(required = false) String ccy,
            @RequestParam(required = false) Double confidence, @RequestParam(required = false) String horizon, @RequestParam(required = false) String method,
            @RequestParam(name = "esConfidence", required = false) Double esq, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p,
            @org.springframework.web.bind.annotation.RequestHeader(value = org.springframework.http.HttpHeaders.ACCEPT, required = false) String accept) {
        return ArrowAnswers.negotiate(accept, views.contributions(p, scope(scope), date(asOf), by, scenario, ccy,
                lens(horizon, method, confidence, esq)), "records", "rows");
    }

    /**
     * The same scope in every listed reporting currency (the explorer's "In other currencies" tab, VAR_GUIDE.md section 15.11): each
     * currency's VaR and ES from the scope's per-trade-currency totals, that VaR in {@code ccy} at today's rate and the difference.
     */
    @GetMapping("/scope/currencies")
    public Map<String, Object> inCurrencies(@RequestParam String scope, @RequestParam(required = false) String ccy,
            @RequestParam(required = false) Double confidence, @RequestParam(required = false) String horizon, @RequestParam(required = false) String method,
            @RequestParam(name = "esConfidence", required = false) Double esq, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.inCurrencies(p, scope(scope), date(asOf), ccy, lens(horizon, method, confidence, esq));
    }

    /** The reporting currencies a page offers: the listed ones, and every currency with scenario FX on the date ("More…"). */
    @GetMapping("/currencies")
    public Map<String, Object> currencies(AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, svc.props().kinds().vector());
        VarDate d = svc.date(date(asOf));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("businessDate", d.date().toString());
        m.put("listed", svc.props().currencies());
        m.put("available", List.copyOf(svc.fx(d).currencies()));
        Map<String, Object> ready = new LinkedHashMap<>();
        svc.props().currencies().forEach(c -> ready.put(c, svc.ready(d, c)));
        m.put("ready", ready);
        return m;
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
    public Map<String, Object> tree(@RequestParam(required = false) String hierarchy, @RequestParam(required = false) String ccy,
            @RequestParam(required = false) Double confidence, @RequestParam(required = false) String horizon, @RequestParam(required = false) String method,
            @RequestParam(name = "esConfidence", required = false) Double esq, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.tree(p, hierarchy, date(asOf), ccy, lens(horizon, method, confidence, esq));
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        VarProperties pr = svc.props();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", pr.enabled());
        m.put("confidence", pr.confidence());
        m.put("esConfidence", pr.esConfidence());
        m.put("confidences", pr.confidences());                      // 2.9.7: what a page and a command may choose
        m.put("esConfidences", pr.esConfidences());
        m.put("tenDayMethod", pr.tenDayMethod());
        m.put("horizons", List.of("1d", "10d"));
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
        Map<String, Object> cur = new LinkedHashMap<>();
        cur.put("listed", pr.currencies());
        cur.put("retentionDates", pr.currencyRetentionDates());
        cur.put("note", "USD at load; the listed currencies built ahead in the background after it (tiered memory), kept for the newest "
                + pr.currencyRetentionDates() + " dates; any other currency with scenario FX on first ask (ccy=CHF), cached per date");
        cur.put("builtAhead", svc.builtAhead());
        m.put("currencies", cur);
        m.put("loaded", svc.loaded());
        return m;
    }

    /** The PV topics' streams (the {@code var-pv-stream} connectors, VAR_GUIDE.md section 8.4): their windows and what they applied. */
    @GetMapping("/intraday/streams")
    public List<Map<String, Object>> streams(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, svc.props().kinds().vector());
        return VarPvStreamPlugin.RUNNING.values().stream().map(VarPvStreamPlugin::describe).toList();
    }

    /** An administrator's "apply now": the stream's waiting window applied at once, as one intraday batch. */
    @PostMapping("/intraday/streams/{name}/apply")
    public Map<String, Object> applyNow(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        VarPvStreamPlugin s = VarPvStreamPlugin.RUNNING.get(name);
        if (s == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no PV stream " + name + " running (a var-pv-stream connector, enabled)");
        }
        s.applyNow();
        Map<String, Object> r = s.flushIfDue();
        svc.audit(p.user(), "var-intraday-apply-now", name, String.valueOf(r));
        return r == null ? Map.of("status", "nothing waiting") : r;
    }

    /** Drops the loaded dates, so the next request reads the lake again (after a new load). Administrators only. */
    @PostMapping("/reload")
    public Map<String, Object> reload(@org.springframework.web.bind.annotation.RequestParam(required = false) String date,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        if (date != null && !date.isBlank()) {                          // 2.9.7 (O3): one business date only; the others stay open
            java.time.LocalDate d;
            try {
                d = java.time.LocalDate.parse(date.trim());
            } catch (java.time.format.DateTimeParseException e) {
                throw new com.ash.drishti.common.DrishtiException(com.ash.drishti.common.ErrorCode.BAD_REQUEST, "date is a business date "
                        + "(2026-09-30), not '" + date + "'");
            }
            svc.reload(d);
            svc.audit(p.user(), "var-reload", d.toString(), "an administrator's reload of one date");
            return Map.of("reloaded", true, "date", d.toString());
        }
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
        com.ash.drishti.compute.var.VarLens l = lens(str(body.get("horizon")), str(body.get("method")), num(body.get("confidence")),
                num(body.get("esConfidence")));
        return captures.capture(p, scope((String) body.get("scope")), date(asOf), body.get("note") == null ? null : String.valueOf(body.get("note")),
                c == null ? null : String.valueOf(c), l);
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }

    private static Double num(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        try {
            return o instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(o).replace("%", "").strip());
        } catch (NumberFormatException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a confidence is a number (0.99 or 99), not '" + o + "'");
        }
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

    /** A capture as JSON Lines, one row a line keyed as its CSV's header (2.9.4). */
    @GetMapping("/captures/{id}/jsonl")
    public ResponseEntity<String> captureJsonl(@PathVariable String id, @RequestParam(required = false) String part,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, svc.props().kinds().vector());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(com.ash.drishti.server.dist.JsonlWriter.MEDIA_TYPE))
                .header("Content-Disposition", "attachment; filename=\"" + id + (part == null ? "" : "-" + part) + ".jsonl\"")
                .body(captures.jsonl(p, id, part));
    }

    /** Two captures side by side ({@code a}, {@code b}), or a capture and now ({@code b=now}). */
    @GetMapping("/compare")
    public Map<String, Object> compare(@RequestParam String a, @RequestParam(required = false) String b,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, svc.props().kinds().vector());
        return captures.compare(p, a, b);
    }
}
