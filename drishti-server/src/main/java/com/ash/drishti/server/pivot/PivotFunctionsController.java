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
package com.ash.drishti.server.pivot;

import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.custom.CustomFunctions;
import com.ash.drishti.bicompute.custom.FunctionDef;
import com.ash.drishti.bicompute.harness.FunctionCheck;
import com.ash.drishti.bicompute.harness.Playground;
import com.ash.drishti.bicompute.spi.Batch;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.bicompute.whatif.Overlay;
import com.ash.drishti.bicompute.whatif.WhatIf;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.explain.ExplainRequest;
import com.ash.drishti.server.explain.NumberExplainService;
import com.ash.drishti.server.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pivot engine's custom functions, explanations and what-ifs (PIVOT_GUIDE.md; API_GUIDE.md "Pivot: custom functions, explain and
 * what-if"), under {@code /api/v1/pivot}: functions listed, read, tested in the playground (the harness, the states after each part and
 * merge, the cost, a pivot of the sample), saved as versions after the harness passes, submitted, approved by someone other than the
 * author, withdrawn; the difference between two cells or one cell on two dates in steps that add up; a view with a what-if overlay beside
 * the original. Everything is audited; figures come from the {@code drishti-bicompute} engine.
 */
@RestController
@RequestMapping("/api/v1/pivot")
public class PivotFunctionsController {

    private final PivotController pivot;
    private final PivotCore core;
    private final CustomFunctions functions;
    private final AuditLog audit;
    private final PivotViewVersions versions;
    private final ObjectProvider<NumberExplainService> explain;

    public PivotFunctionsController(PivotController pivot, PivotCore core, CustomFunctions functions, AuditLog audit, PivotViewVersions versions,
            ObjectProvider<NumberExplainService> explain) {
        this.pivot = pivot;
        this.core = core;
        this.functions = functions;
        this.audit = audit;
        this.versions = versions;
        this.explain = explain;
    }

    // ---- view versions --------------------------------------------------------------------------------------------------------------

    @GetMapping("/views/{owner}/{name}/versions")
    public Map<String, Object> versions(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String owner, @PathVariable String name) {
        pivot.on(p);
        return Map.of("id", owner + "/" + name, "versions", versions.list(p, owner + "/" + name));
    }

    @GetMapping("/views/{owner}/{name}/versions/{version}")
    public Map<String, Object> version(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String owner, @PathVariable String name,
            @PathVariable int version) {
        pivot.on(p);
        return versions.version(p, owner + "/" + name, version);
    }

    @GetMapping("/views/{owner}/{name}/diff")
    public Map<String, Object> diff(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String owner, @PathVariable String name,
            @RequestParam int from, @RequestParam int to) {
        pivot.on(p);
        return versions.diff(p, owner + "/" + name, from, to);
    }

    /** {@code {version}}: the caller's old version saved again as the next one. Audited. */
    @PostMapping("/views/{name}/restore")
    public Map<String, Object> restore(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String name,
            @RequestBody Map<String, Object> body) {
        pivot.on(p);
        int v = body.get("version") instanceof Number n ? n.intValue() : -1;
        Map<String, Object> out = versions.restore(p, name, v);
        audit.record(p.user(), "pivot.view.restore", String.valueOf(out.get("id")), "from version " + v + " as version " + out.get("version"));
        return out;
    }

    // ---- custom functions -----------------------------------------------------------------------------------------------------------

    @GetMapping("/functions")
    public Map<String, Object> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Viewer v = pivot.on(p);
        CustomFunctions.Settings s = functions.settings();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("functions", functions.list(v));
        out.put("languages", Map.of("formula", s.formula(), "sql", s.sql(), "python", s.python(), "java", s.java()));
        out.put("requireApproval", s.requireApproval());
        return out;
    }

    @GetMapping("/functions/{owner}/{name}")
    public Map<String, Object> get(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String owner, @PathVariable String name,
            @RequestParam(required = false) Integer version) {
        return functions.get(pivot.on(p), owner, name, version).toMap();
    }

    /**
     * The playground: {@code {name, language, code, entry, inputs, kind, ..., sample, parts, group, projectRows, nodes}}. Nothing is saved;
     * the harness report, the states after each part and merge, the cost and the pivot of the sample are answered.
     */
    @PostMapping("/functions/test")
    public Map<String, Object> test(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        Viewer v = pivot.on(p);
        FunctionDef d = functions.define(v, body, 0);
        Batch sample = CustomFunctions.sample(d.inputs(), body.get("sample"));
        FunctionCheck.Verdict verdict = functions.test(v, d, sample);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("definition", d.toMap());
        out.put("harness", verdict.toMap());
        String[] groups = groups(body.get("sample"), body.get("group") == null ? null : String.valueOf(body.get("group")), sample.rows());
        long project = body.get("projectRows") instanceof Number n ? n.longValue() : 0;
        int nodes = body.get("nodes") instanceof Number n ? n.intValue() : 1;
        int parts = body.get("parts") instanceof Number n ? n.intValue() : 3;
        try {
            out.put("playground", Playground.run(functions.make(d, true), sample, parts, groups, project, nodes));
        } catch (RuntimeException e) {
            out.put("playground", Map.of("error", String.valueOf(e.getMessage())));
        }
        return out;
    }

    /** The sample's group column (the playground's little pivot), when the author picked one. */
    @SuppressWarnings("unchecked")
    static String[] groups(Object sample, String group, int rows) {
        if (group == null || group.isBlank()) {
            return null;
        }
        String[] out = new String[rows];
        List<Object> list = List.of();
        List<?> cols = List.of();
        if (sample instanceof Map<?, ?> m) {
            if (m.get("rows") instanceof List<?> rs) {
                list = (List<Object>) rs;
            }
            if (m.get("columns") instanceof List<?> c) {
                cols = c;
            }
        } else if (sample instanceof List<?> rs) {
            list = (List<Object>) rs;
        }
        int ci = cols.indexOf(group);
        for (int r = 0; r < Math.min(rows, list.size()); r++) {
            Object row = list.get(r);
            Object g = row instanceof Map<?, ?> rm ? rm.get(group) : row instanceof List<?> rl && ci >= 0 && ci < rl.size() ? rl.get(ci) : null;
            out[r] = g == null ? null : String.valueOf(g);
        }
        return out;
    }

    /** Saves the caller's function as its next version, after its harness passes on {@code sample}. */
    @PostMapping("/functions")
    public Map<String, Object> save(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        Viewer v = pivot.on(p);
        FunctionDef draft = functions.define(v, body, 0);
        return functions.save(v, body, CustomFunctions.sample(draft.inputs(), body.get("sample"))).toMap();
    }

    @PostMapping("/functions/{owner}/{ref}/submit")
    public Map<String, Object> submit(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String owner, @PathVariable String ref,
            @RequestBody(required = false) Map<String, Object> body) {
        String[] nv = ref(ref);
        return functions.submit(pivot.on(p), owner, nv[0], Integer.parseInt(nv[1]), str(body, "note")).toMap();
    }

    @PostMapping("/functions/{owner}/{ref}/revoke")
    public Map<String, Object> revoke(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String owner, @PathVariable String ref,
            @RequestBody(required = false) Map<String, Object> body) {
        String[] nv = ref(ref);
        return functions.revoke(pivot.on(p), owner, nv[0], Integer.parseInt(nv[1]), str(body, "reason")).toMap();
    }

    private static String[] ref(String ref) {
        int at = ref.lastIndexOf('@');
        if (at <= 0 || !ref.substring(at + 1).matches("\\d+")) {
            throw new DrishtiException(ErrorCode.PIVOT_BAD_VIEW, "name a version: " + ref + "@3");
        }
        return new String[] {ref.substring(0, at), ref.substring(at + 1)};
    }

    private static String str(Map<String, Object> body, String key) {
        return body == null || body.get(key) == null ? null : String.valueOf(body.get(key));
    }

    // ---- explain and what-if --------------------------------------------------------------------------------------------------------

    /**
     * DEPRECATED, kept for one release: the one Explain is {@code POST /api/v1/explain {kind: pivot, measure: cell, value, view, from, to,
     * fromDate, toDate, by, order, price, quantity}} (PivotCellExplainer). This alias asks it (the old body's {@code measure} is the
     * value's label) and answers the engine's own answer ({@code why.answer}: words, method, steps, residual, difference) as before, with
     * the headers {@code Deprecation: true} and a {@code Link} to its successor.
     */
    @PostMapping("/explain")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> explainAlias(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        Map<String, Object> args = new LinkedHashMap<>(body == null ? Map.of() : body);
        args.remove("kind");
        args.remove("part");
        Object value = args.remove("measure");
        if (value != null && !args.containsKey("value")) {
            args.put("value", value);
        }
        Map<String, Object> x = explain.getObject().explain(p, new ExplainRequest("pivot", "cell", "why", args));
        Map<String, Object> why = (Map<String, Object>) x.get("why");
        Map<String, Object> answer = why == null ? null : (Map<String, Object>) why.get("answer");
        return ResponseEntity.ok().header("Deprecation", "true").header("Link", "</api/v1/explain>; rel=\"successor-version\"")
                .body(answer == null ? x : answer);
    }

    /** {@code {view, whatIf: {exclude, changes, add}}}: the view with the overlay beside the original, cell by cell. Never written anywhere. */
    @PostMapping("/whatif")
    public Map<String, Object> whatIf(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        Viewer v = pivot.on(p);
        PivotView view = pivot.view(p, body);
        Overlay o = Overlay.parse(body.get("whatIf"));
        Map<String, Object> out = WhatIf.run(core, v, view, o);
        audit.record(p.user(), "pivot.whatif", view.source(), "overlay=" + o.signature() + "; excluded=" + o.exclude().keySet() + "; changes="
                + o.changes().size() + "; added=" + o.add().size());
        return out;
    }
}
