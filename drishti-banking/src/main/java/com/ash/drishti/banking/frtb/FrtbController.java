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
package com.ash.drishti.banking.frtb;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.banking.compute.frtb.FactorDictionary;
import com.ash.drishti.banking.compute.frtb.FrtbChoices;
import com.ash.drishti.banking.compute.frtb.FrtbCompare;
import com.ash.drishti.banking.compute.frtb.FrtbSa;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Position sensitivities and FRTB-SA (FRTB_SA_GUIDE.md section 9), as the person:
 * <pre>
 *   GET  /api/v1/risk/sensitivities?scope=&amp;by=RISK_FACTOR|BUCKET|RISK_CLASS|TENOR|MEASURE|&lt;attribute&gt;&amp;ccy=   SENS: sums for any scope
 *   GET  /api/v1/risk/risk-factors?q=&amp;limit=                     RFAC: the factor dictionary and the load's refusals
 *   GET  /api/v1/risk/frtb/sa?scope=&amp;ccy=&amp;&lt;switches&gt;&amp;override=path=value   FRSA: SBM by class and scenario, DRC, RRAO, the total
 *   GET  /api/v1/risk/frtb/sa/explain?scope=&amp;class=             the steps: buckets, WS, K, S, the scenario
 *   GET  /api/v1/risk/frtb/sa/contributions?scope=&amp;by=DESK      Euler contributions that add up to the total
 *   GET  /api/v1/risk/frtb/playground?scope=&amp;compare=switch=value   PLAY FRTB: the grid, the Choices panel, a comparison in words
 *   GET  /api/v1/risk/frtb/playground.xlsx?scope=                   the workbook of live formulas (default and every one-switch alternative)
 *   GET  /api/v1/risk/frtb/playground/template                      the workbook of the hand-built cases (no data needed)
 *   POST /api/v1/risk/frtb/playground/recipes?scope=                save the run as a recipe
 *   GET  /api/v1/risk/frtb/playground/recipes/{id}                  PLAY FRTB RUN &lt;id&gt;: replay a recipe to the cent
 * </pre>
 * The switches are {@code curvatureSource}, {@code girrCurvatureShock}, {@code fxCurvatureDivisor}, {@code girrCorrelation} and
 * {@code scenario} (FrtbChoices); an {@code override} makes every figure "not the regulatory figure".
 */
@RestController
public class FrtbController {

    private final FrtbService svc;
    private final FrtbRecipes recipes;
    private final PlaygroundTemplate template;

    public FrtbController(FrtbService svc) {
        this.svc = svc;
        this.recipes = new FrtbRecipes(Path.of(svc.props().recipesDir()), svc);
        String t = svc.props().playgroundTemplate();
        this.template = t.isBlank() ? PlaygroundTemplate.builtIn("frtb-sa", FrtbPlaygroundBook.STYLES)
                : PlaygroundTemplate.folder(Path.of(t), FrtbPlaygroundBook.STYLES);
    }

    static FrtbChoices choices(String curvatureSource, String girrCurvatureShock, String fxCurvatureDivisor, String girrCorrelation, String scenario,
            List<String> overrides) {
        Map<String, String> o = new LinkedHashMap<>();
        for (String x : overrides == null ? List.<String>of() : overrides) {
            int eq = x.indexOf('=');
            if (eq <= 0) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "an override is path=value (GIRR.delta.across-buckets=0.6), not " + x);
            }
            o.put(x.substring(0, eq).strip(), x.substring(eq + 1).strip());
        }
        try {
            return new FrtbChoices(curvatureSource, girrCurvatureShock, fxCurvatureDivisor, girrCorrelation, scenario, o);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    private static LocalDate date(AsOf a) {
        return a == null ? null : a.businessDate();
    }

    private FrtbService.Run run(Principal p, String scope, AsOf asOf, String ccy, FrtbChoices c) {
        return svc.run(p, scope, date(asOf), null, ccy, c);
    }

    @GetMapping("/api/v1/risk/sensitivities")
    public Map<String, Object> sensitivities(@RequestParam(required = false) String scope, @RequestParam(required = false) String by,
            @RequestParam(required = false) String ccy, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return svc.sums(p, scope, date(asOf), by, ccy);
    }

    @GetMapping("/api/v1/risk/risk-factors")
    public Map<String, Object> riskFactors(@RequestParam(required = false) String q, @RequestParam(defaultValue = "200") int limit, AsOf asOf) {
        svc.sensitivities();
        FrtbData.Day day = svc.data().day(new AsOf(date(asOf), null));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FactorDictionary.Factor f : day.dictionary().search(q, Math.max(1, Math.min(limit, 5000)))) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("factor", f.name());
            x.put("class", f.riskClass());
            x.put("type", f.type());
            x.put("qualifier", f.qualifier());
            x.put("curve", f.curve());
            x.put("tenor", f.tenor());
            x.put("underlyingTenor", f.underlyingTenor());
            x.put("frtbBucket", f.frtbBucket());
            x.put("simmBucket", f.simmBucket());
            x.put("sourceNames", f.sourceNames());
            rows.add(x);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", String.valueOf(day.date()));
        m.put("factors", day.dictionary().size());
        m.put("version", day.dictionary().version());
        m.put("rows", rows);
        m.put("refusals", day.refusals());
        m.put("warnings", day.warnings());
        m.put("records", day.records().size());
        m.put("proxied", day.proxied());
        return m;
    }

    @GetMapping("/api/v1/risk/frtb/sa")
    public Map<String, Object> sa(@RequestParam(required = false) String scope, @RequestParam(required = false) String ccy,
            @RequestParam(required = false) String curvatureSource, @RequestParam(required = false) String girrCurvatureShock,
            @RequestParam(required = false) String fxCurvatureDivisor, @RequestParam(required = false) String girrCorrelation,
            @RequestParam(required = false) String scenario, @RequestParam(required = false) List<String> override, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        FrtbChoices c = choices(curvatureSource, girrCurvatureShock, fxCurvatureDivisor, girrCorrelation, scenario, override);
        return FrtbAnswer.sa(run(p, scope, asOf, ccy, c));
    }

    @GetMapping("/api/v1/risk/frtb/sa/explain")
    public Map<String, Object> explain(@RequestParam(required = false) String scope, @RequestParam(required = false, name = "class") String cls,
            @RequestParam(required = false) String ccy, @RequestParam(required = false) String curvatureSource,
            @RequestParam(required = false) String girrCurvatureShock, @RequestParam(required = false) String fxCurvatureDivisor,
            @RequestParam(required = false) String girrCorrelation, @RequestParam(required = false) String scenario,
            @RequestParam(required = false) List<String> override, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        FrtbChoices c = choices(curvatureSource, girrCurvatureShock, fxCurvatureDivisor, girrCorrelation, scenario, override);
        return FrtbAnswer.explain(run(p, scope, asOf, ccy, c), cls, svc.props().maxFactors());
    }

    @GetMapping("/api/v1/risk/frtb/sa/contributions")
    public Map<String, Object> contributions(@RequestParam(required = false) String scope, @RequestParam(required = false) String by,
            @RequestParam(required = false) String ccy, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return FrtbAnswer.contributions(run(p, scope, asOf, ccy, FrtbChoices.defaults()), by);
    }

    /** The one-switch alternatives of a run, each computed by the engine. */
    List<FrtbPlaygroundBook.Alternative> alternatives(Principal p, FrtbService.Run base, String ccy) {
        List<FrtbPlaygroundBook.Alternative> out = new ArrayList<>();
        FrtbChoices c = base.result().choices();
        for (FrtbChoices alt : c.alternatives()) {
            String name = "";
            for (FrtbChoices.Switch s : FrtbChoices.SWITCHES) {
                if (!alt.get(s.name()).equals(c.get(s.name()))) {
                    name = s.name();
                }
            }
            FrtbSa.Result r = svc.run(p, base.scope(), base.date(), base.knownAt(), ccy, alt).result();
            out.add(new FrtbPlaygroundBook.Alternative(name, alt.get(name), r.sbm().sbm(), r.total()));
        }
        return out;
    }

    @GetMapping("/api/v1/risk/frtb/playground")
    public Map<String, Object> playground(@RequestParam(required = false) String scope, @RequestParam(required = false) String ccy,
            @RequestParam(required = false) String curvatureSource, @RequestParam(required = false) String girrCurvatureShock,
            @RequestParam(required = false) String fxCurvatureDivisor, @RequestParam(required = false) String girrCorrelation,
            @RequestParam(required = false) String scenario, @RequestParam(required = false) List<String> override,
            @RequestParam(required = false) String compare, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        FrtbChoices c = choices(curvatureSource, girrCurvatureShock, fxCurvatureDivisor, girrCorrelation, scenario, override);
        FrtbService.Run run = run(p, scope, asOf, ccy, c);
        Map<String, Object> m = FrtbAnswer.sa(run);
        m.put("choices", FrtbAnswer.choices(c));
        m.put("grid", FrtbAnswer.grid(run, svc.props().maxFactors()));
        m.put("factors", run.result().sbm().model.risks.size() + run.result().sbm().model.curvs.size());
        m.put("maxFactors", svc.props().maxFactors());
        m.put("explainSteps", template.explain().stream().map(s -> Map.of("title", s[0], "text", s[1])).toList());
        if (compare != null && !compare.isBlank()) {
            int eq = compare.indexOf('=');
            if (eq <= 0) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "compare is switch=value (girr-correlation=exact)");
            }
            String name = compare.substring(0, eq).strip();
            String value = compare.substring(eq + 1).strip();
            FrtbChoices alt;
            try {
                alt = c.with(name, value);
            } catch (IllegalArgumentException e) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
            }
            FrtbService.Run b = svc.run(p, scope, date(asOf), null, ccy, alt);
            FrtbCompare.Diff d = FrtbCompare.compare(run.result(), b.result(), name + ": " + c.get(name) + " → " + alt.get(name));
            m.put("compare", FrtbAnswer.diff(d));
            m.put("compareAnswer", FrtbAnswer.sa(b));
        }
        return m;
    }

    private ResponseEntity<StreamingResponseBody> xlsx(FrtbService.Run run, Principal p, String ccy, String recipe, String name) {
        FrtbPlaygroundBook book = new FrtbPlaygroundBook(run, alternatives(p, run, ccy), template, true, p == null ? "" : p.user(), Instant.now().toString(),
                recipe);
        StreamingResponseBody body = book::write;
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"").header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    @GetMapping("/api/v1/risk/frtb/playground.xlsx")
    public ResponseEntity<StreamingResponseBody> export(@RequestParam(required = false) String scope, @RequestParam(required = false) String ccy,
            @RequestParam(required = false) String curvatureSource, @RequestParam(required = false) String girrCurvatureShock,
            @RequestParam(required = false) String fxCurvatureDivisor, @RequestParam(required = false) String girrCorrelation,
            @RequestParam(required = false) String scenario, @RequestParam(required = false) List<String> override,
            @RequestParam(defaultValue = "true") boolean saveRecipe, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        FrtbChoices c = choices(curvatureSource, girrCurvatureShock, fxCurvatureDivisor, girrCorrelation, scenario, override);
        FrtbService.Run run = run(p, scope, asOf, ccy, c);
        int factors = run.result().sbm().model.risks.size() + run.result().sbm().model.curvs.size();
        if (factors > svc.props().maxFactors()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the scope has " + factors + " factors, more than drishti.frtb.max-factors ("
                    + svc.props().maxFactors() + "): export a desk or a set of positions");
        }
        String recipe = saveRecipe ? recipes.save(run, svc.sbmBytes(), svc.drcBytes(), p).id() : null;
        String safe = run.scope().replaceAll("[^A-Za-z0-9_.~-]+", "-");
        return xlsx(run, p, ccy, recipe, "frtb-sa-playground_" + safe + "_" + run.date() + "_" + run.reporting() + ".xlsx");
    }

    @GetMapping("/api/v1/risk/frtb/playground/template")
    public ResponseEntity<StreamingResponseBody> template(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        FrtbService.Run run = svc.run(p, FrtbService.EXAMPLE, null, null, "USD", FrtbChoices.defaults());
        return xlsx(run, p, "USD", null, "frtb-sa-playground-hand-built-cases.xlsx");
    }

    @PostMapping("/api/v1/risk/frtb/playground/recipes")
    public Map<String, Object> save(@RequestParam(required = false) String scope, @RequestParam(required = false) String ccy,
            @RequestParam(required = false) String curvatureSource, @RequestParam(required = false) String girrCurvatureShock,
            @RequestParam(required = false) String fxCurvatureDivisor, @RequestParam(required = false) String girrCorrelation,
            @RequestParam(required = false) String scenario, @RequestParam(required = false) List<String> override, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        FrtbChoices c = choices(curvatureSource, girrCurvatureShock, fxCurvatureDivisor, girrCorrelation, scenario, override);
        FrtbRecipes.Recipe r = recipes.save(run(p, scope, asOf, ccy, c), svc.sbmBytes(), svc.drcBytes(), p);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("scope", r.scope());
        m.put("date", r.date());
        m.put("knownAt", r.knownAt());
        m.put("switches", r.switches());
        m.put("overrides", r.overrides());
        m.put("parametersHash", r.parametersHash());
        m.put("inputsDigest", r.inputsDigest());
        m.put("figures", r.figures());
        m.put("command", "PLAY FRTB RUN " + r.id());
        return m;
    }

    @GetMapping("/api/v1/risk/frtb/playground/recipes/{id}")
    public Map<String, Object> replay(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        FrtbRecipes.Replay rp = recipes.replay(id, p);
        Map<String, Object> m = FrtbAnswer.sa(rp.run());
        m.put("choices", FrtbAnswer.choices(rp.run().result().choices()));
        m.put("grid", FrtbAnswer.grid(rp.run(), svc.props().maxFactors()));
        m.put("recipe", Map.of("id", rp.recipe().id(), "created", rp.recipe().created(), "user", rp.recipe().user(), "knownAt",
                String.valueOf(rp.recipe().knownAt()), "inputsEqual", rp.inputsEqual(), "figuresEqual", rp.figuresEqual(), "differences", rp.differences()));
        m.put("reproduced", rp.figuresEqual() && rp.inputsEqual() ? "reproduced to the cent: the inputs and every figure equal the recipe's"
                : "not reproduced: " + String.join("; ", rp.differences()));
        return m;
    }
}
