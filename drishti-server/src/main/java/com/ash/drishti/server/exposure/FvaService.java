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
 * ({@link ExposureCalc#profile}); {@link FvaMath} prices it with the funding spread, the discount curve and the choices. The scope's figures
 * are the sum of its sets' (FVA adds). The curves are read from the curve kind of the date (the market-data pack's {@code ir-curve}), a
 * funding curve named in the configuration when the kind has none of that id, else the flat fallback, said in the answer's flags.
 */
final class FvaService {

    /** The choices a request may change (the playground's Choices panel); null: the setting. */
    record Choices(String eePoint, Boolean fba, Boolean fundingSurvival, String fundingSpread) {
        static Choices none() {
            return new Choices(null, null, null, null);
        }
    }

    /** One netting set's FVA. */
    record SetFva(String id, String counterparty, String name, String csa, boolean whole, double ce, double nce, FvaMath.Result result) {}

    /** A scope's FVA: the exposure answer it reads, each set's and the sum, the curves and settings used, the flags. */
    record Answer(ExposureService.Scoped scoped, List<SetFva> sets, FvaMath.Result total, FvaMath.Curve borrow, FvaMath.Curve lend,
            FvaMath.Curve discount, double[] survival, FvaMath.Point point, boolean fba, boolean survivalOn, String calibration, String valueBasis,
            List<String> flags, boolean setsShown) {}

    private final ExposureService exposure;
    private final FvaProperties props;
    private final com.github.benmanes.caffeine.cache.Cache<LocalDate, Map<String, DataNode>> curves =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(20).expireAfterWrite(java.time.Duration.ofMinutes(5)).build();
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
        curves.invalidateAll();
    }

    /** FVA of a scope as the caller, on the request's business date (the exposure engine's newest with paths when later). */
    Answer answer(Principal p, String scope, LocalDate asked, Choices ch, String run, Boolean strict) {
        enabled();
        ExposureService.Ask ask = ExposureService.Ask.of(exposure.props(), null, null, run, strict);
        ExposureService.Scoped s = exposure.scoped(p, scope, asked, ask);
        ExposureBook b = s.date().book();
        List<String> flags = new ArrayList<>();
        Map<String, Object> runRec = b.run();
        String calibration = text(runRec.get("CALIBRATION"));
        String valueBasis = text(runRec.get("VALUE_BASIS"));
        if (!"RISK_NEUTRAL".equalsIgnoreCase(calibration)) {
            if (props.requireRiskNeutral()) {
                throw new DrishtiException(ErrorCode.FVA_REFUSED, "FVA is a price and needs a risk-neutral run: the run " + b.runId() + " of " + b.businessDate()
                        + " is " + (calibration.isEmpty() ? "of no stated calibration" : calibration) + " (drishti.ccr.fva.require-risk-neutral is true)");
            }
            flags.add("REAL_WORLD_RUN");
        }
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
        FvaMath.Market market = FvaMath.market(t, discount, borrow, lend, null);
        double[] survival = survivalOn ? FvaMath.flatSurvival(t, props.counterpartyHazard(), props.ownHazard()) : null;
        if (survivalOn) {
            market = new FvaMath.Market(market.df(), market.sb(), market.sl(), survival);
        }
        ExposureCalc.Result r = s.result();
        List<SetFva> sets = new ArrayList<>();
        List<FvaMath.Result> parts = new ArrayList<>();
        boolean shown = true;
        for (ExposureCalc.Piece pc : r.pieces) {
            double[][] prof = ExposureCalc.profile(r, pc);
            double nce = ExposureMath.currentNegative(pc.v0, pc.csa, r.params.model());
            FvaMath.Result fr = FvaMath.compute(new FvaMath.Profile(t, prof[0], prof[1], pc.ce, nce), market, point, fba);
            parts.add(fr);
            ExposureBook.SetInfo si = b.set(pc.set);
            if (!s.view().detailsSet(pc.set)) {
                shown = false;
            }
            sets.add(new SetFva(si.id(), si.counterparty(), exposure.name(s.date(), si.counterparty(), p), ExposureExplorer.csaLabel(pc.csa), pc.whole, pc.ce, nce,
                    fr));
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
        return new Answer(s, shown ? List.copyOf(sets) : List.of(), FvaMath.sum(parts), borrow, lend, discount, survival, point, fba, survivalOn,
                calibration, valueBasis, List.copyOf(flags), shown);
    }

    private static FvaMath.Point point(String s) {
        try {
            return FvaMath.Point.of(s);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, e.getMessage());
        }
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
        DataNode doc = curveDocs(day).get(spec);
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
        DataNode doc = curveDocs(day).get(props.discountCurve());
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

    private Map<String, DataNode> curveDocs(LocalDate day) {
        return curves.get(day, d -> {
            Map<String, DataNode> m;
            try {
                m = exposure.loader().documents(props.curveKind(), AsOf.of(d));
            } catch (RuntimeException e) {                         // no curves: the configured curve or the flat setting answers, flagged
                org.slf4j.LoggerFactory.getLogger(FvaService.class).warn("fva: the {} of {} not read: {}", props.curveKind(), d, e.getMessage());
                m = Map.of();
            }
            Map<String, DataNode> out = new java.util.HashMap<>();
            m.forEach((k, v) -> {
                String id = v.get("CURVE_ID").asText();
                out.put(id.isBlank() ? k : id, v);
            });
            return Map.copyOf(out);
        });
    }

    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o).strip().toUpperCase(Locale.ROOT);
    }
}
