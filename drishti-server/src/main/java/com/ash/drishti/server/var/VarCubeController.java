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
import com.ash.drishti.server.cube.CubeEngine;
import com.ash.drishti.server.cube.CubeView;
import com.ash.drishti.server.cube.CubeViews;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The MR cube's own endpoints beside the shared core's (MR_CUBE_GUIDE.md section 10), as the person:
 * <pre>
 *   GET  /api/v1/cube/mr/versions                                   the time-travel slider's stops: dates and their batches
 *   GET  /api/v1/cube/mr/explain?scope=&amp;from=&amp;to=&amp;confidence=       why a cell's VaR moved: the engine's exact steps on its scope
 *   GET  /api/v1/cube/mr/sensitivities?scope=&amp;grid=&amp;by=&amp;class=&amp;measure=   the DV01 ladder by a grid, or the heat map by two factor parts
 *   POST /api/v1/cube/mr/what-if  {view, items}                     the what-if side by side: each row of the view and its total
 *   GET  /api/v1/cube/mr/cell?view=&lt;json|owner/name&gt;&amp;row=&amp;column=   one cell (as POST /api/v1/cube/mr/cell)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/cube/mr")
@ConditionalOnProperty(name = "drishti.cube.mr.enabled", havingValue = "true", matchIfMissing = true)
public class VarCubeController {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int WHAT_IF_ROWS = 40;
    private final VarCubeExtras extras;
    private final CubeEngine engine;
    private final CubeViews views;

    public VarCubeController(VarCubeExtras extras, CubeEngine engine, CubeViews views) {
        this.extras = extras;
        this.engine = engine;
        this.views = views;
    }

    @GetMapping("/versions")
    public Map<String, Object> versions(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return extras.versions(p);
    }

    @GetMapping("/explain")
    public Map<String, Object> explain(@RequestParam(required = false) String scope, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) Double confidence,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return extras.explain(p, scope, VarExplainController.date(from, "from"), VarExplainController.date(to, "to"), confidence);
    }

    @GetMapping("/sensitivities")
    public Map<String, Object> sensitivities(@RequestParam(required = false) String scope, @RequestParam(required = false) String grid,
            @RequestParam(required = false) String by, @RequestParam(required = false, name = "class") String riskClass,
            @RequestParam(required = false) String measure, @RequestParam(required = false) String ccy, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return extras.sensitivities(p, scope, asOf == null ? null : asOf.businessDate(), grid, by, riskClass, measure, ccy);
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/what-if")
    public Map<String, Object> whatIf(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Object v = body.get("view");
        CubeView view = CubeView.of(v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of("scope", "BANK"), "mr");
        Map<String, Object> a = engine.query(p, new CubeView("mr", view.scope(), view.rows(), List.of(), List.of("VAR"), view.filters(), view.date(),
                view.lens(), view.sort(), 0));
        List<String> scopes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) a.get("rows")) {
            if (scopes.size() >= WHAT_IF_ROWS) {
                break;
            }
            scopes.add((String) r.get("scope"));
            labels.add(String.join(" › ", (List<String>) r.get("labels")));
        }
        Map<String, Object> total = (Map<String, Object>) a.get("total");
        scopes.add((String) total.get("scope"));
        labels.add(String.valueOf(a.get("canonical")));
        Map<String, Object> out = new java.util.LinkedHashMap<>(extras.whatIf(p, scopes, labels, body.get("items"), view.date(), view.lens().get("ccy")));
        out.put("view", view.toMap());
        out.put("date", a.get("date"));
        return out;
    }

    @SuppressWarnings("unchecked")
    @GetMapping("/cell")
    public Map<String, Object> cell(@RequestParam String view, @RequestParam(required = false) String row, @RequestParam(required = false) String column,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        CubeView v;
        String s = view.strip();
        if (s.startsWith("{")) {
            try {
                v = CubeView.of(JSON.readValue(s, Map.class), "mr");
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new DrishtiException(ErrorCode.CUBE_BAD_VIEW, "view is a JSON object or a saved view's id (owner/name)");
            }
        } else if (s.toUpperCase(java.util.Locale.ROOT).startsWith(CubeView.WORD)) {
            v = CubeView.parse(s, "mr");
        } else {
            v = views.open(p, s);
        }
        return engine.cell(p, v, row, column);
    }
}
