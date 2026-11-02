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
package com.ash.drishti.compute.frtb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The default risk charge for non-securitisations (FRTB_SA_GUIDE.md section 5; MAR22.9 to MAR22.26): the gross jump-to-default of each
 * position, scaled by maturity, netted per obligor by seniority (a short offsets a long of the same or higher seniority), then per bucket
 * with the hedge benefit ratio, and the buckets added. Every intermediate figure is kept for the explanation and the workbook.
 */
public final class Drc {

    /** One position's gross and scaled jump-to-default. */
    public record Line(FrtbInput.DrcPosition in, double lgd, double gross, double scale, double scaled) {}

    /** One obligor: its scaled longs and shorts by seniority, the net long and net short after netting, its risk weight. */
    public record Obligor(String obligor, String bucket, String quality, double rw, Map<String, double[]> bySeniority, double netLong,
            double netShort) {}

    /** One bucket's figures (MAR22.23 to MAR22.25). */
    public record Bucket(String bucket, double sumLong, double sumShort, double hbr, double weightedLong, double weightedShort, double charge) {}

    /** The DRC of a scope; {@code delivered} false when no default-risk input exists (the charge is then missing, never 0). */
    public record Result(boolean delivered, List<Line> lines, List<Obligor> obligors, List<Bucket> buckets, double total, List<String> refusals) {

        /** Each line's share of its bucket's charge (obligor share, then pro rata within the obligor's lines by |scaled|). */
        public Map<String, Double> contributionsByLine() {
            Map<String, Double> out = new LinkedHashMap<>();
            Map<String, Bucket> bk = new LinkedHashMap<>();
            buckets.forEach(b -> bk.put(b.bucket(), b));
            for (Obligor o : obligors) {
                Bucket b = bk.get(o.bucket());
                double share = b == null ? 0 : o.rw() * o.netLong() - b.hbr() * o.rw() * Math.abs(o.netShort());
                double denom = b == null ? 0 : b.weightedLong() - b.hbr() * b.weightedShort();
                double part = b == null || b.charge() == 0 || denom == 0 ? 0 : share / denom * b.charge();
                double abs = lines.stream().filter(l -> l.in().obligor().equals(o.obligor())).mapToDouble(l -> Math.abs(l.scaled())).sum();
                for (Line l : lines) {
                    if (l.in().obligor().equals(o.obligor())) {
                        out.merge(l.in().id(), abs == 0 ? 0 : part * Math.abs(l.scaled()) / abs, Double::sum);
                    }
                }
            }
            return out;
        }
    }

    private Drc() {}

    public static Result missing() {
        return new Result(false, List.of(), List.of(), List.of(), Double.NaN, List.of());
    }

    public static Result compute(List<FrtbInput.DrcPosition> positions, FrtbParams p) {
        if (positions == null) {
            return missing();
        }
        List<Line> lines = new ArrayList<>();
        List<String> refusals = new ArrayList<>();
        Map<String, List<Line>> byObligor = new TreeMap<>();
        for (FrtbInput.DrcPosition d : positions) {
            String sen = d.seniority() == null ? "" : d.seniority().strip().toUpperCase(Locale.ROOT);
            Double lgd = Double.isFinite(d.lgd()) ? Double.valueOf(d.lgd()) : p.drcLgd.get(sen);
            String q = d.creditQuality() == null ? "" : d.creditQuality().strip().toUpperCase(Locale.ROOT);
            String bucket = d.bucket() == null ? "" : d.bucket().strip().toUpperCase(Locale.ROOT);
            if (lgd == null || !p.drcSeniority.contains(sen)) {
                refusals.add(d.id() + ": the seniority " + d.seniority() + " is not one of " + p.drcSeniority);
                continue;
            }
            if (!p.drcRw.containsKey(q)) {
                refusals.add(d.id() + ": the credit quality " + d.creditQuality() + " has no risk weight");
                continue;
            }
            if (!p.drcBuckets.contains(bucket)) {
                refusals.add(d.id() + ": the bucket " + d.bucket() + " is not one of " + p.drcBuckets);
                continue;
            }
            if (!Double.isFinite(d.notional()) || !Double.isFinite(d.marketValue())) {
                refusals.add(d.id() + ": the notional or market value is not a number");
                continue;
            }
            double pnl = d.marketValue() - d.notional();
            double jtd = lgd * d.notional() + pnl;
            double gross = d.notional() >= 0 ? Math.max(jtd, 0) : Math.min(jtd, 0);
            double scale = d.cashEquity() ? p.drcCashEquityYears : Math.min(Math.max(d.maturityYears(), p.drcMaturityFloor), p.drcMaturityCap);
            Line l = new Line(d, lgd, gross, scale, gross * scale);
            lines.add(l);
            byObligor.computeIfAbsent(d.obligor(), x -> new ArrayList<>()).add(l);
        }
        List<Obligor> obligors = new ArrayList<>();
        for (Map.Entry<String, List<Line>> e : byObligor.entrySet()) {
            Line first = e.getValue().get(0);
            String q = first.in().creditQuality().strip().toUpperCase(Locale.ROOT);
            String bucket = first.in().bucket().strip().toUpperCase(Locale.ROOT);
            Map<String, double[]> bySen = new LinkedHashMap<>();
            for (String s : p.drcSeniority) {
                bySen.put(s, new double[2]);
            }
            for (Line l : e.getValue()) {
                double[] v = bySen.get(l.in().seniority().strip().toUpperCase(Locale.ROOT));
                if (l.scaled() >= 0) {
                    v[0] += l.scaled();
                } else {
                    v[1] += -l.scaled();
                }
            }
            // most senior first: the shorts of a level offset the longs of that level and of every more senior one still open
            double carry = 0;
            double shortLeft = 0;
            for (double[] v : bySen.values()) {
                double avail = carry + v[0];
                double used = Math.min(avail, v[1]);
                carry = avail - used;
                shortLeft += v[1] - used;
            }
            double rw = bucket.equals("SOVEREIGN") && p.drcSovereignZero ? 0 : p.drcRw.get(q);
            obligors.add(new Obligor(e.getKey(), bucket, q, rw, bySen, carry, -shortLeft));
        }
        List<Bucket> buckets = new ArrayList<>();
        double total = 0;
        for (String b : p.drcBuckets) {
            double sl = 0;
            double ss = 0;
            double wl = 0;
            double wsh = 0;
            boolean any = false;
            for (Obligor o : obligors) {
                if (o.bucket().equals(b)) {
                    any = true;
                    sl += o.netLong();
                    ss += Math.abs(o.netShort());
                    wl += o.rw() * o.netLong();
                    wsh += o.rw() * Math.abs(o.netShort());
                }
            }
            if (!any) {
                continue;
            }
            double hbr = sl + ss == 0 ? 0 : sl / (sl + ss);
            double charge = Math.max(wl - hbr * wsh, 0);
            buckets.add(new Bucket(b, sl, ss, hbr, wl, wsh, charge));
            total += charge;
        }
        return new Result(true, List.copyOf(lines), List.copyOf(obligors), List.copyOf(buckets), total, List.copyOf(refusals));
    }
}
