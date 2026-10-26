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
package com.ash.drishti.server.exposure;

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
 * The exposure engine's API (EXPOSURE_GUIDE.md section 19.2). A scope is the scope grammar's text ({@code NSET:NS-ALDERSHOT-FRA},
 * {@code CPTY:CP-ALDERSHOT}, {@code GROUP:GRP-ALDERSHOT}, {@code LE:LE-NY}, {@code DESK:SFT & CPTY:CP-ALDERSHOT}, {@code TRD:<id>}); every
 * answer is computed as the caller, over the netting sets they see, on the request's business date (the newest with paths when later).
 * Parameters: {@code q} (the PFE confidence, default {@code limit-confidence}), {@code collateral} ({@code lagged}, {@code current},
 * {@code none}), {@code run} (empty: the date's newest complete run), {@code strict} (refuse a date whose run is inconsistent instead of
 * answering from the date before).
 *
 * <pre>
 *   GET  /api/v1/exposure/scope?scope=                     figures, profile, netting sets, flags, provenance
 *   GET  /api/v1/exposure/scope/profile?scope=&amp;dates=      the profile only (dates=all: the look-back points too); format=csv
 *   GET  /api/v1/exposure/scope/distribution?scope=&amp;date=  the exposure distribution at a primary date
 *   GET  /api/v1/exposure/scope/contributions?scope=&amp;by=&amp;figure=&amp;date=   parts' EE, EPE or PFE contributions, incremental PFE
 *   GET  /api/v1/exposure/tree                              groups, counterparties, netting sets with their EPE and peak PFE
 *   POST /api/v1/exposure/what-if                           incremental exposure before dealing (method addon; paths: the Python engine)
 *   POST /api/v1/exposure/captures {scope, note}; GET /captures[?scope=]; GET /captures/{id}[/csv]; GET /compare?a=&amp;b=
 *   GET  /api/v1/exposure/status; POST /reload (administrators); GET /retention
 *   GET  /api/v1/exposure/official?asOf=                   a date's official record; POST runs it (administrators)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/exposure")
public class ExposureController {

    private final ExposureService svc;
    private final ExposureViews views;
    private final ExposureCaptures captures;
    private final ExposureWhatIf whatIf;
    private final ExposureOfficial official;
    private final Entitlements entitlements;

    ExposureController(ExposureService svc, ExposureViews views, ExposureOfficial official, Entitlements entitlements) {
        this.svc = svc;
        this.views = views;
        this.official = official;
        this.captures = new ExposureCaptures(svc, views);
        this.whatIf = new ExposureWhatIf(svc);
        this.entitlements = entitlements;
    }

    private static LocalDate date(AsOf asOf) {
        return asOf == null ? null : asOf.businessDate();
    }

    private static String scope(String scope) {
        if (scope == null || scope.isBlank()) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "give a scope: scope=NSET:<netting set>, CPTY:<counterparty>, GROUP:<group>, "
                    + "LE:<legal entity>, TRD:<trade>, BANK ...");
        }
        return ExposureService.scopeText(scope);
    }

    private ExposureService.Ask ask(String q, String collateral, String run, Boolean strict) {
        return ExposureService.Ask.of(svc.props(), q, collateral, run, strict);
    }

    @GetMapping("/scope")
    public Map<String, Object> scope(@RequestParam String scope, @RequestParam(required = false) String q, @RequestParam(required = false) String collateral,
            @RequestParam(required = false) String run, @RequestParam(required = false) Boolean strict, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.scope(p, scope(scope), date(asOf), ask(q, collateral, run, strict));
    }

    /** The path form for a simple scope ({@code /exposure/scopes/NSET:NS-ALDERSHOT-FRA}). */
    @GetMapping("/scopes/{scope}")
    public Map<String, Object> scopePath(@PathVariable String scope, @RequestParam(required = false) String q, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.scope(p, scope(scope), date(asOf), ask(q, null, null, null));
    }

    @GetMapping("/scope/profile")
    public ResponseEntity<?> profile(@RequestParam String scope, @RequestParam(required = false) String q, @RequestParam(required = false) String collateral,
            @RequestParam(required = false) String run, @RequestParam(required = false) String dates, @RequestParam(required = false) String format,
            AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        ExposureService.Ask a = ask(q, collateral, run, null);
        if ("csv".equalsIgnoreCase(format)) {
            return ResponseEntity.ok().contentType(new MediaType("text", "csv"))
                    .header("Content-Disposition", "attachment; filename=\"exposure-profile.csv\"").body(views.profileCsv(p, scope(scope), date(asOf), a));
        }
        return ResponseEntity.ok(views.profile(p, scope(scope), date(asOf), a, dates));
    }

    @GetMapping("/scope/distribution")
    public Map<String, Object> distribution(@RequestParam String scope, @RequestParam(required = false) String date,
            @RequestParam(defaultValue = "0") int bins, @RequestParam(required = false) String q, @RequestParam(required = false) String collateral,
            @RequestParam(defaultValue = "false") boolean bands, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.distribution(p, scope(scope), date(asOf), ask(q, collateral, null, null), date, bins, bands);
    }

    @GetMapping("/scope/contributions")
    public Map<String, Object> contributions(@RequestParam String scope, @RequestParam(required = false) String by,
            @RequestParam(required = false) String figure, @RequestParam(required = false) String date, @RequestParam(required = false) String q,
            @RequestParam(required = false) String collateral, @RequestParam(defaultValue = "false") boolean alone,
            @RequestParam(defaultValue = "false") boolean profile, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.contributions(p, scope(scope), date(asOf), ask(q, collateral, null, null), by, figure, date, alone, profile);
    }

    @GetMapping("/tree")
    public Map<String, Object> tree(@RequestParam(required = false) String q, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.tree(p, date(asOf), ask(q, null, null, null));
    }

    /** Incremental exposure before dealing: {@code {scope, method: addon|paths, q, candidates: [...]}}. */
    @PostMapping("/what-if")
    public Map<String, Object> whatIf(@RequestBody Map<String, Object> body, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return whatIf.whatIf(p, body, date(asOf));
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        ExposureProperties pr = svc.props();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", pr.enabled());
        m.put("confidence", pr.confidence());
        m.put("limitConfidence", pr.limitConfidence());
        m.put("rankRule", pr.rankRule());
        m.put("collateralModel", pr.collateralModel());
        m.put("imModel", pr.imModel());
        m.put("alpha", pr.alpha());
        m.put("contributionWindow", pr.contributionWindow());
        m.put("hotDates", pr.hotDates());
        m.put("memoryBudgetGb", pr.memoryBudgetGb());
        m.put("maxScopeNettingSets", pr.maxScopeNettingSets());
        m.put("tier", svc.loader().tier().describe());
        m.put("kinds", pr.kinds().all());
        m.put("official", Map.of("enabled", pr.official().enabled(), "publish", pr.official().publish(), "retentionYears",
                pr.official().retentionYears(), "dates", svc.official().dates().size()));
        m.put("loaded", svc.loaded());
        return m;
    }

    /** What the retention keeps of each date held (EXPOSURE_GUIDE.md section 27.3), and the dates whose trade paths are beyond their window. */
    @GetMapping("/retention")
    public Map<String, Object> retention(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, svc.props().kinds().paths());
        return ExposureRetention.plan(svc.props().retention(), svc.loader().held());
    }

    /** Drops the loaded dates, so the next request reads the lake again. Administrators only. */
    @PostMapping("/reload")
    public Map<String, Object> reload(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        svc.reload();
        svc.audit(p.user(), "ccr-reload", "all dates", "an administrator's reload");
        return Map.of("reloaded", true);
    }

    // ------------------------------------------------------------------------------------------------------------------- official

    @GetMapping("/official")
    public Map<String, Object> officialRecord(AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if (!svc.access().seesAll(p)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "the official record covers every counterparty: your role sees only some of them");
        }
        LocalDate d = svc.date(date(asOf)).date();
        return svc.official().index(d).orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no official exposure record of " + d));
    }

    @PostMapping("/official")
    public Map<String, Object> captureOfficial(AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        LocalDate d = svc.date(date(asOf)).date();
        return official.capture(d);
    }

    // ------------------------------------------------------------------------------------------------------------------- captures

    /** Captures a scope's exposure now: {@code {"scope": "CPTY:CP-ALDERSHOT", "note": "before the unwind", "q": 0.95}}. */
    @PostMapping("/captures")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> capture(@RequestBody Map<String, Object> body, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Object q = body.get("q");
        Object c = body.get("collateral");
        return captures.capture(p, scope((String) body.get("scope")), date(asOf), ask(q == null ? null : String.valueOf(q), c == null ? null
                : String.valueOf(c), null, null), body.get("note") == null ? null : String.valueOf(body.get("note")));
    }

    @GetMapping("/captures")
    public List<Map<String, Object>> captures(@RequestParam(required = false) String scope, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, svc.props().kinds().paths());
        return captures.list(p, scope);
    }

    @GetMapping("/captures/{id}")
    public Map<String, Object> capture(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, svc.props().kinds().paths());
        return captures.get(p, id);
    }

    @GetMapping("/captures/{id}/csv")
    public ResponseEntity<String> captureCsv(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, svc.props().kinds().paths());
        return ResponseEntity.ok().contentType(new MediaType("text", "csv")).header("Content-Disposition", "attachment; filename=\"" + id + ".csv\"")
                .body(captures.csv(p, id));
    }

    @GetMapping("/compare")
    public Map<String, Object> compare(@RequestParam String a, @RequestParam(required = false) String b, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, svc.props().kinds().paths());
        return captures.compare(p, a, b);
    }
}
