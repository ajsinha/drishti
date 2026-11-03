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
import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.ccr.ExposureBook;
import com.ash.drishti.compute.ccr.ExposureCalc;
import com.ash.drishti.compute.ccr.ExposureMath;
import com.ash.drishti.compute.ccr.FvaMath;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * FVA of a scope (docs/guides/FVA_GUIDE.md): the exposure engine answers the scope as the caller ({@link ExposureService#scoped}), and each of
 * its netting sets' EE and ENE profile (after the CSA, decision 13) is read from what that answer already holds
 * ({@link ExposureCalc#profile}); {@link FvaMath} prices it with the funding spread, the discount curve, the survival of both sides when
 * asked and the choices. The scope's figures are the sum of its sets' (FVA adds). The curves are read from the curve kinds of the date (the
 * market-data pack's {@code ir-curve} and {@code credit-curve}); a funding curve named in the configuration when the kind has none of that
 * id; the flat settings otherwise, said in the answer's flags.
 */
final class FvaService {

    /** The choices a request may change (the playground's Choices panel); null: the setting. */
    record Choices(String eePoint, Boolean fba, Boolean fundingSurvival, String fundingSpread) {
        static Choices none() {
            return new Choices(null, null, null, null);
        }
    }

    /**
     * One netting set's FVA.
     *
     * @param survival S_C(t) · S_B(t) at each primary date (the counterparty's and the bank's), filled when survival weighting is on or the
     *     playground asks; else null
     * @param survivalSource where both survival curves came from
     * @param paths the set's exposure and negative exposure per path, {@code {E, N}} each {@code G_primary x P}, for the playground's paths
     *     mode; else null
     */
    record SetFva(String id, String counterparty, String name, String csa, boolean whole, double ce, double nce, FvaMath.Result result,
            double[] survival, String survivalSource, double[][] paths) {}

    /** A scope's FVA: the exposure answer it reads, each set's and the sum, the curves and settings used, the flags. */
    record Answer(ExposureService.Scoped scoped, List<SetFva> sets, FvaMath.Result total, FvaMath.Curve borrow, FvaMath.Curve lend,
            FvaMath.Curve discount, FvaMath.Point point, boolean fba, boolean survivalOn, String calibration, String valueBasis, List<String> flags,
            boolean setsShown) {}

    /** What the playground needs beyond the figures: every set's survival (to flip the switch in Config) and, in the paths mode, its paths. */
    record Extras(boolean survival, boolean paths) {
        static final Extras NONE = new Extras(false, false);
    }

    private final ExposureService exposure;
    private final FvaProperties props;
    private final com.github.benmanes.caffeine.cache.Cache<String, Map<String, DataNode>> docs =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(60).expireAfterWrite(java.time.Duration.ofMinutes(5)).build();
    private volatile BooleanSupplier running;

    FvaService(ExposureService exposure, FvaProperties props) {
        this.exposure = exposure;
        this.props = props;
    }

    FvaProperties props() {
        return props;
    }

    ExposureService exposure() {
        return exposure;
    }

    /** The engine registry's word on whether FVA runs here; unset (a test of the service alone): on. */
    void running(BooleanSupplier on) {
        this.running = on;
    }

    boolean running() {
        BooleanSupplier r = running;
        return r == null || r.getAsBoolean();
    }

    void enabled() {
        if (!running()) {
            throw new com.ash.drishti.server.engines.EngineOffException("fva", "FVA");
        }
        if (!exposure.running()) {
            throw new com.ash.drishti.server.engines.EngineOffException("exposure", "Exposure");
        }
    }

    /** Releases what was read (the engine turned off). */
    void clear() {
        docs.invalidateAll();
    }

    /**
     * The run's calibration against the setting (decision 10): a run that is not {@code RISK_NEUTRAL} is refused with DRS-5447 when
     * {@code require-risk-neutral} is on, else priced and flagged {@code REAL_WORLD_RUN}.
     */
    static void checkRun(String calibration, boolean requireRiskNeutral, String runId, LocalDate date, List<String> flags) {
        if ("RISK_NEUTRAL".equalsIgnoreCase(calibration)) {
            return;
        }
        if (requireRiskNeutral) {
            throw new DrishtiException(ErrorCode.FVA_REFUSED, "FVA is a price and needs a risk-neutral run: the run " + runId + " of " + date + " is "
                    + (calibration == null || calibration.isEmpty() ? "of no stated calibration" : calibration)
                    + " (drishti.ccr.fva.require-risk-neutral is true)");
        }
        flags.add("REAL_WORLD_RUN");
    }

    /** FVA of a scope as the caller, on the request's business date (the exposure engine's newest with paths when later). */
    Answer answer(Principal p, String scope, LocalDate asked, Choices ch, String run, Boolean strict) {
        return answer(p, scope, asked, ch, run, strict, Extras.NONE);
    }

    Answer answer(Principal p, String scope, LocalDate asked, Choices ch, String run, Boolean strict, Extras extras) {
        enabled();
        ExposureService.Ask ask = ExposureService.Ask.of(exposure.props(), null, null, run, strict);
        return price(p, exposure.scoped(p, scope, asked, ask), ch, extras);
    }

    /** Prices an exposure answer already made (the official capture prices the scopes it captures, as the server). */
    Answer price(Principal p, ExposureService.Scoped s, Choices ch, Extras extras) {
        ExposureBook b = s.date().book();
        List<String> flags = new ArrayList<>();
        Map<String, Object> runRec = b.run();
        String calibration = text(runRec.get("CALIBRATION"));
        String valueBasis = text(runRec.get("VALUE_BASIS"));
        checkRun(calibration, props.requireRiskNeutral(), b.runId(), b.businessDate(), flags);
        FvaMath.Point point = ch.eePoint() == null || ch.eePoint().isBlank() ? props.point() : point(ch.eePoint());
        boolean fba = ch.fba() == null ? props.fba() : ch.fba();
        boolean survivalOn = ch.fundingSurvival() == null ? props.fundingSurvival() : ch.fundingSurvival();
        LocalDate day = b.businessDate();
        String spreadAsked = ch.fundingSpread() == null || ch.fundingSpread().isBlank() ? props.fundingSpread() : ch.fundingSpread().strip();
        FvaMath.Curve borrow = spread(day, spreadAsked, flags);
        FvaMath.Curve lend = props.lendingSpread().isEmpty() ? borrow : spread(day, props.lendingSpread(), flags);
        boolean discounted = "DISCOUNTED".equalsIgnoreCase(valueBasis);
        FvaMath.Curve discount = discounted ? null : discount(day, flags);
        if (discounted) {
            flags.add("PATHS_DISCOUNTED");
        }
        double[] t = ExposureCalc.times(b.grid());
        FvaMath.Market base = FvaMath.market(t, discount, borrow, lend, null);
        boolean wantSurvival = survivalOn || extras.survival();
        List<String> survFlags = survivalOn ? flags : new ArrayList<>();        // the playground's extra curves flag nothing the answer did not use
        FvaMath.Curve own = wantSurvival ? ownSurvival(day, survFlags) : null;
        ExposureCalc.Result r = s.result();
        List<SetFva> sets = new ArrayList<>();
        List<FvaMath.Result> parts = new ArrayList<>();
        boolean shown = true;
        for (ExposureCalc.Piece pc : r.pieces) {
            ExposureBook.SetInfo si = b.set(pc.set);
            double[] surv = null;
            String survSource = null;
            if (wantSurvival) {
                FvaMath.Curve cp = counterpartySurvival(day, si.counterparty(), survFlags);
                surv = new double[t.length];
                for (int i = 0; i < t.length; i++) {
                    surv[i] = cp.logLinear(t[i]) * own.logLinear(t[i]);
                }
                survSource = cp.id() + " (" + cp.source() + ") × " + own.id() + " (" + own.source() + ")";
            }
            FvaMath.Market market = survivalOn ? new FvaMath.Market(base.df(), base.sb(), base.sl(), surv) : base;
            double[][] prof = ExposureCalc.profile(r, pc);
            double nce = ExposureMath.currentNegative(pc.v0, pc.csa, r.params.model());
            FvaMath.Result fr = FvaMath.compute(new FvaMath.Profile(t, prof[0], prof[1], pc.ce, nce), market, point, fba);
            parts.add(fr);
            if (!s.view().detailsSet(pc.set)) {
                shown = false;
            }
            sets.add(new SetFva(si.id(), si.counterparty(), exposure.name(s.date(), si.counterparty(), p), ExposureExplorer.csaLabel(pc.csa), pc.whole,
                    pc.ce, nce, fr, surv, survSource, extras.paths() ? ExposureCalc.matrices(r, pc) : null));
        }
        if (!shown) {
            flags.add("SETS_AGGREGATE");
        }
        for (String f : r.flags) {
            if (!flags.contains(f)) {
                flags.add(f);
            }
        }
        sets.sort((x, y) -> Double.compare(x.result().fva(), y.result().fva()));
        return new Answer(s, shown ? List.copyOf(sets) : List.of(), FvaMath.sum(parts), borrow, lend, discount, point, fba, survivalOn, calibration,
                valueBasis, List.copyOf(flags), shown);
    }

    private static FvaMath.Point point(String s) {
        try {
            return FvaMath.Point.of(s);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, e.getMessage());
        }
    }

    /**
     * The counterparty's survival S_C(t): the credit curve its record names ({@code CREDIT_CURVE}; points {@code TENOR} and {@code SURVIVAL}
     * of the credit-curve kind), log-linear; no curve: the flat {@code counterparty-hazard}, flagged {@code FLAT_HAZARD}.
     */
    FvaMath.Curve counterpartySurvival(LocalDate day, String counterparty, List<String> flags) {
        DataNode cp = counterparty == null ? null : kind(day, exposure.props().kinds().counterparty(), "COUNTERPARTY_ID").get(counterparty);
        String id = cp == null ? "" : cp.get("CREDIT_CURVE").asText();
        if (!id.isBlank()) {
            DataNode doc = kind(day, props.creditCurveKind(), "CURVE_ID").get(id);
            FvaMath.Curve c = doc == null ? null : points(id, doc, "SURVIVAL", props.creditCurveKind() + " " + id + " of " + day);
            if (c != null) {
                return c;
            }
        }
        if (!flags.contains("FLAT_HAZARD")) {
            flags.add("FLAT_HAZARD");
        }
        return flatSurvival(props.counterpartyHazard(), "drishti.ccr.fva.counterparty-hazard (" + (id.isBlank() ? "no CREDIT_CURVE on " + counterparty
                : "no curve " + id) + ")");
    }

    /** The bank's own survival S_B(t): the credit curve {@code own-curve}, else the flat {@code own-hazard} (flagged when a curve was named). */
    FvaMath.Curve ownSurvival(LocalDate day, List<String> flags) {
        if (!props.ownCurve().isBlank()) {
            DataNode doc = kind(day, props.creditCurveKind(), "CURVE_ID").get(props.ownCurve());
            FvaMath.Curve c = doc == null ? null : points(props.ownCurve(), doc, "SURVIVAL", props.creditCurveKind() + " " + props.ownCurve() + " of " + day);
            if (c != null) {
                return c;
            }
            if (!flags.contains("FLAT_HAZARD")) {
                flags.add("FLAT_HAZARD");
            }
        }
        return flatSurvival(props.ownHazard(), "drishti.ccr.fva.own-hazard");
    }

    private static FvaMath.Curve flatSurvival(double hazard, String source) {
        return new FvaMath.Curve("flat " + hazard, List.of("1Y"), new double[] {1.0}, new double[] {Math.exp(-hazard)}, source);
    }

    /** A spread: a number (flat), a curve of the curve kind, a curve of the configuration, else the fallback (flagged). */
    FvaMath.Curve spread(LocalDate day, String spec, List<String> flags) {
        Double flat = FvaProperties.flat(spec);
        if (flat != null) {
            if (!(flat > -0.05 && flat < 0.5)) {
                throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "a funding spread is a fraction (0.005 is 50 bp), not " + spec);
            }
            return FvaMath.Curve.flat("flat " + spec, flat, "the setting");
        }
        DataNode doc = kind(day, props.curveKind(), "CURVE_ID").get(spec);
        if (doc != null) {
            FvaMath.Curve c = points(spec, doc, "SPREAD", props.curveKind() + " " + spec + " of " + day);
            if (c != null) {
                return c;
            }
        }
        for (FvaProperties.Curve c : props.curves()) {
            if (c.id().equalsIgnoreCase(spec) && !c.points().isEmpty()) {
                List<String> tenors = new ArrayList<>();
                double[][] tv = new double[2][c.points().size()];
                int i = 0;
                for (FvaProperties.Point pt : sortedPoints(c.points())) {
                    tenors.add(pt.tenor());
                    tv[0][i] = FvaMath.tenorYears(pt.tenor());
                    tv[1][i++] = pt.spread() == null ? 0 : pt.spread();
                }
                return new FvaMath.Curve(c.id(), tenors, tv[0], tv[1], "drishti.ccr.fva.curves" + (c.label().isEmpty() ? "" : " (" + c.label() + ")"));
            }
        }
        if (!flags.contains("FUNDING_FALLBACK")) {
            flags.add("FUNDING_FALLBACK");
        }
        return FvaMath.Curve.flat("flat " + props.fallbackSpread(), props.fallbackSpread(), "drishti.ccr.fva.fallback-spread (no curve " + spec + ")");
    }

    private static List<FvaProperties.Point> sortedPoints(List<FvaProperties.Point> pts) {
        List<FvaProperties.Point> out = new ArrayList<>(pts);
        out.sort((a, b) -> Double.compare(FvaMath.tenorYears(a.tenor()), FvaMath.tenorYears(b.tenor())));
        return out;
    }

    /** The discount curve of the date, else the flat rate (flagged). */
    FvaMath.Curve discount(LocalDate day, List<String> flags) {
        DataNode doc = kind(day, props.curveKind(), "CURVE_ID").get(props.discountCurve());
        if (doc != null) {
            FvaMath.Curve c = points(props.discountCurve(), doc, "DF", props.curveKind() + " " + props.discountCurve() + " of " + day);
            if (c != null) {
                return c;
            }
        }
        flags.add("FLAT_DISCOUNT");
        double r = props.discountRate();
        return new FvaMath.Curve("flat " + r, List.of("1Y"), new double[] {1.0}, new double[] {Math.exp(-r)},
                "drishti.ccr.fva.discount-rate (no curve " + props.discountCurve() + ")");
    }

    /** A curve's points from its document: {@code POINTS[].TENOR} and the field; null when it has none. */
    static FvaMath.Curve points(String id, DataNode doc, String field, String source) {
        DataNode pts = doc.get("POINTS");
        List<double[]> rows = new ArrayList<>();
        List<String> tenors = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
            DataNode pt = pts.get(i);
            double v = pt.get(field).asDouble();
            String tenor = pt.get("TENOR").asText();
            if (Double.isNaN(v) || tenor.isBlank()) {
                continue;
            }
            double y;
            try {
                y = FvaMath.tenorYears(tenor);
            } catch (IllegalArgumentException e) {
                continue;
            }
            rows.add(new double[] {y, v});
            tenors.add(tenor);
        }
        if (rows.isEmpty()) {
            return null;
        }
        Integer[] idx = new Integer[rows.size()];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(rows.get(a)[0], rows.get(b)[0]));
        double[] t = new double[idx.length];
        double[] v = new double[idx.length];
        List<String> ts = new ArrayList<>();
        for (int i = 0; i < idx.length; i++) {
            t[i] = rows.get(idx[i])[0];
            v[i] = rows.get(idx[i])[1];
            ts.add(tenors.get(idx[i]));
        }
        return new FvaMath.Curve(id, ts, t, v, source);
    }

    /** A kind's documents of the date by their id field (cached a few minutes); a kind no connector serves reads as empty. */
    private Map<String, DataNode> kind(LocalDate day, String kind, String idField) {
        return docs.get(day + "|" + kind, key -> {
            Map<String, DataNode> m;
            try {
                m = exposure.loader().documents(kind, AsOf.of(day));
            } catch (RuntimeException e) {                         // nothing read: the configured curve or the flat setting answers, flagged
                org.slf4j.LoggerFactory.getLogger(FvaService.class).warn("fva: the {} of {} not read: {}", kind, day, e.getMessage());
                m = Map.of();
            }
            Map<String, DataNode> out = new java.util.HashMap<>();
            m.forEach((id, v) -> {
                String own = v.get(idField).asText();
                out.put(own.isBlank() ? id : own, v);
            });
            return Map.copyOf(out);
        });
    }

    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o).strip().toUpperCase(Locale.ROOT);
    }
}
