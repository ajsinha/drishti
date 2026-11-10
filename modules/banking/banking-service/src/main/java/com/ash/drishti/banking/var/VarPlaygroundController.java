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
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.playground.ExportSizing;
import com.ash.drishti.kernel.api.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The VaR Playground's API (docs/guides/VAR_GUIDE.md section 11), as the person:
 * <pre>
 *   GET /api/v1/risk/var/playground?scope=|ids=&amp;ccy=&amp;horizon=&amp;method=&amp;confidence=&amp;esConfidence=&amp;set=&amp;view=pnl|pv|fx
 *        the grid (at most drishti.var.playground.max-positions positions, never more than 500; the rest in Rest of scope), the figures,
 *        the engine's figures and the checks, the formula bar's formulas, and the export's choices with their estimates
 *   GET /api/v1/risk/var/playground/estimate?scope=|ids=   the export's choices (100, 500, 1,000, 10,000, All) with size, time and applications
 *   GET /api/v1/risk/var/playground.xlsx?scope=|ids=&amp;count=500|all   the workbook, streamed (refused in words when no application opens it)
 *   GET /api/v1/risk/var/playground/template?positions=10&amp;scenarios=509   an empty workbook to fill and recalculate offline
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/risk/var")
public class VarPlaygroundController {

    private final VarPlayground playground;

    VarPlaygroundController(VarService svc, VarViews views, @Value("${drishti.var.playground.enabled:true}") boolean enabled,
            @Value("${drishti.var.playground.max-positions:500}") int maxPositions,
            @Value("${drishti.var.playground.incremental-positions:50}") int incremental, @Value("${drishti.var.playground.max-ids:50}") int maxIds,
            @Value("${drishti.var.playground.template:}") String template,
            @Value("${drishti.var.playground.export-choices:100,500,1000,10000,0}") List<Integer> choices,
            @Value("${drishti.var.playground.export-default:500}") int exportDefault,
            @Value("${drishti.var.playground.export-max-cells:20000000}") long maxCells) {
        this.playground = new VarPlayground(svc, views, new VarPlayground.Settings(enabled, maxPositions, incremental, maxIds, template, choices, maxCells,
                exportDefault));
    }

    private static VarPlayground.Ask ask(String scope, String ids, AsOf asOf, String ccy, String horizon, String method, Double confidence,
            Double esConfidence, String set) {
        LocalDate d = asOf == null ? null : asOf.businessDate();
        return new VarPlayground.Ask(scope, ids, d, ccy, horizon, method, confidence, esConfidence, set);
    }

    @GetMapping("/playground")
    public Map<String, Object> grid(@RequestParam(required = false) String scope, @RequestParam(required = false) String ids,
            @RequestParam(required = false) String ccy, @RequestParam(required = false) String horizon, @RequestParam(required = false) String method,
            @RequestParam(required = false) Double confidence, @RequestParam(required = false) Double esConfidence,
            @RequestParam(required = false) String set, @RequestParam(required = false) String view,
            @RequestParam(required = false) String measure, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        VarPlayground.Ask a = ask(scope, ids, asOf, ccy, horizon, method, confidence, esConfidence, set);
        VarPlayground.Built b = playground.build(p, a, playground.settings().maxPositions());
        Map<String, Object> out = VarPlaygroundAnswer.grid(b, playground, view);
        if (parametric(measure)) {                                       // the Parametric tab's figures, steps and columns
            out = new LinkedHashMap<>(out);
            List<String> shownIds = b.model().positions.stream().map(VarPlaygroundModel.Pos::id).toList();
            out.put("parametric", VarPlaygroundParametric.answer(playground.parametric(b), b.scope(), shownIds, playground.describer()));
            out.put("measure", "parametric");
        }
        return out;
    }

    @GetMapping("/playground/estimate")
    public Map<String, Object> estimate(@RequestParam(required = false) String scope, @RequestParam(required = false) String ids,
            @RequestParam(required = false) String ccy, @RequestParam(required = false) String set, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        VarPlayground.Built b = playground.build(p, ask(scope, ids, asOf, ccy, null, null, null, null, set), 1);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("canonical", b.scoped().res().canonical());
        m.put("positions", b.total());
        m.put("scenarios", b.model().n);
        m.put("choices", playground.choices(b.total(), b.model().n, b.model().fx.size()));
        return m;
    }

    @GetMapping(value = "/playground.xlsx")
    public ResponseEntity<StreamingResponseBody> export(@RequestParam(required = false) String scope, @RequestParam(required = false) String ids,
            @RequestParam(required = false) String ccy, @RequestParam(required = false) String horizon, @RequestParam(required = false) String method,
            @RequestParam(required = false) Double confidence, @RequestParam(required = false) Double esConfidence,
            @RequestParam(required = false) String set, @RequestParam(required = false) String count,
            @RequestParam(required = false) String measure, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        int n = count(count);
        VarPlayground.Ask a = ask(scope, ids, asOf, ccy, horizon, method, confidence, esConfidence, set);
        VarPlayground.Built b = playground.build(p, a, n);                 // built before a byte is sent: a refusal is still a problem answer
        ExportSizing.Estimate est = playground.estimate(n, b.total(), b.model().n, b.model().fx.size());
        if (est.refused() != null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the workbook of " + (n == 0 ? "all " + b.total() : n) + " positions cannot be made: " + est.refused());
        }
        VarPlaygroundBook book = new VarPlaygroundBook(b.model(), b.engine(), b.about(), playground.template(), true);
        if (parametric(measure)) {
            book.parametric(playground.parametric(b));                  // the Parametric tab after PnL
        }
        String name = parametric(measure) ? fileName(b).replace("var-playground", "pvar-playground") : fileName(b);
        StreamingResponseBody body = book::write;
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"").header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Drishti-Positions", String.valueOf(b.model().positions.size())).header("X-Drishti-Estimated-Bytes", String.valueOf(est.bytes()))
                .body(body);
    }

    @GetMapping("/playground/template")
    public ResponseEntity<StreamingResponseBody> template(@RequestParam(defaultValue = "10") int positions, @RequestParam(defaultValue = "509") int scenarios,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if (positions < 1 || positions > VarPlayground.BROWSER_MAX || scenarios < 20 || scenarios > 5000) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "positions is 1 to " + VarPlayground.BROWSER_MAX + " and scenarios 20 to 5000");
        }
        VarPlaygroundBook book = VarPlaygroundAnswer.blank(playground, positions, scenarios, p == null ? "" : p.user());
        StreamingResponseBody body = book::write;
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"var-playground-template-" + positions + "x" + scenarios + ".xlsx\"")
                .body(body);
    }

    /** {@code measure=parametric} (the Parametric tab); anything else but blank or {@code historical} is refused. */
    static boolean parametric(String measure) {
        if (measure == null || measure.isBlank() || measure.equalsIgnoreCase("historical")) {
            return false;
        }
        if (measure.equalsIgnoreCase("parametric")) {
            return true;
        }
        throw new DrishtiException(ErrorCode.BAD_REQUEST, "measure is historical or parametric, not " + measure);
    }

    /** {@code all} (or 0) is every position; else a positive count. */
    static int count(String count) {
        if (count == null || count.isBlank()) {
            return 500;
        }
        String c = count.strip().toLowerCase(Locale.ROOT).replace(",", "");
        if (c.equals("all") || c.equals("0")) {
            return 0;
        }
        try {
            int n = Integer.parseInt(c);
            if (n < 1) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "count is a number of positions (100, 500, 1000, 10000) or all");
        }
    }

    static String fileName(VarPlayground.Built b) {
        VarPlaygroundModel m = b.model();
        String scope = b.scoped().res().canonical().replaceAll("[^A-Za-z0-9_.~-]+", "-").replaceAll("^-+|-+$", "");
        if (scope.length() > 60) {
            scope = scope.substring(0, 60);
        }
        String lens = m.set.horizon() + ("10D".equals(m.set.horizon()) ? "-" + m.set.method() : "") + "_"
                + com.ash.drishti.banking.compute.var.VarLens.pct(m.set.confidence()).replace("%", "").replace(".", "_");
        List<String> parts = new ArrayList<>(List.of("var-playground", scope, b.scoped().date().date().toString(), m.reporting, lens));
        return String.join("_", parts) + ".xlsx";
    }
}
