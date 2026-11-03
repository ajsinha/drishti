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
package com.ash.drishti.server.saccr;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.saccr.Saccr;
import com.ash.drishti.compute.saccr.SaccrInput;
import com.ash.drishti.compute.saccr.SaccrRows;
import com.ash.drishti.server.playground.PlaygroundTemplate;
import com.ash.drishti.server.security.Principal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * SA-CCR (docs/guides/SACCR_GUIDE.md section 9), as the person:
 * <pre>
 *   GET  /api/v1/saccr/scope?scope=&amp;nettingSet=&amp;irBucketOffset=&amp;cap=&amp;strict=   the SA-CCR page's one answer (views A, B and C)
 *   GET  /api/v1/saccr/scope/contributions?scope=&amp;by=netting-set|asset-class|hedging-set|trade   rows of a scope by part
 *   GET  /api/v1/saccr/trade/{tradeId}                    a trade's terms (SD, δ, MF, D, bucket) and its standalone EAD
 *   POST /api/v1/saccr/what-if                            incremental EAD of candidate rows in a netting set (stores nothing)
 *   GET  /api/v1/saccr/parameters                         the regulation's parameter file: every value with its CRE52 paragraph
 *   GET  /api/v1/saccr/playground?scope=                  PLAY SACCR: the grid of a set's trades and hedging sets, the explanation
 *   GET  /api/v1/saccr/playground.xlsx?scope=             the workbook of live formulas (Main, Config, Data, Trades, Hedging sets)
 *   GET  /api/v1/saccr/playground/template                the shipped workbook: the hand-built cases
 * </pre>
 * Scopes: {@code NSET:<id>} (or the bare id), {@code CPTY:<id>}, {@code LE:<id>}, {@code BANK}, {@code EXAMPLE} (the hand-built cases).
 */
@RestController
public class SaccrController {

    private final SaccrService svc;
    private final PlaygroundTemplate template;

    public SaccrController(SaccrService svc) {
        this.svc = svc;
        String t = svc.props().playgroundTemplate();
        this.template = t.isBlank() ? PlaygroundTemplate.builtIn("saccr", SaccrPlaygroundBook.STYLES) : PlaygroundTemplate.folder(Path.of(t), SaccrPlaygroundBook.STYLES);
    }

    static Saccr.Choices choices(Boolean irBucketOffset, Boolean cap) {
        return new Saccr.Choices(irBucketOffset == null || irBucketOffset, cap == null || cap);
    }

    private static LocalDate date(AsOf a) {
        return a == null ? null : a.businessDate();
    }

    @GetMapping("/api/v1/saccr/scope")
    public Map<String, Object> scope(@RequestParam(required = false) String scope, @RequestParam(required = false) String nettingSet,
            @RequestParam(required = false) Boolean irBucketOffset, @RequestParam(required = false) Boolean cap, @RequestParam(defaultValue = "false") boolean strict,
            AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        String s = scope == null || scope.isBlank() ? nettingSet : scope;
        SaccrService.Run run = svc.run(p, s, date(asOf), choices(irBucketOffset, cap), strict);
        return SaccrAnswer.scope(run, nettingSet, svc);
    }

    @GetMapping("/api/v1/saccr/scope/contributions")
    public Map<String, Object> contributions(@RequestParam String scope, @RequestParam(defaultValue = "netting-set") String by,
            @RequestParam(required = false) Boolean irBucketOffset, @RequestParam(required = false) Boolean cap, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Saccr.Choices ch = choices(irBucketOffset, cap);
        SaccrService.Run run = svc.run(p, scope, date(asOf), ch, false);
        List<Map<String, Object>> rows = new ArrayList<>();
        String mode = by.strip().toLowerCase(Locale.ROOT);
        for (SaccrService.SetRun s : run.sets()) {
            Saccr.Result r = s.result();
            switch (mode) {
                case "netting-set" -> rows.add(row(s.id(), s.id(), "EAD", s.ead()));
                case "asset-class" -> {
                    if (r != null) {
                        r.binding().addOns().forEach((ac, a) -> {
                            if (a > 0) {
                                rows.add(row(ac, s.id(), "addOn", a));
                            }
                        });
                    }
                }
                case "hedging-set" -> {
                    if (r != null) {
                        boolean marg = r.margined() != null && !r.capped();
                        r.hedgingSets().forEach(h -> rows.add(row(h.assetClass() + "/" + h.hedgingSet(), s.id(), "addOn", marg ? h.addOnMargined() : h.addOnUnmargined())));
                    }
                }
                case "trade" -> {
                    if (r != null) {
                        boolean marg = r.margined() != null && !r.capped();
                        for (Saccr.Terms t : r.terms()) {
                            Map<String, Object> x = row(t.trade().tradeId() + "~" + t.trade().driver(), s.id(), "d", marg ? t.dMargined() : t.dUnmargined());
                            x.put("standaloneEad", svc.standalone(t.trade(), ch));
                            rows.add(x);
                        }
                    }
                }
                default -> throw new DrishtiException(ErrorCode.SACCR_BAD_REQUEST, "DRS-5410 by is netting-set, asset-class, hedging-set or trade, not " + by);
            }
        }
        rows.sort(Comparator.comparingDouble((Map<String, Object> x) -> -Math.abs(((Number) x.get("value")).doubleValue())));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", run.scope());
        m.put("businessDate", run.date() == null ? null : run.date().toString());
        m.put("by", mode);
        m.put("EAD", run.ead());
        m.put("rows", rows);
        m.put("note", mode.equals("netting-set") ? "EAD adds over netting sets: the rows sum to the scope's EAD"
                : "parts below a netting set do not add up to its EAD (square roots, absolute values, the multiplier): each row is the part's own figure");
        return m;
    }

    private static Map<String, Object> row(String key, String set, String measure, double v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("nettingSet", set);
        m.put("measure", measure);
        m.put("value", v);
        return m;
    }

    @GetMapping("/api/v1/saccr/trade/{tradeId}")
    public Map<String, Object> trade(@PathVariable String tradeId, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        svc.enabled();
        SaccrData.Day day = svc.data().day(new AsOf(date(asOf), null));
        SaccrService.Located at = svc.locate(day, tradeId.strip());
        if (at == null || at.set() == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no SA-CCR classification row for trade " + tradeId + " on " + day.date());
        }
        SaccrService.Run run = svc.run(p, "NSET:" + at.set().id(), day.date(), Saccr.Choices.REGULATORY, false);
        List<Map<String, Object>> drivers = new ArrayList<>();
        for (SaccrService.SetRun s : run.sets()) {
            if (s.result() == null) {
                continue;
            }
            for (Saccr.Terms t : s.result().terms()) {
                if (t.trade().tradeId().equals(tradeId.strip())) {
                    Map<String, Object> x = SaccrAnswer.terms(t, t.trade().driver() == 0 ? svc.standalone(t.trade(), Saccr.Choices.REGULATORY) : Double.NaN);
                    x.put("row", t.trade().attributes());
                    drivers.add(x);
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tradeId", tradeId.strip());
        m.put("nettingSet", at.set().id());
        m.put("businessDate", String.valueOf(day.date()));
        m.put("drivers", drivers);
        m.put("note", "standalone EAD: the trade as its own unmargined netting set (V its MTM, no collateral); never a share of its set's EAD");
        return m;
    }

    /**
     * Incremental EAD: {@code {nettingSet, candidates: [saccr-trade row + MTM], remove: [tradeId]}}; the set before and after, its hedging
     * sets' add-ons before and after. Stores nothing.
     */
    @PostMapping("/api/v1/saccr/what-if")
    public Map<String, Object> whatIf(@RequestBody Map<String, Object> body, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Object ns = body.get("nettingSet");
        if (ns == null) {
            throw new DrishtiException(ErrorCode.SACCR_BAD_REQUEST, "DRS-5410 give nettingSet and candidates: [{saccr-trade row, MTM}]");
        }
        SaccrService.Run run = svc.run(p, "NSET:" + ns, date(asOf), Saccr.Choices.REGULATORY, false);
        SaccrService.SetRun before = run.sets().get(0);
        Saccr.Result b = before.result();
        if (b == null) {
            throw new DrishtiException(ErrorCode.SACCR_BAD_REQUEST, "DRS-5410 " + ns + " has no derivative: a what-if needs a set SA-CCR measures");
        }
        List<String> remove = new ArrayList<>();
        if (body.get("remove") instanceof List<?> l) {
            l.forEach(x -> remove.add(String.valueOf(x)));
        }
        List<SaccrInput.Trade> trades = new ArrayList<>(b.terms().stream().map(Saccr.Terms::trade).filter(t -> !remove.contains(t.tradeId())).toList());
        double v = b.v();
        for (Saccr.Terms t : b.terms()) {
            if (remove.contains(t.trade().tradeId()) && t.trade().driver() == 0 && Double.isFinite(t.trade().mtm())) {
                v -= t.trade().mtm();
            }
        }
        int i = 0;
        if (body.get("candidates") instanceof List<?> cands) {
            for (Object o : cands) {
                if (!(o instanceof Map<?, ?> raw)) {
                    throw new DrishtiException(ErrorCode.SACCR_BAD_REQUEST, "DRS-5410 a candidate is a saccr-trade row (an object)");
                }
                Map<String, Object> row = new LinkedHashMap<>();
                raw.forEach((k, x) -> row.put(String.valueOf(k), x));
                row.putIfAbsent("TRADE_ID", "CANDIDATE-" + (++i));
                double mtm = row.get("MTM") instanceof Number n ? n.doubleValue() : 0;
                SaccrInput.Trade t = SaccrRows.trade(row, run.date(), mtm);
                String why = SaccrRows.computedField(row) != null ? "DRS-5410 a candidate carries the computed field " + SaccrRows.computedField(row)
                        : Saccr.run(new SaccrInput(SaccrInput.NettingSet.unmargined("CHECK", 0, 0), List.of(t)), svc.params()).refusals().stream().findFirst()
                                .map(Saccr.Refusal::reason).orElse(null);
                if (why != null) {
                    throw new DrishtiException(ErrorCode.SACCR_BAD_REQUEST, why + " (candidate " + row.get("TRADE_ID") + ")");
                }
                trades.add(t);
                if (t.driver() == 0) {
                    v += mtm;
                }
            }
        }
        Saccr.Result a = Saccr.run(new SaccrInput(b.set().withV(v), trades), svc.params());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nettingSet", String.valueOf(ns));
        m.put("businessDate", String.valueOf(run.date()));
        m.put("eadBefore", b.ead());
        m.put("eadAfter", a.ead());
        m.put("incrementalEad", a.ead() - b.ead());
        m.put("rcBefore", b.rc());
        m.put("rcAfter", a.rc());
        m.put("pfeBefore", b.pfe());
        m.put("pfeAfter", a.pfe());
        m.put("multiplierBefore", b.multiplier());
        m.put("multiplierAfter", a.multiplier());
        List<Map<String, Object>> hs = new ArrayList<>();
        Map<String, double[]> by = new LinkedHashMap<>();
        boolean mb = b.margined() != null && !b.capped();
        boolean ma = a.margined() != null && !a.capped();
        b.hedgingSets().forEach(h -> by.computeIfAbsent(h.assetClass() + "|" + h.hedgingSet(), k -> new double[2])[0] = mb ? h.addOnMargined() : h.addOnUnmargined());
        a.hedgingSets().forEach(h -> by.computeIfAbsent(h.assetClass() + "|" + h.hedgingSet(), k -> new double[2])[1] = ma ? h.addOnMargined() : h.addOnUnmargined());
        by.forEach((k, x) -> {
            String[] parts = k.split("\\|", 2);
            hs.add(Map.of("assetClass", parts[0], "hedgingSet", parts[1], "addOnBefore", x[0], "addOnAfter", x[1]));
        });
        m.put("hedgingSets", hs);
        m.put("note", "incremental EAD = EAD after − EAD before, the set recomputed with the candidates (V moved by their MTM); nothing is stored");
        return m;
    }

    @GetMapping("/api/v1/saccr/parameters")
    public Map<String, Object> parameters() {
        svc.enabled();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("regulation", svc.params().regulation);
        m.put("version", svc.params().version);
        m.put("source", svc.params().source);
        m.put("file", svc.paramsFile());
        m.put("sha256", svc.params().hash);
        List<Map<String, Object>> rows = new ArrayList<>();
        svc.params().values().forEach((k, v) -> rows.add(Map.of("key", k, "value", v, "ref", svc.params().ref(k))));
        m.put("values", rows);
        return m;
    }

    @GetMapping("/api/v1/saccr/playground")
    public Map<String, Object> playground(@RequestParam(required = false) String scope, @RequestParam(required = false) String nettingSet,
            @RequestParam(required = false) Boolean irBucketOffset, @RequestParam(required = false) Boolean cap, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        String s = scope == null || scope.isBlank() ? nettingSet : scope;
        SaccrService.Run run = svc.run(p, s, date(asOf), choices(irBucketOffset, cap), false);
        check(run);
        Map<String, Object> m = SaccrAnswer.scope(run, nettingSet, svc);
        List<Map<String, Object>> grid = new ArrayList<>();
        int shown = 0;
        int total = 0;
        for (SaccrService.SetRun x : run.sets()) {
            if (x.result() == null) {
                continue;
            }
            for (Saccr.Terms t : x.result().terms()) {
                total++;
                if (shown < svc.props().browserTrades()) {
                    Map<String, Object> r = SaccrAnswer.terms(t, Double.NaN);
                    r.put("nettingSet", x.id());
                    grid.add(r);
                    shown++;
                }
            }
        }
        m.put("grid", grid);
        m.put("gridRows", total);
        m.put("gridShown", shown);
        m.put("explainSteps", template.explain().stream().map(e -> Map.of("title", e[0], "text", e[1])).toList());
        return m;
    }

    private void check(SaccrService.Run run) {
        if (run.sets().size() > svc.props().maxSets()) {
            throw new DrishtiException(ErrorCode.SACCR_BAD_REQUEST, "DRS-5410 the playground holds up to " + svc.props().maxSets()
                    + " netting sets side by side (drishti.ccr.saccr.max-sets); " + run.scope() + " has " + run.sets().size());
        }
        int rows = run.sets().stream().mapToInt(s -> s.result() == null ? 0 : s.result().terms().size()).sum();
        if (rows > svc.props().maxTrades()) {
            throw new DrishtiException(ErrorCode.SACCR_BAD_REQUEST, "DRS-5410 the playground holds up to " + svc.props().maxTrades()
                    + " classification rows (drishti.ccr.saccr.max-trades); " + run.scope() + " has " + rows);
        }
    }

    private ResponseEntity<StreamingResponseBody> xlsx(SaccrService.Run run, Principal p, String name) {
        SaccrPlaygroundBook book = new SaccrPlaygroundBook(run, svc, template, true, p == null ? "" : p.user(), Instant.now().toString());
        StreamingResponseBody body = book::write;
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"").header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    @GetMapping("/api/v1/saccr/playground.xlsx")
    public ResponseEntity<StreamingResponseBody> export(@RequestParam(required = false) String scope, @RequestParam(required = false) String nettingSet,
            @RequestParam(required = false) Boolean irBucketOffset, @RequestParam(required = false) Boolean cap, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        String s = scope == null || scope.isBlank() ? nettingSet : scope;
        SaccrService.Run run = svc.run(p, s, date(asOf), choices(irBucketOffset, cap), false);
        check(run);
        String safe = run.scope().replaceAll("[^A-Za-z0-9_.~-]+", "-");
        return xlsx(run, p, "saccr-playground_" + safe + "_" + (run.date() == null ? "examples" : run.date()) + ".xlsx");
    }

    @GetMapping("/api/v1/saccr/playground/template")
    public ResponseEntity<StreamingResponseBody> template(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        SaccrService.Run run = svc.run(p, SaccrService.EXAMPLE, null, Saccr.Choices.REGULATORY, false);
        return xlsx(run, p, "saccr-playground-template.xlsx");
    }
}
