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
import com.ash.drishti.server.playground.ExportSizing;
import com.ash.drishti.server.playground.PlaygroundTemplate;
import com.ash.drishti.server.security.Principal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * The Exposure Playground's API (docs/guides/EXPOSURE_GUIDE.md section 29; the command {@code PLAY EXP <netting set>}). Every figure is
 * the server's: the screen draws what {@code /playground} answers and the workbook is generated here. {@code nettingSet} is a netting
 * set id ({@code NS-ALDERSHOT-FRA}) or {@code EXAMPLE} (the hand-built case X1). Parameters: {@code q} (one to four confidences,
 * {@code 0.95,0.99}), {@code rule} ({@code kth}, {@code interpolated}), {@code collateral} ({@code lagged}, {@code current}, {@code none}),
 * {@code date} (the contribution date: a tenor or {@code peak}), {@code paths} (the first N paths, at most {@code max-paths}),
 * {@code trades} (the trade columns: a count or {@code All}; the rest in Rest of set), {@code at} (the screen's path table date).
 *
 * <pre>
 *   GET /api/v1/exposure/playground?nettingSet=           the profile, the headline, the path table, the contributions, the export's choices
 *   GET /api/v1/exposure/playground/estimate?nettingSet=  each export choice's size before it is made
 *   GET /api/v1/exposure/playground.xlsx?nettingSet=&amp;trades=   the workbook of live formulas
 *   GET /api/v1/exposure/playground/template              the workbook of the hand-built case X1
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/exposure")
public class ExposurePlaygroundController {

    private final ExposureService svc;
    private final ExposurePlayground playground;
    private final PlaygroundTemplate template;

    ExposurePlaygroundController(ExposureService svc, ExposurePlaygroundProperties props) {
        this.svc = svc;
        this.playground = new ExposurePlayground(svc, props);
        this.template = props.template().isBlank() ? PlaygroundTemplate.builtIn("exposure", ExposurePlaygroundBook.STYLES)
                : PlaygroundTemplate.folder(Path.of(props.template()), ExposurePlaygroundBook.STYLES);
    }

    private static LocalDate date(AsOf a) {
        return a == null ? null : a.businessDate();
    }

    private ExposurePlayground.Choices choices(String q, String rule, String collateral, String date, Integer paths, String trades, int defaultTrades) {
        return ExposurePlayground.Choices.of(svc, playground.props(), q, rule, collateral, date, paths, trades, defaultTrades);
    }

    @GetMapping("/playground")
    public Map<String, Object> playground(@RequestParam(required = false) String nettingSet, @RequestParam(required = false) String q,
            @RequestParam(required = false) String rule, @RequestParam(required = false) String collateral, @RequestParam(required = false) String date,
            @RequestParam(required = false) Integer paths, @RequestParam(required = false) String trades, @RequestParam(required = false) String at, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        ExposurePlaygroundProperties pp = playground.props();
        ExposurePlaygroundModel m = playground.build(p, nettingSet, date(asOf), choices(q, rule, collateral, date, paths, trades, 0));
        return answer(m, pp, at);
    }

    /** The screen's answer: the model's figures as they are, nothing left for the browser to compute. */
    Map<String, Object> answer(ExposurePlaygroundModel m, ExposurePlaygroundProperties pp, String at) {
        ExposurePlaygroundModel.About ab = m.about;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nettingSet", ab.nettingSet());
        out.put("counterparty", ab.counterparty());
        out.put("businessDate", ab.businessDate());
        out.put("runId", ab.runId());
        out.put("calibration", ab.calibration());
        out.put("runPaths", ab.runPaths());
        out.put("paths", m.n);
        out.put("setTrades", ab.setTrades());
        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("confidences", m.qs);
        ch.put("rankRule", m.ruleText());
        ch.put("collateralModel", m.modelText());
        ch.put("contributionDate", m.tenor(m.contributionAt));
        ch.put("contributionPeak", m.contributionPeak);
        List<String> tenors = new ArrayList<>();
        for (int i = 0; i < m.primaryCount(); i++) {
            tenors.add(m.tenor(i));
        }
        ch.put("tenors", tenors);
        out.put("choices", ch);
        Map<String, Object> csa = new LinkedHashMap<>();
        csa.put("csa", m.csa.present() ? m.csa.csaId() : null);
        csa.put("margined", m.csa.margined());
        csa.put("thresholdThem", m.csa.thThem());
        csa.put("thresholdUs", m.csa.thUs());
        csa.put("mtaThem", m.csa.mtaThem());
        csa.put("mtaUs", m.csa.mtaUs());
        csa.put("independentAmount", m.csa.ia());
        csa.put("rounding", m.csa.rounding());
        csa.put("heldToday", m.csa.heldToday());
        csa.put("imHeld", m.csa.imHeld());
        csa.put("mporDays", ab.mporDays());
        csa.put("lookbackDays", ab.lookbackDays());
        out.put("csa", csa);
        out.put("v0", m.v0);
        out.put("figures", m.figures());
        out.put("profile", m.profile());
        int ai = at == null || at.isBlank() ? m.contributionAt : Math.max(0, ExposurePlayground.contributionAt(m.grid, at));
        Map<String, Object> pt = new LinkedHashMap<>();
        pt.put("tenor", m.tenor(ai));
        pt.put("shown", Math.min(pp.browserPaths(), m.n));
        pt.put("of", m.n);
        pt.put("rows", m.paths(ai, pp.browserPaths()));
        out.put("pathTable", pt);
        List<Map<String, Object>> contrib = m.contributions(m.columns.size());
        double sum = 0;
        for (double x : m.share) {
            sum += x;
        }
        Map<String, Object> cb = new LinkedHashMap<>();
        cb.put("tenor", m.tenor(m.contributionAt));
        cb.put("ee", m.stats.ee[m.contributionAt]);
        cb.put("sum", sum);
        cb.put("shown", Math.min(pp.browserTrades(), contrib.size()));
        cb.put("of", contrib.size());
        cb.put("rows", contrib.subList(0, Math.min(pp.browserTrades(), contrib.size())));
        out.put("contributions", cb);
        out.put("runFigures", ab.runFigures());
        out.put("provenance", ab.provenance());
        out.put("formulas", ExposurePlaygroundBook.formulas());
        out.put("explainSteps",template.explain().stream().map(s -> Map.of("title", s[0], "text", s[1])).toList());
        Map<String, Object> ex = new LinkedHashMap<>();
        ex.put("choices", pp.exportChoices());
        ex.put("default", pp.exportDefault());
        ex.put("estimates", playground.estimates(ab.setTrades(), m.n, m.grid.size(), m.primaryCount(), m.qs.length));
        out.put("export", ex);
        return out;
    }

    @GetMapping("/playground/estimate")
    public Map<String, Object> estimate(@RequestParam(required = false) String nettingSet, @RequestParam(required = false) Integer paths, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        int[] z = playground.sizes(p, nettingSet, date(asOf), paths);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nettingSet", nettingSet);
        out.put("paths", z[1]);
        out.put("setTrades", z[0]);
        out.put("estimates", playground.estimates(z[0], z[1], z[2], z[3], svc.props().confidences().length));
        return out;
    }

    @GetMapping("/playground.xlsx")
    public ResponseEntity<StreamingResponseBody> export(@RequestParam(required = false) String nettingSet, @RequestParam(required = false) String q,
            @RequestParam(required = false) String rule, @RequestParam(required = false) String collateral, @RequestParam(required = false) String date,
            @RequestParam(required = false) Integer paths, @RequestParam(required = false) String trades, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        ExposurePlaygroundProperties pp = playground.props();
        int def;
        try {
            def = ExposurePlaygroundProperties.trades(pp.exportDefault());
        } catch (IllegalArgumentException e) {
            def = 500;
        }
        ExposurePlayground.Choices ch = choices(q, rule, collateral, date, paths, trades, def);
        ExposurePlaygroundModel m = playground.build(p, nettingSet, date(asOf), ch);   // built before a byte is sent: a refusal is still a problem answer
        ExportSizing.Estimate est = playground.estimate(m);
        if (est.refused() != null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the workbook of " + m.columns.size() + " trade columns and " + m.n + " paths cannot be made: "
                    + est.refused());
        }
        String safe = m.about.nettingSet().replaceAll("[^A-Za-z0-9_.~-]+", "-");
        return xlsx(m, p, "exposure-playground_" + safe + "_" + m.about.businessDate() + ".xlsx");
    }

    @GetMapping("/playground/template")
    public ResponseEntity<StreamingResponseBody> template(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return xlsx(ExposurePlayground.example(null), p, "exposure-playground-template.xlsx");
    }

    private ResponseEntity<StreamingResponseBody> xlsx(ExposurePlaygroundModel m, Principal p, String name) {
        ExposurePlaygroundBook book = new ExposurePlaygroundBook(m, template, true, p == null ? "" : p.user(), Instant.now().toString());
        StreamingResponseBody body = book::write;
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"").header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }
}
