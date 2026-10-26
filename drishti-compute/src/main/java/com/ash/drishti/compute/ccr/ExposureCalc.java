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
package com.ash.drishti.compute.ccr;

import com.ash.drishti.compute.vector.OrdinalSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A scope's exposure on one date (EXP.md sections 6.4, 6.6 and 8): the trades it resolved to are grouped by netting set; a set the scope
 * covers whole adds its exposure matrices (precomputed at load under the default collateral model); a set it cuts (a desk's share) is
 * recomputed from the scope's trades, read from the lake, with collateral as if those trades were the set ({@code partial}); a scope of
 * trades only values them alone, uncollateralised ({@code standalone}). Exposures add per path across sets; the statistics are read from
 * the sum, so a counterparty's PFE is the PFE of everything it faces, never a sum of PFEs.
 */
public final class ExposureCalc {

    /**
     * What a request asks.
     *
     * @param q the confidence of the PFE fields
     * @param confidences every confidence computed (q among them)
     * @param window the paths around the quantile a PFE contribution averages over
     * @param maxSets the most whole netting sets an interactive answer sums (more: a batch job, DRS-5443)
     */
    public record Params(double q, ExposureMath.Model model, ExposureMath.Rule rule, double[] confidences, int window, int maxSets) {
        public Params {
            Set<Double> qs = new LinkedHashSet<>();
            for (double c : confidences) {
                qs.add(c);
            }
            qs.add(q);
            confidences = qs.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        }
    }

    /** Raised when a scope sums more netting sets than an interactive answer may (the official capture computes it). */
    public static final class TooLarge extends IllegalStateException {
        public TooLarge(String m) {
            super(m);
        }
    }

    /** One netting set's share of a scope: whole, or recomputed from the scope's trades in it. */
    public static final class Piece {
        public final int set;
        public final boolean whole;
        public final int[] trades;
        public final CsaTerms csa;
        public final double v0;
        public final double ce;
        public double[] e;
        public double[] n;
        public double[] pos;
        double[] v;
        double[] c;

        Piece(int set, boolean whole, int[] trades, CsaTerms csa, double v0, double ce) {
            this.set = set;
            this.whole = whole;
            this.trades = trades;
            this.csa = csa;
            this.v0 = v0;
            this.ce = ce;
        }
    }

    /** A scope computed. */
    public static final class Result {
        public final ExposureBook book;
        public final OrdinalSet trades;
        public final boolean standalone;
        public final Params params;
        public final List<Piece> pieces;
        public final double[] e;
        public final double[] n;
        public final double[] pos;
        public final ExposureMath.Stats stats;
        public final List<String> flags;

        Result(ExposureBook book, OrdinalSet trades, boolean standalone, Params params, List<Piece> pieces, double[] e, double[] n, double[] pos,
                ExposureMath.Stats stats, List<String> flags) {
            this.book = book;
            this.trades = trades;
            this.standalone = standalone;
            this.params = params;
            this.pieces = pieces;
            this.e = e;
            this.n = n;
            this.pos = pos;
            this.stats = stats;
            this.flags = flags;
        }

        public int wholeSets() {
            return (int) pieces.stream().filter(p -> p.whole).count();
        }

        public int partialSets() {
            return pieces.size() - wholeSets();
        }
    }

    private ExposureCalc() {}

    /** The primary points' times in years. */
    public static double[] times(ExposureGrid g) {
        double[] t = new double[g.primaryCount()];
        for (int i = 0; i < t.length; i++) {
            t[i] = g.time(g.primaryAt(i));
        }
        return t;
    }

    /** Computes a scope: its trades, whether it is standalone, the request's parameters; trade paths read when a set is cut. */
    public static Result evaluate(ExposureBook b, OrdinalSet trades, boolean standalone, Params prm, ExposureBook.TradePaths paths) {
        ExposureGrid g = b.grid();
        int p = b.paths();
        int gp = g.primaryCount();
        Map<Integer, List<Integer>> bySet = new LinkedHashMap<>();
        trades.forEach(t -> bySet.computeIfAbsent(b.trade(t).set(), x -> new ArrayList<>()).add(t));
        List<Piece> pieces = new ArrayList<>();
        List<String> need = new ArrayList<>();
        double life = 0;
        for (Map.Entry<Integer, List<Integer>> en : bySet.entrySet()) {
            ExposureBook.SetInfo s = b.set(en.getKey());
            int[] ts = en.getValue().stream().mapToInt(Integer::intValue).toArray();
            boolean whole = !standalone && ts.length == s.to() - s.from();
            double v0 = 0;
            for (int t : ts) {
                v0 += b.trade(t).mtm0();
                life = Math.max(life, b.trade(t).lifeYears());
            }
            CsaTerms csa = standalone ? CsaTerms.NONE : whole ? s.csa() : s.csa().holding(s.csa().holding(0, 0).called(v0), 0);
            pieces.add(new Piece(en.getKey(), whole, ts, csa, v0, ExposureMath.currentExposure(v0, csa, prm.model())));
            if (!whole) {
                for (int t : ts) {
                    need.add(b.trade(t).id());
                }
            }
        }
        int whole = (int) pieces.stream().filter(x -> x.whole).count();
        if (prm.maxSets() > 0 && whole > prm.maxSets()) {
            throw new TooLarge("the scope sums " + whole + " netting sets, more than an interactive answer may (" + prm.maxSets() + ")");
        }
        Map<String, double[]> tp = need.isEmpty() ? Map.of() : paths.read(need);
        double[] e = new double[gp * p];
        double[] n = new double[gp * p];
        double[] pos = new double[gp * p];
        double ce = 0;
        for (Piece pc : pieces) {
            if (pc.whole && prm.model() == b.model()) {
                b.addExposure(pc.set, e);
                b.addNegative(pc.set, n);
                b.addPositive(pc.set, pos);
            } else {
                double[] v = pc.whole ? b.value(pc.set) : sum(tp, b, pc.trades, g.size() * p);
                double[][] enc = ExposureMath.exposure(v, g, p, pc.csa, prm.model());
                pc.v = v;
                pc.e = enc[0];
                pc.n = enc[1];
                pc.c = enc[2];
                pc.pos = ExposureMath.positive(v, g, p);
                ExposureMath.addInto(e, pc.e);
                ExposureMath.addInto(n, pc.n);
                ExposureMath.addInto(pos, pc.pos);
            }
            ce += pc.ce;
        }
        double horizon = Math.min(1.0, life);
        ExposureMath.Stats st = ExposureMath.statistics(e, n, pos, times(g), p, horizon, prm.confidences(), ce, prm.rule());
        List<String> flags = new ArrayList<>();
        if (pieces.stream().anyMatch(x -> !x.whole) && !standalone) {
            flags.add("partial");
        }
        if (standalone) {
            flags.add("standalone");
        }
        for (Piece pc : pieces) {
            for (String f : b.set(pc.set).flags()) {
                if (!flags.contains(f) && !f.equals("standalone")) {
                    flags.add(f);
                }
            }
        }
        return new Result(b, trades, standalone, prm, pieces, e, n, pos, st, flags);
    }

    private static double[] sum(Map<String, double[]> tp, ExposureBook b, int[] trades, int len) {
        double[] v = new double[len];
        for (int t : trades) {
            double[] m = tp.get(b.trade(t).id());
            if (m != null) {
                ExposureMath.addInto(v, m);
            }
        }
        return v;
    }

    // ------------------------------------------------------------------------------------------------------------- contributions

    /** A part of a scope and its share of the scope's exposure on every path ({@code G_primary x P}; the parts' shares add up to E). */
    public record Part(String key, String label, String kind, String scope, double[] x) {}

    /** A part's contribution to one figure. */
    public record Contribution(String key, String label, String kind, String scope, double value, double pct, Double incremental) {}

    /**
     * The parts of a scope: by {@code netting-set}, {@code counterparty}, {@code legal-entity} (sets add up per path), {@code trade} or
     * {@code asset} (each trade's share of its set's exposure, read from the lake).
     */
    public static List<Part> parts(Result r, String by, ExposureBook.TradePaths paths) {
        ExposureBook b = r.book;
        ExposureGrid g = b.grid();
        int p = b.paths();
        int gp = g.primaryCount();
        List<Part> out = new ArrayList<>();
        switch (by) {
            case "netting-set", "counterparty", "legal-entity" -> {
                Map<String, double[]> acc = new LinkedHashMap<>();
                Map<String, String> label = new LinkedHashMap<>();
                for (Piece pc : r.pieces) {
                    ExposureBook.SetInfo s = b.set(pc.set);
                    String key = by.equals("netting-set") ? s.id() : by.equals("counterparty") ? s.counterparty() : s.legalEntity();
                    double[] x = acc.computeIfAbsent(String.valueOf(key), k -> new double[gp * p]);
                    if (pc.e != null) {
                        ExposureMath.addInto(x, pc.e);
                    } else {
                        b.addExposure(pc.set, x);
                    }
                    label.put(String.valueOf(key), by.equals("netting-set") ? s.id() + " · " + s.counterparty() : String.valueOf(key));
                }
                String word = by.equals("netting-set") ? "NSET:" : by.equals("counterparty") ? "CPTY:" : "LE:";
                acc.forEach((k, x) -> out.add(new Part(k, label.get(k), by, word + k, x)));
            }
            case "trade", "asset" -> {
                List<String> ids = new ArrayList<>();
                r.trades.forEach(t -> ids.add(b.trade(t).id()));
                Map<String, double[]> tp = paths.read(ids);
                Map<String, double[]> byAsset = new LinkedHashMap<>();
                for (Piece pc : r.pieces) {
                    double[][] vec = tradeShares(r, pc, tp);
                    for (int i = 0; i < pc.trades.length; i++) {
                        ExposureBook.TradeInfo t = b.trade(pc.trades[i]);
                        if (by.equals("trade")) {
                            String lab = t.id() + (t.product() == null ? "" : " · " + t.product());
                            out.add(new Part(t.id(), lab, "trade", "TRD:" + t.id(), vec[i]));
                        } else {
                            String a = t.assetClass() == null ? "(none)" : t.assetClass();
                            double[] x = byAsset.computeIfAbsent(a, k -> new double[gp * p]);
                            ExposureMath.addInto(x, vec[i]);
                        }
                    }
                }
                byAsset.forEach((k, x) -> out.add(new Part(k, k, "asset", null, x)));
            }
            default -> throw new IllegalArgumentException("by is netting-set, counterparty, legal-entity, trade or asset, not " + by);
        }
        return out;
    }

    /** Each scope trade's share of its piece's exposure (computed in double from the trades' paths, so the shares add up exactly). */
    private static double[][] tradeShares(Result r, Piece pc, Map<String, double[]> tp) {
        ExposureBook b = r.book;
        ExposureGrid g = b.grid();
        int p = b.paths();
        double[] v = sum(tp, b, pc.trades, g.size() * p);
        double[][] enc = ExposureMath.exposure(v, g, p, pc.csa, r.params.model());
        double im = r.params.model() == ExposureMath.Model.NONE ? 0 : pc.csa.imHeld();
        double[][] out = new double[pc.trades.length][];
        for (int i = 0; i < pc.trades.length; i++) {
            double[] m = tp.getOrDefault(b.trade(pc.trades[i]).id(), new double[g.size() * p]);
            out[i] = ExposureMath.tradeShare(m, v, enc[0], enc[2], im, g, p);
        }
        return out;
    }

    /**
     * The parts' contributions to a figure: {@code EE} at primary point {@code at} (-1: the scope's peak EE date), {@code EPE} over the
     * scope's horizon, {@code PFE} (the window around the quantile at the peak PFE date, scaled to add up to the peak), largest first;
     * for {@code PFE} each part's incremental figure too (the peak with it less the peak without it).
     */
    public static List<Contribution> contributions(Result r, List<Part> parts, String figure, int at) {
        ExposureBook b = r.book;
        int p = b.paths();
        ExposureMath.Stats st = r.stats;
        int qa = st.at(r.params.q());
        double[] t = times(b.grid());
        List<Contribution> out = new ArrayList<>();
        double total;
        switch (figure) {
            case "EE" -> {
                int i = at >= 0 ? at : argmax(st.ee);
                total = st.ee.length == 0 ? 0 : st.ee[i];
                for (Part pt : parts) {
                    double v = ExposureMath.mean(pt.x(), i, p);
                    out.add(new Contribution(pt.key(), pt.label(), pt.kind(), pt.scope(), v, pct(v, total), null));
                }
            }
            case "EPE" -> {
                total = st.epe;
                for (Part pt : parts) {
                    double v = timeAverage(pt.x(), t, p, st.horizon);
                    out.add(new Contribution(pt.key(), pt.label(), pt.kind(), pt.scope(), v, pct(v, total), null));
                }
            }
            case "PFE" -> {
                int i = st.peakAt[qa] < 0 ? 0 : st.peakAt[qa];
                total = st.peakPfe[qa];
                int[] w = ExposureMath.window(r.e, i, p, r.params.q(), r.params.window());
                double sum = 0;
                double[] raw = new double[parts.size()];
                for (int k = 0; k < parts.size(); k++) {
                    raw[k] = ExposureMath.meanOver(parts.get(k).x(), i, p, w);
                    sum += raw[k];
                }
                double scale = ExposureMath.scale(total, sum);
                for (int k = 0; k < parts.size(); k++) {
                    Part pt = parts.get(k);
                    double v = raw[k] * scale;
                    out.add(new Contribution(pt.key(), pt.label(), pt.kind(), pt.scope(), v, pct(v, total), incremental(r, pt)));
                }
            }
            default -> throw new IllegalArgumentException("figure is EE, EPE or PFE, not " + figure);
        }
        out.sort((a, c) -> Double.compare(Math.abs(c.value()), Math.abs(a.value())));
        return out;
    }

    /** The peak PFE of the scope less the peak PFE of the scope without the part (its exposure removed per path). */
    private static Double incremental(Result r, Part pt) {
        int p = r.book.paths();
        double[] without = r.e.clone();
        for (int i = 0; i < without.length; i++) {
            without[i] = Math.max(0, without[i] - pt.x()[i]);
        }
        int qa = r.stats.at(r.params.q());
        double peak = 0;
        for (int i = 0; i < r.book.grid().primaryCount(); i++) {
            peak = Math.max(peak, ExposureMath.quantileSorted(ExposureMath.sortedRow(without, i, p), r.params.q(), r.params.rule()));
        }
        return r.stats.peakPfe[qa] - peak;
    }

    static double timeAverage(double[] x, double[] times, int p, double horizon) {
        if (horizon <= 0) {
            return 0;
        }
        double s = 0;
        double prev = 0;
        for (int i = 0; i < times.length; i++) {
            if (prev >= horizon - 1e-9) {
                break;
            }
            s += ExposureMath.mean(x, i, p) * (Math.min(times[i], horizon) - prev);
            prev = times[i];
        }
        return s / horizon;
    }

    private static int argmax(double[] a) {
        int best = 0;
        for (int i = 1; i < a.length; i++) {
            if (a[i] > a[best]) {
                best = i;
            }
        }
        return best;
    }

    private static double pct(double v, double total) {
        return Math.abs(total) > 1e-9 ? v / total : 0;
    }

    /** The trades of a scope by their ids (for a lake read). */
    public static List<String> ids(ExposureBook b, Collection<Integer> ordinals) {
        List<String> out = new ArrayList<>();
        for (int o : ordinals) {
            out.add(b.trade(o).id());
        }
        return out;
    }
}
