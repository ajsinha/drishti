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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.banking.compute.var.Columns;
import com.ash.drishti.banking.compute.var.ScenarioFx;
import com.ash.drishti.banking.compute.var.VarBook;
import com.ash.drishti.banking.compute.var.VarCalc;
import com.ash.drishti.banking.compute.var.VarLens;
import com.ash.drishti.banking.compute.var.VarMath;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.server.playground.ExportSizing;
import com.ash.drishti.server.playground.PlaygroundTemplate;
import com.ash.drishti.server.security.Principal;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The VaR Playground (docs/guides/VAR_GUIDE.md): a scope's VaR worked out in a grid and in a workbook of real formulas. The
 * inputs are built as the person (their nodes, roles and masks, as every VaR answer) from the engine's own vectors on the date: each
 * position's scenario P&amp;L in the reporting currency as the engine holds it, its USD value today and the currency its PVs came in, and
 * the date's scenario FX; each position's scenario PVs are recovered from them ({@code PV(s) = (P&L_r(s) + p / FX_r(base)) × FX_r(s) /
 * FX_c(s)}, the same recovery as the per-currency totals, VAR_GUIDE.md section 15.11), so the workbook's chain of formulas gives back the
 * engine's vector. Positions beyond the count shown (the largest by |component VaR| are kept) are summed into one Rest of scope column,
 * so the sheet's portfolio is the engine's scope to the cent.
 */
final class VarPlayground {

    /** The browser's hard maximum: the grid never shows more positions than this, whatever the setting. */
    static final int BROWSER_MAX = 500;

    /**
     * The playground's settings ({@code drishti.var.playground.*}).
     *
     * @param maxPositions positions the screen shows as their own columns (at most {@link #BROWSER_MAX})
     * @param incremental positions that get incremental VaR (the largest by |component VaR|)
     * @param maxIds ids {@code PLAY VAR} takes
     * @param template a bank's template folder, blank for the built-in one
     * @param exportChoices the counts the export offers (0: all)
     * @param exportMaxCells the most cells one export may hold on this server
     */
    record Settings(boolean enabled, int maxPositions, int incremental, int maxIds, String template, List<Integer> exportChoices, long exportMaxCells,
            int exportDefault) {

        Settings {
            if (maxPositions > BROWSER_MAX) {
                throw new IllegalArgumentException("drishti.var.playground.max-positions is " + maxPositions + ": the browser playground shows at most "
                        + BROWSER_MAX + " positions (the rest are combined in Rest of scope)");
            }
            maxPositions = Math.max(1, maxPositions);
            incremental = Math.max(0, incremental);
            maxIds = Math.max(1, maxIds);
            exportChoices = List.copyOf(exportChoices);
        }
    }

    /** What is asked: a scope or ids, the date, the reporting currency and the lens. */
    record Ask(String scope, String ids, LocalDate date, String ccy, String horizon, String method, Double confidence, Double esConfidence, String set) {}

    /** One part before it is chosen: its index (position or trade ordinal) and |component VaR|. */
    private record Candidate(int index, double size) {}

    /** Everything one playground answer is made of. */
    record Built(VarPlaygroundModel model, VarPlaygroundBook.Engine engine, VarPlaygroundBook.About about, VarService.Scoped scoped, int total,
            double[] scope, List<double[]> parts) {}

    private final VarService svc;
    private final VarViews views;
    private final Settings settings;
    private final PlaygroundTemplate template;

    VarPlayground(VarService svc, VarViews views, Settings settings) {
        this.svc = svc;
        this.views = views;
        this.settings = settings;
        this.template = settings.template() == null || settings.template().isBlank() ? PlaygroundTemplate.builtIn("var", VarPlaygroundBook.STYLES)
                : PlaygroundTemplate.folder(Path.of(settings.template()), VarPlaygroundBook.STYLES);
    }

    Settings settings() {
        return settings;
    }

    PlaygroundTemplate template() {
        return template;
    }

    private void enabled() {
        if (!settings.enabled()) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "the VaR Playground is switched off on this server (drishti.var.playground.enabled)");
        }
    }

    /** The scope text an ask names: the scope, or the ids (each a position when the date has it, else a trade). */
    String scopeOf(Ask a, VarBook b) {
        if (a.ids() != null && !a.ids().isBlank()) {
            List<String> ids = java.util.Arrays.stream(a.ids().split("[,\\s]+")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
            if (ids.size() > settings.maxIds()) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, ids.size() + " ids: PLAY VAR takes at most " + settings.maxIds()
                        + " (drishti.var.playground.max-ids); give a scope for more");
            }
            List<String> pos = new ArrayList<>();
            List<String> trd = new ArrayList<>();
            for (String id : ids) {
                (b.positionIndexAnyCase(id) >= 0 ? pos : trd).add(id);
            }
            List<String> terms = new ArrayList<>();
            if (!pos.isEmpty()) {
                terms.add("POS:" + String.join(",", pos));
            }
            if (!trd.isEmpty()) {
                terms.add("TRD:" + String.join(",", trd));
            }
            return String.join(" + ", terms);
        }
        if (a.scope() == null || a.scope().isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "give a scope (scope=DESK:RATES, BANK, POS:<id>) or ids (ids=POS-1,T-1001)");
        }
        return a.scope().trim();
    }

    /** The lens's settings with the configured confidences, for the model and the named cells. */
    private VarPlaygroundModel.Settings modelSettings(VarLens lens) {
        VarProperties pr = svc.props();
        return new VarPlaygroundModel.Settings(lens.confidence(), lens.esConfidence(), pr.confidences(), pr.esConfidences(), pr.rule(),
                pr.componentWindow(), pr.componentScaleToVar(), lens.horizon(), lens.method() == VarLens.Method.SUM ? "SUM" : "SQRT10", settings.incremental());
    }

    private VarLens lens(Ask a) {
        try {
            VarLens l = svc.props().lens(a.horizon(), a.method(), a.confidence(), a.esConfidence(), a.set());
            return l.tenDay() ? l : new VarLens(1, VarLens.Method.NONE, l.confidence(), l.esConfidence(), l.stressed());
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Builds the playground of {@code a} with at most {@code count} positions as their own columns ({@code 0}: every position); the rest in
     * Rest of scope.
     */
    Built build(Principal p, Ask a, int count) {
        enabled();
        VarLens lens = lens(a);
        boolean stressed = lens.stressed();
        String ccy = stressed ? VarDate.USD : VarCurrencies.normalise(a.ccy());
        VarDate probe = svc.date(a.date());
        String scope = scopeOf(a, probe.book());
        VarLens oneDay = VarLens.oneDay(lens.confidence(), lens.esConfidence()).withStressed(stressed);
        VarService.Scoped s = svc.scoped(p, scope, a.date(), ccy, stressed ? oneDay : null);
        VarService.Scoped su = ccy.equals(VarDate.USD) ? s : svc.scoped(p, scope, a.date(), VarDate.USD, null);
        double[] ev = s.oneDay();
        VarBook b = s.date().book();
        VarBook bu = su.date().book();
        boolean byTrade = scope.toUpperCase(Locale.ROOT).startsWith("TRD:") && !scope.trim().contains(" ");
        if (!byTrade && !s.view().seesPositions(s.set())) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "your role sees this scope in aggregate only: the playground works position by position");
        }
        VarCalc calc = new VarCalc(lens.confidence(), lens.esConfidence(), svc.props().rule(), svc.props().componentWindow(),
                svc.props().componentScaleToVar());
        VarCalc.Measures em = calc.measures(ev);
        // every part, its |component VaR| (the engine's), the largest kept
        List<Candidate> all = new ArrayList<>();
        if (byTrade) {
            s.set().forEach(o -> all.add(new Candidate(o, Math.abs(VarMath.component(b.tradeVector(o), em.tail(), em.scale())))));
        } else {
            for (int[] run : VarPartTotals.positionRuns(b, s.set())) {
                all.add(new Candidate(run[0], Math.abs(VarMath.component(views.position(b, s.set(), run[0]).vector(), em.tail(), em.scale()))));
            }
        }
        all.sort(Comparator.comparingDouble((Candidate c) -> -c.size()).thenComparingInt(Candidate::index));
        int shown = count <= 0 ? all.size() : Math.min(count, all.size());
        if (shown > settings.maxPositions()) {                       // an export: refused before its positions are read when no application opens it
            ExportSizing.Estimate est = estimate(count, all.size(), ev.length, 2);
            if (est.refused() != null) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "the workbook of " + (count <= 0 ? "all " + all.size() : count) + " positions cannot be made: "
                        + est.refused());
            }
        }
        ScenarioFx fx = stressed ? new ScenarioFx(ev.length, List.of()) : svc.fx(su.date());
        ScenarioFx.Rates rep = fx.rates(ccy).orElseThrow(() -> new DrishtiException(ErrorCode.VAR_NO_FX, "no " + ccy + " scenario FX on " + s.date().date()));
        List<VarPlaygroundModel.Pos> positions = new ArrayList<>();
        List<double[]> engineParts = new ArrayList<>();
        TreeSet<String> ccys = new TreeSet<>();
        int[] forms = new int[3];
        for (int i = 0; i < shown; i++) {
            Candidate c = all.get(i);
            // the USD book's rows (what every currency's book is converted from), into the reporting currency as the engine converts them
            double[] usd = byTrade ? bu.tradeVector(c.index()) : views.position(bu, su.set(), c.index()).vector();
            VarPlaygroundModel.Pos pos = part(byTrade, c.index(), b, bu, su.set(), usd, fx, stressed);
            double[] r = usd;
            if (!ccy.equals(VarDate.USD)) {
                r = new double[usd.length];
                ScenarioFx.convert(pos.today(), usd, usd.length, rep, r);
            }
            engineParts.add(r);
            forms[pos.pv() ? (pos.currency().equals(VarDate.USD) && pos.source().startsWith("mixed") ? 2 : 0) : 1]++;
            ccys.add(pos.currency());
            positions.add(pos);
        }
        ccys.add(ccy);
        List<VarPlaygroundModel.Fx> fxs = new ArrayList<>();
        fxs.add(fxOf(fx, VarDate.USD, ev.length));
        for (String c : ccys) {
            if (!c.equals(VarDate.USD)) {
                fxs.add(fxOf(fx, c, ev.length));
            }
        }
        VarPlaygroundModel.Settings ms = modelSettings(lens);
        // Rest of scope: the engine's scope less the positions shown, as the sheet computes them. With every position shown it is still
        // kept when the scope's vector differs from its positions' stored rows (a node's figure is summed from the rows as read, in float64;
        // the rows are stored in float32 in the mapped tier): zero positions, the difference said in words, so the sheet's VaR is the screen's.
        VarPlaygroundModel shownOnly = new VarPlaygroundModel(s.date().book().scenarioDates(), positions, null, fxs, ccy, ms);
        double[] rest = new double[ev.length];
        double worst = 0;
        for (int j = 0; j < ev.length; j++) {
            double sum = 0;
            for (int i = 0; i < positions.size(); i++) {
                sum += shownOnly.pnl(i, j);
            }
            rest[j] = ev[j] - sum;
            worst = Math.max(worst, Math.abs(rest[j]));
        }
        if (shown == all.size() && worst < 1e-6) {
            rest = null;
        }
        VarPlaygroundModel model = new VarPlaygroundModel(s.date().book().scenarioDates(), positions, rest, fxs, ccy, ms);
        if (rest != null) {
            double[] er = ev.clone();
            for (double[] r : engineParts) {
                for (int j = 0; j < er.length; j++) {
                    er[j] -= r[j];
                }
            }
            engineParts.add(er);
        }
        VarPlaygroundBook.Engine engine = engine(ev, engineParts, calc, lens, Math.min(settings.incremental(), positions.size()), svc.props().confidences(),
                svc.props().esConfidences());
        Map<String, Object> prov = provenance(p, s, scope, lens, all.size(), shown, forms, fx);
        VarPlaygroundBook.About about = new VarPlaygroundBook.About(s.label() == null ? s.res().canonical() : s.label(), s.res().canonical(),
                s.res().canonical(), s.date().date().toString(), p == null ? "" : p.user(), java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                        .toString(), all.size(), all.size() - shown, prov);
        return new Built(model, engine, about, s, all.size(), ev, engineParts);
    }

    private static VarPlaygroundModel.Fx fxOf(ScenarioFx fx, String c, int width) {
        if (c.equals(VarDate.USD)) {
            double[] ones = new double[width];
            java.util.Arrays.fill(ones, 1.0);
            return new VarPlaygroundModel.Fx(VarDate.USD, 1.0, ones, "base");
        }
        ScenarioFx.Rates r = fx.rates(c).orElseThrow();
        return new VarPlaygroundModel.Fx(c, r.base(), r.scenarios().clone(), r.source());
    }

    /**
     * A part's Config and Data: its currency (the one its scenario PVs came in, when its trades agree and the date has its FX; else USD), its
     * USD value today {@code p}, and its scenario PVs recovered from its USD vector {@code usd} (the USD book's rows, as every currency's
     * book is converted from them): {@code PV(s) = (p + P&L_USD(s)) / FX_c(s)}; a part priced by P&amp;L vectors alone is input form PnL
     * (its USD P&amp;L).
     */
    private VarPlaygroundModel.Pos part(boolean byTrade, int index, VarBook b, VarBook bu, OrdinalSet setUsd, double[] usd, ScenarioFx fx,
            boolean stressed) {
        Columns tc = bu.trades();
        double[] pv = {0};
        TreeSet<String> pvCcy = new TreeSet<>();
        int[] pnlTrades = {0};
        java.util.function.IntConsumer each = o -> {
            double x = tc.number("PV", o);
            pv[0] += Double.isNaN(x) ? 0 : x;
            String[] cc = VarViews.tradeCurrency(tc, o);
            if ("scenario PVs".equals(cc[1])) {
                pvCcy.add(cc[0].toUpperCase(Locale.ROOT));
            } else {
                pnlTrades[0]++;
            }
        };
        String id;
        String kind;
        String label;
        if (byTrade) {
            each.accept(index);
            id = b.tradeId(index);
            kind = b.supplied(index) ? "position" : "trade";
            label = id;
        } else {
            bu.forEachPositionMemberIn(index, setUsd, each);
            id = b.positionId(index);
            kind = "position";
            String inst = b.positions().text("INSTRUMENT", index);
            label = inst == null ? id : inst;
        }
        double p = stressed ? 0 : pv[0];
        int n = usd.length;
        if (stressed || pvCcy.isEmpty()) {
            return new VarPlaygroundModel.Pos(id, kind, label, VarDate.USD, p, 1.0, "PnL", stressed ? "pnl (stress scenario set)" : "pnl-vector", usd);
        }
        String c = pvCcy.size() == 1 && pnlTrades[0] == 0 ? pvCcy.first() : VarDate.USD;
        ScenarioFx.Rates cr = fx.rates(c).orElse(null);
        if (cr == null) {
            c = VarDate.USD;
            cr = fx.rates(VarDate.USD).orElseThrow();
        }
        double[] data = new double[n];
        for (int j = 0; j < n; j++) {
            data[j] = (usd[j] + p) / cr.scenarios()[j];
        }
        String source = pvCcy.size() == 1 && pnlTrades[0] == 0 ? "scenario-pv" : "mixed (" + String.join(", ", pvCcy) + (pnlTrades[0] > 0 ? ", P&L vectors" : "")
                + "): in USD";
        return new VarPlaygroundModel.Pos(id, kind, label, c, p / cr.base(), cr.base(), "PV", source, data);
    }

    /** The engine's own figures for the same vectors (the check cells' right-hand side). */
    static VarPlaygroundBook.Engine engine(double[] ev, List<double[]> parts, VarCalc calc, VarLens lens, int inc, List<Double> confidences,
            List<Double> esConfidences) {
        VarCalc.Measures m = calc.measures(ev);
        List<VarCalc.Part> ps = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            ps.add(new VarCalc.Part("p" + i, "p" + i, "position", parts.get(i), Double.NaN));
        }
        List<VarCalc.Contribution> cs = calc.contributions(ev, m, ps);
        VarLens sum = new VarLens(10, VarLens.Method.SUM, lens.confidence(), lens.esConfidence());
        double[] t = sum.apply(ev);
        VarCalc.Measures m10 = sum.measures(calc, ev, t);
        List<VarCalc.Part> ps10 = new ArrayList<>();
        for (VarCalc.Part x : ps) {
            ps10.add(new VarCalc.Part(x.key(), x.label(), x.kind(), sum.apply(x.vector()), Double.NaN));
        }
        List<VarCalc.Contribution> cs10 = calc.contributions(t, m10, ps10);
        int n = parts.size();
        double[] cv = new double[n];
        double[] ce = new double[n];
        double[] c10 = new double[n];
        double[] incr = new double[n];
        double standalone = 0;
        for (int i = 0; i < n; i++) {
            cv[i] = cs.get(i).componentVar();
            ce[i] = cs.get(i).componentEs();
            c10[i] = cs10.get(i).componentVar();
            incr[i] = i < inc ? cs.get(i).incrementalVar() : Double.NaN;
            standalone += cs.get(i).standaloneVar();
        }
        VarMath.RankRule rule = calc.rule();
        List<double[]> conf = new ArrayList<>();
        for (double c : confidences) {
            double v = VarMath.var(ev, c, rule);
            conf.add(new double[] {c, v, v * VarLens.SQRT10, VarMath.var(t, c, rule)});
        }
        List<double[]> esc = new ArrayList<>();
        for (double c : esConfidences) {
            double e = VarMath.es(ev, c);
            esc.add(new double[] {c, e, e * VarLens.SQRT10, VarMath.es(t, c)});
        }
        return new VarPlaygroundBook.Engine(m.var(), m.es(), m.varScenario(), m10.var(), m10.es(), m.smoothed(), conf, esc, cv, ce, c10, incr,
                standalone - m.var());
    }

    private Map<String, Object> provenance(Principal p, VarService.Scoped s, String scope, VarLens lens, int total, int shown, int[] forms, ScenarioFx fx) {
        VarDate d = s.date();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Business date", d.date().toString());
        m.put("Scope asked", scope);
        m.put("Scope (canonical)", s.res().canonical());
        m.put("Positions in the scope", total);
        m.put("Positions shown", shown + (total > shown ? " (the largest by |component VaR|; the other " + (total - shown) + " in Rest of scope)" : ""));
        m.put("Reporting currency", d.currency());
        m.put("Lens", lens.describe(s.oneDay().length));
        m.put("Scenario set", d.book().scenarioSet() + " · " + d.book().scenarioDates().get(0) + " to " + d.book().scenarioDates().get(d.book().scenarioDates().size() - 1));
        m.put("Scenario window", views.scenarioWindow(d).get("note"));
        m.put("Input forms", forms[0] + " PV (scenario PVs), " + forms[1] + " PnL (P&L vectors), " + forms[2] + " mixed (shown in USD)");
        m.put("Scenario PVs", "recovered from the engine's USD rows: PV(s) = (p + P&L_USD(s)) / FX_c(s), p the USD value today (every reporting currency is converted from these rows)");
        List<String> fxl = new ArrayList<>();
        for (String c : fx.currencies()) {
            fx.rates(c).ifPresent(r -> fxl.add(c + ": " + r.source() + ", today " + r.base()));
        }
        m.put("FX", fxl);
        d.inputs().forEach((k, v) -> m.put("Data version: " + k, String.valueOf(v)));
        List<String> refused = d.intake() == null ? List.of() : d.intake().rejections().stream().limit(50).toList();
        m.put("Refused on the date (with DRS codes)", refused);
        m.put("Engine", "drishti-compute " + VarViews.ENGINE_VERSION);
        m.put("Template", template.name() + " " + template.version() + " (" + template.origin() + ")");
        m.put("Exported by", p == null ? "" : p.user());
        m.put("Exported at", java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
        m.put("Settings", "max-positions " + settings.maxPositions() + ", incremental-positions " + settings.incremental() + ", rank-rule "
                + svc.props().rankRule() + ", component-window " + svc.props().componentWindow() + ", component-scale-to-var " + svc.props().componentScaleToVar());
        return m;
    }

    /**
     * The playground's Parametric tab (docs/guides/VAR_GUIDE.md section 10): the parametric figures of what was built, the 1-day portfolio and its positions (Rest of
     * scope included), with the server's parametric settings; refused in words while the measure is off.
     */
    VarPlaygroundParametric.Engine parametric(Built b) {
        VarParametric par = views.parametric();
        if (par == null || !par.enabled()) {
            throw new com.ash.drishti.common.DrishtiException(com.ash.drishti.common.ErrorCode.FORBIDDEN,
                    "parametric VaR is switched off on this server (drishti.var.parametric.enabled): the playground's Parametric tab is not offered");
        }
        return VarPlaygroundParametric.engine(b.scope(), b.parts(), b.model().set.confidence(), b.model().set.esConfidence(), par.props().settings());
    }

    VarParametric describer() {
        return views.parametric();
    }

    /** Writes the workbook of {@code a} with {@code count} positions ({@code 0}: all), refusing a size no application opens. */
    void export(Principal p, Ask a, int count, OutputStream out) {
        Built b = build(p, a, count);
        new VarPlaygroundBook(b.model(), b.engine(), b.about(), template, true).write(out);
    }

    /** The estimate of an export of {@code count} positions of a scope of {@code total} over {@code scenarios} scenarios. */
    ExportSizing.Estimate estimate(int count, int total, int scenarios, int currencies) {
        int shown = count <= 0 ? total : Math.min(count, total);
        return VarPlaygroundBook.estimate(shown, shown < total, scenarios, currencies, settings.incremental(), svc.props().confidences().size()
                + svc.props().esConfidences().size(), settings.exportMaxCells());
    }

    /** The export's choices for a scope of {@code total} positions, each with its estimate (sizes, time, applications, or why not). */
    List<Map<String, Object>> choices(int total, int scenarios, int currencies) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int c : settings.exportChoices()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", c);
            m.put("label", c == 0 ? "All (" + String.format(Locale.ROOT, "%,d", total) + ")" : String.format(Locale.ROOT, "%,d", c));
            m.put("default", c == settings.exportDefault());
            m.putAll(estimate(c, total, scenarios, currencies).describe());
            out.add(m);
        }
        return out;
    }
}
