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
package com.ash.drishti.banking.var;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.pivot.PivotViews;
import com.ash.drishti.server.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The MR lens of the pivot engine, {@code /api/v1/pivot/mr} (MR_CUBE_GUIDE.md section 10; engine {@code mr-cube}: off, every path answers
 * {@code 404 DRS-1020} from the engines' gate). The grid is the pivot engine's own {@code POST /api/v1/pivot/query} on source {@code var};
 * these are what the MR lens draws beside it, as the person:
 * <pre>
 *   POST /api/v1/risk/mr/headline  {view}                         the headline strip of the view's scope (VaR, ES, limit use, stressed, data)
 *   POST /api/v1/risk/mr/detail    {view, coords}                 a cell's tail and histogram through the view's lens
 *   POST /api/v1/risk/mr/history   {view}                         the sparklines: the view on the open dates up to its own
 *   GET  /api/v1/risk/mr/versions                                 the time-travel slider's stops: dates and their batches
 *   GET  /api/v1/risk/mr/sensitivities?scope=&amp;grid=&amp;by=&amp;class=&amp;measure=&amp;ccy=   the DV01 ladder by a grid, or the heat map by two parts
 *   POST /api/v1/risk/mr/what-if   {view, items}                  the what-if side by side: each row of the view and its total
 * </pre>
 * A {@code view} is the view itself or a saved pivot view's id ({@code owner/name}), opened with the reader's entitlements.
 */
@RestController
@RequestMapping("/api/v1/risk/mr")
public class MrCubeController {

    private final MrCubeExtras extras;
    private final PivotViews views;
    private final AuditLog audit;

    public MrCubeController(MrCubeExtras extras, PivotViews views, AuditLog audit) {
        this.extras = extras;
        this.views = views;
        this.audit = audit;
    }

    @SuppressWarnings("unchecked")
    private PivotView view(Principal p, Map<String, Object> body) {
        Object v = body == null ? null : body.get("view");
        if (v instanceof String id) {
            return views.open(p, id);
        }
        if (v instanceof Map<?, ?> m) {
            return PivotView.parse((Map<String, Object>) m);
        }
        throw PivotView.bad("the MR lens needs the view: {\"view\": {\"source\": \"var\", ...}} or a saved view's id");
    }

    private static Map<String, String> coords(Map<String, Object> body) {
        Map<String, String> c = new LinkedHashMap<>();
        if (body.get("coords") instanceof Map<?, ?> m) {
            m.forEach((k, x) -> c.put(String.valueOf(k), String.valueOf(x)));
        }
        return c;
    }

    @PostMapping("/headline")
    public Map<String, Object> headline(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        return extras.headline(p, view(p, body));
    }

    @PostMapping("/detail")
    public Map<String, Object> detail(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        return extras.detail(p, view(p, body), coords(body));
    }

    @PostMapping("/history")
    public Map<String, Object> history(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        return extras.history(p, view(p, body));
    }

    @GetMapping("/versions")
    public Map<String, Object> versions(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return extras.versions(p);
    }

    @GetMapping("/sensitivities")
    public Map<String, Object> sensitivities(@RequestParam(required = false) String scope, @RequestParam(required = false) String grid,
            @RequestParam(required = false) String by, @RequestParam(required = false, name = "class") String riskClass,
            @RequestParam(required = false) String measure, @RequestParam(required = false) String ccy, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return extras.sensitivities(p, scope, asOf == null ? null : asOf.businessDate(), grid, by, riskClass, measure, ccy);
    }

    @PostMapping("/what-if")
    public Map<String, Object> whatIf(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        PivotView v = view(p, body);
        Map<String, Object> out = extras.whatIf(p, v, body.get("items"));
        audit.record(p.user(), "pivot.mr.what-if", v.source(), "rows=" + v.rows() + "; date=" + (v.date() == null ? "latest" : v.date()));
        return out;
    }
}
