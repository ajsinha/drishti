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
import com.ash.drishti.compute.ccr.FvaMath;
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
 * FVA, funding cost and benefit (docs/guides/FVA_GUIDE.md section 6), as the caller. One answer serves every screen (the netting-set page's
 * FVA section, the netting sets by FVA, the counterparty page): the scope's figures and terms, each netting set's figures and term bars,
 * the counterparties' sums. Reads only (the {@code read} token scope); nothing is stored.
 *
 * <pre>
 *   GET /api/v1/fva/scope?scope=&amp;eePoint=&amp;fba=&amp;survival=&amp;spread=   FVA of any exposure scope: NSET:, CPTY:, GROUP:, LE:, DESK: ..., BANK
 *   GET /api/v1/fva/status                                       the settings, the curves of the newest date
 *   GET /api/v1/fva/playground?scope=                            PLAY FVA: the answer, the Choices, the Explain steps
 *   GET /api/v1/fva/playground.xlsx?scope=                       the workbook of live formulas, equal to the engine to the cent
 *   GET /api/v1/fva/playground/template                          the workbook of the hand-built case F1 (b)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/fva")
public class FvaController {

    private final FvaService svc;
    private final PlaygroundTemplate template;

    FvaController(FvaService svc) {
        this.svc = svc;
        String t = svc.props().playgroundTemplate();
        this.template = t.isBlank() ? PlaygroundTemplate.builtIn("fva", FvaPlaygroundBook.STYLES) : PlaygroundTemplate.folder(Path.of(t), FvaPlaygroundBook.STYLES);
    }

    private static LocalDate date(AsOf a) {
        return a == null ? null : a.businessDate();
    }

    private static String scope(String scope) {
        if (scope == null || scope.isBlank()) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "give a scope: scope=NSET:<netting set>, CPTY:<counterparty>, GROUP:<group>, "
                    + "LE:<legal entity>, BANK ...");
        }
        return ExposureService.scopeText(scope);
    }

    static FvaService.Choices choices(String eePoint, String fba, String survival, String spread) {
        return new FvaService.Choices(eePoint, onOff("fba", fba), onOff("survival", survival), spread);
    }

    private static Boolean onOff(String name, String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        return switch (v.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "on", "true", "yes" -> true;
            case "off", "false", "no" -> false;
            default -> throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, name + " is on or off, not " + v);
        };
    }

    @GetMapping("/scope")
    public Map<String, Object> scope(@RequestParam String scope, @RequestParam(required = false) String eePoint, @RequestParam(required = false) String fba,
            @RequestParam(required = false) String survival, @RequestParam(required = false) String spread, @RequestParam(required = false) String run,
            @RequestParam(required = false) Boolean strict, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return FvaViews.answer(svc.answer(p, scope(scope), date(asOf), choices(eePoint, fba, survival, spread), run, strict));
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        svc.enabled();
        FvaProperties pr = svc.props();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fundingSpread", pr.fundingSpread());
        m.put("lendingSpread", pr.lendingSpread().isEmpty() ? "symmetric" : pr.lendingSpread());
        m.put("fallbackSpread", pr.fallbackSpread());
        m.put("discountCurve", pr.discountCurve());
        m.put("discountRate", pr.discountRate());
        m.put("curveKind", pr.curveKind());
        m.put("configuredCurves", pr.curves().stream().map(FvaProperties.Curve::id).toList());
        m.put("eePoint", pr.eePoint());
        m.put("fundingSurvival", pr.fundingSurvival());
        m.put("fba", pr.fba());
        m.put("requireRiskNeutral", pr.requireRiskNeutral());
        m.put("playgroundMaxSets", pr.playgroundMaxSets());
        LocalDate d = svc.exposure().loader().latest();
        if (d != null) {
            List<String> flags = new ArrayList<>();
            m.put("businessDate", d.toString());
            m.put("fundingCurve", FvaViews.curve(svc.spread(d, pr.fundingSpread(), flags), "spread"));
            m.put("discount", FvaViews.curve(svc.discount(d, flags), "df"));
            m.put("flags", flags);
        }
        return m;
    }

    private FvaService.Answer playable(Principal p, String scope, AsOf asOf, FvaService.Choices ch, String run) {
        FvaService.Answer a = svc.answer(p, scope(scope), date(asOf), ch, run, null);
        if (!a.setsShown()) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "the playground shows each netting set's profile; your role sees some of these sets only in aggregate");
        }
        if (a.sets().size() > svc.props().playgroundMaxSets()) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "the playground shows up to " + svc.props().playgroundMaxSets() + " netting sets side by "
                    + "side (drishti.ccr.fva.playground-max-sets); the scope has " + a.sets().size() + ": name a netting set or a counterparty");
        }
        return a;
    }

    @GetMapping("/playground")
    public Map<String, Object> playground(@RequestParam String scope, @RequestParam(required = false) String eePoint, @RequestParam(required = false) String fba,
            @RequestParam(required = false) String survival, @RequestParam(required = false) String spread, @RequestParam(required = false) String run,
            AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        FvaService.Answer a = playable(p, scope, asOf, choices(eePoint, fba, survival, spread), run);
        Map<String, Object> m = FvaViews.answer(a);
        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("eePoint", Map.of("value", a.point().text(), "options", List.of("end", "mid"), "default", svc.props().eePoint()));
        ch.put("fba", Map.of("value", a.fba() ? "on" : "off", "options", List.of("on", "off"), "default", svc.props().fba() ? "on" : "off"));
        ch.put("survival", Map.of("value", a.survivalOn() ? "on" : "off", "options", List.of("on", "off"), "default", svc.props().fundingSurvival() ? "on" : "off"));
        ch.put("spread", Map.of("value", a.borrow().id(), "default", svc.props().fundingSpread(), "hint", "a curve id or a flat spread (0.005 is 50 bp)"));
        m.put("choices", ch);
        m.put("explainSteps", template.explain().stream().map(s -> Map.of("title", s[0], "text", s[1])).toList());
        m.put("maxSets", svc.props().playgroundMaxSets());
        return m;
    }

    @GetMapping("/playground.xlsx")
    public ResponseEntity<StreamingResponseBody> export(@RequestParam String scope, @RequestParam(required = false) String eePoint,
            @RequestParam(required = false) String fba, @RequestParam(required = false) String survival, @RequestParam(required = false) String spread,
            @RequestParam(required = false) String run, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        FvaService.Answer a = playable(p, scope, asOf, choices(eePoint, fba, survival, spread), run);
        String safe = a.scoped().res().canonical().replaceAll("[^A-Za-z0-9_.~-]+", "-");
        return xlsx(input(a, svc.props()), p, "fva-playground_" + safe + "_" + a.scoped().date().date() + ".xlsx");
    }

    @GetMapping("/playground/template")
    public ResponseEntity<StreamingResponseBody> exampleWorkbook(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        svc.enabled();
        return xlsx(example(), p, "fva-playground-example.xlsx");
    }

    private ResponseEntity<StreamingResponseBody> xlsx(FvaPlaygroundBook.Input in, Principal p, String name) {
        FvaPlaygroundBook book = new FvaPlaygroundBook(in, template, p == null ? "" : p.user(), Instant.now().toString());
        StreamingResponseBody body = book::write;
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"").header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    /** The workbook's input from an answer. */
    static FvaPlaygroundBook.Input input(FvaService.Answer a, FvaProperties props) {
        var g = a.scoped().date().book().grid();
        List<String> dates = new ArrayList<>();
        for (int i = 0; i < g.primaryCount(); i++) {
            dates.add(g.date(g.primaryAt(i)).toString());
        }
        List<FvaPlaygroundBook.SetIn> sets = new ArrayList<>();
        for (FvaService.SetFva s : a.sets()) {
            sets.add(new FvaPlaygroundBook.SetIn(s.id(), s.counterparty(), s.csa(), s.ce(), s.nce(), s.result()));
        }
        Map<String, Object> prov = new LinkedHashMap<>();
        prov.put("Engine", "fva (Java): FvaMath on the exposure engine's EE and ENE profile per netting set");
        prov.put("Exposure", "run " + a.scoped().date().book().runId() + ", " + a.scoped().date().book().paths() + " paths, collateral " + a.scoped().ask().collateral());
        return new FvaPlaygroundBook.Input(a.scoped().res().canonical(), a.scoped().label(), a.scoped().date().date().toString(), a.scoped().date().book().runId(),
                a.calibration(), a.flags(), dates, a.total().t(), a.borrow(), a.lend() == a.borrow() ? null : a.lend(), a.discount(), props.counterpartyHazard(),
                props.ownHazard(), a.point(), a.survivalOn(), a.fba(), sets, a.total(), prov);
    }

    /** Case F1 (b) of COUNTERPARTY_ENGINES.md section 9: case X1's profile, flat 50 bp, DF = e^(−0.04 t). */
    static FvaPlaygroundBook.Input example() {
        double m = 1_000_000;
        double[] t = {0.25, 0.5, 0.75, 1.0};
        FvaMath.Profile prof = new FvaMath.Profile(t, new double[] {0.8 * m, 1.6 * m, 1.1 * m, 1.3 * m}, new double[] {0, 0, -0.4 * m, -0.3 * m}, 0.4 * m, 0);
        FvaMath.Curve borrow = FvaMath.Curve.flat("flat 0.005", 0.005, "case F1");
        FvaMath.Curve df = new FvaMath.Curve("e^(-0.04 t)", List.of("1Y"), new double[] {1.0}, new double[] {Math.exp(-0.04)}, "case F1 (b)");
        FvaMath.Result r = FvaMath.compute(prof, FvaMath.market(t, df, borrow, null, null), FvaMath.Point.END, true);
        return new FvaPlaygroundBook.Input("EXAMPLE", "case F1 (b): case X1's profile, 50 bp, 4 % discounting", "the hand-built example", "X1", "RISK_NEUTRAL",
                List.of(), List.of("t + 3M", "t + 6M", "t + 9M", "t + 1Y"), t, borrow, null, df, 0.02, 0.01, FvaMath.Point.END, false, true,
                List.of(new FvaPlaygroundBook.SetIn("NS-X1", "CP-EXAMPLE", "none", 0.4 * m, 0, r)), r,
                Map.of("Source", "docs/architecture/COUNTERPARTY_ENGINES.md section 9, case F1 (b): FCA −5,846.09, FBA 845.52, FVA −5,000.57"));
    }
}
