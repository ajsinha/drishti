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
package com.ash.drishti.banking.simm;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.banking.compute.simm.SimmChoices;
import com.ash.drishti.banking.compute.simm.SimmCompare;
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
 * ISDA SIMM (docs/guides/SIMM_GUIDE.md section 9), as the person:
 * <pre>
 *   GET  /api/v1/risk/simm?nettingSet=|scope=&amp;version=&amp;concentration=&amp;hvr=&amp;override=key=value   SIMM: collect and post, by product and risk class
 *   GET  /api/v1/risk/simm/netting-sets?counterparty=            every netting set: computed, stored (labelled) or why not computed
 *   GET  /api/v1/risk/simm/explain?nettingSet=&amp;direction=collect|post&amp;class=   the steps: buckets, factors, CR, WS, K, S
 *   GET  /api/v1/risk/simm/against?nettingSet=                  the margin against the counterparty's call (crif-external)
 *   GET  /api/v1/risk/simm/versions                              the installed parameter files (and the refused ones, with why)
 *   GET  /api/v1/risk/simm/playground?nettingSet=&amp;compare=switch=value   PLAY SIMM: the grid, the Choices panel, a comparison in words
 *   GET  /api/v1/risk/simm/playground.xlsx?nettingSet=&amp;side=     the workbook of live formulas (switch cells in Config)
 *   GET  /api/v1/risk/simm/playground/template                   the shipped workbook: the hand-built examples, every parameter cell empty
 *   POST /api/v1/risk/simm/playground/recipes?nettingSet=        save the run as a recipe
 *   GET  /api/v1/risk/simm/playground/recipes/{id}               PLAY SIMM RUN &lt;id&gt;: replay a recipe to the cent
 * </pre>
 * {@code nettingSet} is a netting set id, {@code EXAMPLE} (the hand-built examples) or any VaR scope (an analytic, labelled so).
 */
@RestController
public class SimmController {

    private final SimmService svc;
    private final SimmRecipes recipes;
    private final PlaygroundTemplate template;

    public SimmController(SimmService svc) {
        this.svc = svc;
        this.recipes = new SimmRecipes(Path.of(svc.props().recipesDir()), svc);
        String t = svc.props().playgroundTemplate();
        this.template = t.isBlank() ? PlaygroundTemplate.builtIn("simm", SimmPlaygroundBook.STYLES) : PlaygroundTemplate.folder(Path.of(t), SimmPlaygroundBook.STYLES);
    }

    static SimmChoices choices(String concentration, String hvr, List<String> overrides) {
        Map<String, String> o = new LinkedHashMap<>();
        for (String x : overrides == null ? List.<String>of() : overrides) {
            int eq = x.lastIndexOf('=');
            if (eq <= 0) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "an override is key=value (InterestRate.concentration-thresholds.delta.1=3000), not " + x);
            }
            o.put(x.substring(0, eq).strip(), x.substring(eq + 1).strip());
        }
        try {
            return new SimmChoices(concentration, hvr, o);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    private static LocalDate date(AsOf a) {
        return a == null ? null : a.businessDate();
    }

    private static String scope(String nettingSet, String scope) {
        return nettingSet != null && !nettingSet.isBlank() ? nettingSet : scope;
    }

    private SimmService.Run run(Principal p, String nettingSet, String scope, AsOf asOf, String version, SimmChoices c) {
        return svc.run(p, scope(nettingSet, scope), date(asOf), null, version, c, null);
    }

    @GetMapping("/api/v1/risk/simm")
    public Map<String, Object> simm(@RequestParam(required = false) String nettingSet, @RequestParam(required = false) String scope,
            @RequestParam(required = false) String version, @RequestParam(required = false) String concentration, @RequestParam(required = false) String hvr,
            @RequestParam(required = false) List<String> override, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return SimmAnswer.result(run(p, nettingSet, scope, asOf, version, choices(concentration, hvr, override)));
    }

    @GetMapping("/api/v1/risk/simm/netting-sets")
    public Map<String, Object> nettingSets(@RequestParam(required = false) String counterparty, @RequestParam(required = false) String version,
            @RequestParam(required = false) String concentration, @RequestParam(required = false) String hvr, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return svc.nettingSets(p, date(asOf), counterparty, version, choices(concentration, hvr, null));
    }

    @GetMapping("/api/v1/risk/simm/explain")
    public Map<String, Object> explain(@RequestParam(required = false) String nettingSet, @RequestParam(required = false) String scope,
            @RequestParam(defaultValue = "collect") String direction, @RequestParam(required = false, name = "class") String cls,
            @RequestParam(required = false) String version, @RequestParam(required = false) String concentration, @RequestParam(required = false) String hvr,
            @RequestParam(required = false) List<String> override, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        SimmService.Run run = run(p, nettingSet, scope, asOf, version, choices(concentration, hvr, override));
        return SimmAnswer.explain(run, "post".equalsIgnoreCase(direction), cls, svc.props().maxFactors());
    }

    @GetMapping("/api/v1/risk/simm/against")
    public Map<String, Object> against(@RequestParam(required = false) String nettingSet, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        SimmService.Run run = run(p, nettingSet, null, asOf, null, SimmChoices.defaults());
        if (run.nettingSet() == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the comparison with the counterparty's call is per netting set, not for " + run.scope());
        }
        SimmData.Day day = svc.data().day(new AsOf(run.date(), null));
        SimmData.Csa csa = day.csas().get(run.nettingSet().csa());
        return SimmAnswer.against(run, day.calls().getOrDefault(run.nettingSet().id(), List.of()), csa == null ? Double.NaN : csa.tolerance(), day.callsRead());
    }

    @GetMapping("/api/v1/risk/simm/versions")
    public Map<String, Object> versions() {
        svc.enabled();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SimmService.Version v : svc.versions()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("version", v.version());
            x.put("file", v.file());
            x.put("installed", v.ok());
            x.put("effectiveFrom", v.ok() ? v.params().effectiveFrom : null);
            x.put("sha256", v.ok() ? v.params().hash : null);
            x.put("problem", v.problem());
            rows.add(x);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("parametersDir", Path.of(svc.props().parametersDir()).toAbsolutePath().toString());
        m.put("default", svc.defaultVersion().isBlank() ? null : svc.defaultVersion());
        m.put("versions", rows);
        m.put("licence", "ISDA's SIMM parameters are licensed: " + com.ash.drishti.common.Branding.name("the product") + " ships none; the bank installs one file a version (docs/guides/SIMM_GUIDE.md section 4)");
        return m;
    }

    /** The one-switch alternatives of a run, each computed by the engine (both sides). */
    List<SimmPlaygroundBook.Alternative> alternatives(Principal p, SimmService.Run base, String version) {
        List<SimmPlaygroundBook.Alternative> out = new ArrayList<>();
        SimmChoices c = base.collect().choices();
        for (SimmChoices alt : c.alternatives()) {
            String name = "";
            for (SimmChoices.Switch s : SimmChoices.SWITCHES) {
                if (!alt.get(s.name()).equals(c.get(s.name()))) {
                    name = s.name();
                }
            }
            SimmService.Run r = svc.run(p, base.scope(), base.date(), base.knownAt(), version, alt, null);
            out.add(new SimmPlaygroundBook.Alternative(name, alt.get(name), r.imCollect(), r.imPost()));
        }
        return out;
    }

    @GetMapping("/api/v1/risk/simm/playground")
    public Map<String, Object> playground(@RequestParam(required = false) String nettingSet, @RequestParam(required = false) String scope,
            @RequestParam(required = false) String version, @RequestParam(required = false) String concentration, @RequestParam(required = false) String hvr,
            @RequestParam(required = false) List<String> override, @RequestParam(defaultValue = "collect") String side, @RequestParam(required = false) String compare,
            AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        SimmChoices c = choices(concentration, hvr, override);
        SimmService.Run run = run(p, nettingSet, scope, asOf, version, c);
        boolean post = "post".equalsIgnoreCase(side);
        Map<String, Object> m = SimmAnswer.result(run);
        m.put("side", post ? "post" : "collect");
        m.put("choices", SimmAnswer.choices(c, run.version().version(), svc.versions().stream().filter(SimmService.Version::ok).map(SimmService.Version::version).toList()));
        m.put("grid", SimmAnswer.grid(post ? run.post() : run.collect(), svc.props().maxFactors()));
        m.put("factors", run.collect().risks().size());
        m.put("maxFactors", svc.props().maxFactors());
        m.put("explainSteps", template.explain().stream().map(s -> Map.of("title", s[0], "text", s[1])).toList());
        if (compare != null && !compare.isBlank()) {
            int eq = compare.indexOf('=');
            if (eq <= 0) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "compare is switch=value (concentration=off, hvr=off, simm-parameters=<version>)");
            }
            String name = compare.substring(0, eq).strip();
            String value = compare.substring(eq + 1).strip();
            SimmService.Run b;
            String label;
            if (name.equals("simm-parameters")) {
                b = svc.run(p, run.scope(), run.date(), null, value, c, null);
                label = "SIMM " + run.version().version() + " → " + value;
            } else {
                SimmChoices alt;
                try {
                    alt = c.with(name, value);
                } catch (IllegalArgumentException e) {
                    throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
                }
                b = svc.run(p, run.scope(), run.date(), null, version, alt, null);
                label = name + ": " + c.get(name) + " → " + alt.get(name);
            }
            SimmCompare.Diff d = SimmCompare.compare(post ? run.post() : run.collect(), post ? b.post() : b.collect(), label);
            m.put("compare", SimmAnswer.diff(d));
            m.put("compareAnswer", SimmAnswer.result(b));
        }
        return m;
    }

    private ResponseEntity<StreamingResponseBody> xlsx(SimmService.Run run, List<SimmPlaygroundBook.Alternative> alts, Principal p, String side, String recipe,
            String name) {
        SimmPlaygroundBook book = new SimmPlaygroundBook(run, alts, template, true, side, p == null ? "" : p.user(), Instant.now().toString(), recipe);
        StreamingResponseBody body = book::write;
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"").header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    @GetMapping("/api/v1/risk/simm/playground.xlsx")
    public ResponseEntity<StreamingResponseBody> export(@RequestParam(required = false) String nettingSet, @RequestParam(required = false) String scope,
            @RequestParam(required = false) String version, @RequestParam(required = false) String concentration, @RequestParam(required = false) String hvr,
            @RequestParam(required = false) List<String> override, @RequestParam(defaultValue = "collect") String side,
            @RequestParam(defaultValue = "true") boolean saveRecipe, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        SimmService.Run run = run(p, nettingSet, scope, asOf, version, choices(concentration, hvr, override));
        int factors = run.collect().risks().size();
        if (factors > svc.props().maxFactors()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the netting set has " + factors + " factors, more than drishti.simm.max-factors ("
                    + svc.props().maxFactors() + ")");
        }
        String recipe = saveRecipe ? recipes.save(run, p).id() : null;
        String safe = run.scope().replaceAll("[^A-Za-z0-9_.~-]+", "-");
        return xlsx(run, alternatives(p, run, version), p, side, recipe, "simm-playground_" + safe + "_" + run.date() + "_" + run.version().version() + ".xlsx");
    }

    @GetMapping("/api/v1/risk/simm/playground/template")
    public ResponseEntity<StreamingResponseBody> template(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        svc.enabled();
        return xlsx(svc.template(), List.of(), p, "collect", null, "simm-playground-template.xlsx");
    }

    @PostMapping("/api/v1/risk/simm/playground/recipes")
    public Map<String, Object> save(@RequestParam(required = false) String nettingSet, @RequestParam(required = false) String scope,
            @RequestParam(required = false) String version, @RequestParam(required = false) String concentration, @RequestParam(required = false) String hvr,
            @RequestParam(required = false) List<String> override, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        SimmRecipes.Recipe r = recipes.save(run(p, nettingSet, scope, asOf, version, choices(concentration, hvr, override)), p);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("scope", r.scope());
        m.put("date", r.date());
        m.put("knownAt", r.knownAt());
        m.put("version", r.version());
        m.put("switches", r.switches());
        m.put("overrides", r.overrides());
        m.put("parametersHash", r.parametersHash());
        m.put("inputsDigest", r.inputsDigest());
        m.put("figures", r.figures());
        m.put("command", "PLAY SIMM RUN " + r.id());
        return m;
    }

    @GetMapping("/api/v1/risk/simm/playground/recipes/{id}")
    public Map<String, Object> replay(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        SimmRecipes.Replay rp = recipes.replay(id, p);
        Map<String, Object> m = SimmAnswer.result(rp.run());
        m.put("side", "collect");
        m.put("choices", SimmAnswer.choices(rp.run().collect().choices(), rp.run().version().version(), List.of(rp.recipe().version())));
        m.put("grid", SimmAnswer.grid(rp.run().collect(), svc.props().maxFactors()));
        m.put("recipe", Map.of("id", rp.recipe().id(), "created", rp.recipe().created(), "user", rp.recipe().user(), "knownAt",
                String.valueOf(rp.recipe().knownAt()), "inputsEqual", rp.inputsEqual(), "figuresEqual", rp.figuresEqual(), "differences", rp.differences()));
        m.put("reproduced", rp.figuresEqual() && rp.inputsEqual() ? "reproduced to the cent: the inputs and every figure equal the recipe's"
                : "not reproduced: " + String.join("; ", rp.differences()));
        return m;
    }
}
