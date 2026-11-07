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
package com.ash.drishti.banking.compute.frtb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Two runs of one scope side by side (SENSITIVITIES_AND_FRTB_SA.md section 12.1a, FRTB_SA_GUIDE.md section 8.2): the difference per risk
 * class and measure, the bucket where most of it is, the factor or the pair of factors behind it, and the step where it arises (the
 * weighted sensitivity, the curvature risk, the correlations within a bucket, the sum across buckets, or the scenario), in words.
 */
public final class FrtbCompare {

    /** One class and measure's difference in the scenario each run reports. */
    public record Line(String cls, String measure, double base, double alternative, double difference, String bucket, String step, String detail) {}

    /** The comparison. */
    public record Diff(String label, double baseTotal, double alternativeTotal, double difference, String baseScenario, String alternativeScenario,
            List<Line> lines, String words) {}

    private FrtbCompare() {}

    /**
     * {@code alt} against {@code base}; {@code label} names what changed ({@code girr-correlation: table → exact}).
     */
    public static Diff compare(FrtbSa.Result base, FrtbSa.Result alt, String label) {
        Sbm.Result a = base.sbm();
        Sbm.Result b = alt.sbm();
        List<Line> lines = new ArrayList<>();
        Map<String, Sbm.ClassCharge> bc = new HashMap<>();
        b.charges.forEach(c -> bc.put(c.cls + "|" + c.measure, c));
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (Sbm.ClassCharge c : a.charges) {
            seen.add(c.cls + "|" + c.measure);
            lines.add(line(base, alt, c, bc.get(c.cls + "|" + c.measure)));
        }
        for (Sbm.ClassCharge c : b.charges) {
            if (seen.add(c.cls + "|" + c.measure)) {
                lines.add(line(base, alt, null, c));
            }
        }
        lines.removeIf(l -> Math.abs(l.difference()) < 0.005);
        lines.sort((x, y) -> Double.compare(Math.abs(y.difference()), Math.abs(x.difference())));
        double ta = base.total();
        double tb = alt.total();
        String sa = Sbm.SCENARIOS[a.reported];
        String sb = Sbm.SCENARIOS[b.reported];
        StringBuilder w = new StringBuilder();
        double d = tb - ta;
        if (Math.abs(d) < 0.005) {
            w.append(cap(label)).append(" leaves the total unchanged at ").append(money(ta)).append('.');
        } else {
            w.append(cap(label)).append(d > 0 ? " raises" : " lowers").append(" the total by ").append(money(Math.abs(d)));
            if (ta != 0) {
                w.append(" (").append(String.format(Locale.ROOT, "%.1f%%", Math.abs(d) / Math.abs(ta) * 100)).append(')');
            }
            w.append(", from ").append(money(ta)).append(" to ").append(money(tb)).append('.');
            if (!sa.equals(sb)) {
                w.append(" The binding scenario moves from ").append(sa).append(" to ").append(sb).append('.');
            }
            if (!lines.isEmpty()) {
                Line top = lines.get(0);
                w.append(' ').append(className(top.cls())).append(' ').append(top.measure().toLowerCase(Locale.ROOT))
                        .append(top.difference() > 0 ? " rises by " : " falls by ").append(money(Math.abs(top.difference())));
                if (top.bucket() != null) {
                    w.append(", most of it in bucket ").append(top.bucket());
                }
                if (top.detail() != null) {
                    w.append(", ").append(top.detail());
                }
                w.append("; the difference arises in ").append(top.step()).append('.');
            }
        }
        if (base.drc().delivered() && alt.drc().delivered() && Math.abs(base.drc().total() - alt.drc().total()) >= 0.005) {
            w.append(" DRC moves by ").append(money(alt.drc().total() - base.drc().total())).append('.');
        }
        return new Diff(label, ta, tb, tb - ta, sa, sb, List.copyOf(lines), w.toString());
    }

    private static Line line(FrtbSa.Result base, FrtbSa.Result alt, Sbm.ClassCharge ca, Sbm.ClassCharge cb) {
        Sbm.ClassCharge any = ca != null ? ca : cb;
        int sa = base.sbm().reported;
        int sb = alt.sbm().reported;
        double va = ca == null ? 0 : ca.charge[sa];
        double vb = cb == null ? 0 : cb.charge[sb];
        String step;
        String bucket = null;
        String detail = null;
        if (sa != sb && ca != null && cb != null && Math.abs(ca.charge[sb] - cb.charge[sb]) < 0.005) {
            step = "the scenario chosen (" + Sbm.SCENARIOS[sa] + " to " + Sbm.SCENARIOS[sb] + ")";
        } else if (ca == null || cb == null) {
            step = "the factors themselves (the class is in one run only)";
        } else {
            // the bucket whose K_b moved most
            double best = -1;
            Sbm.Bucket bA = null;
            Sbm.Bucket bB = null;
            for (Sbm.Bucket x : ca.buckets) {
                for (Sbm.Bucket y : cb.buckets) {
                    if (x.bucket.equals(y.bucket)) {
                        double dk = Math.abs(y.k[sb] - x.k[sa]);
                        if (dk > best) {
                            best = dk;
                            bA = x;
                            bB = y;
                        }
                    }
                }
            }
            if (bA != null && best >= 0.005) {
                bucket = bA.bucket;
                if (any.measure.equals(SbmModel.CURVATURE)) {
                    boolean cvrMoved = false;
                    for (int i = 0; i < bA.members.length && i < bB.members.length; i++) {
                        SbmModel.Curv x = base.sbm().model.curvs.get(bA.members[i]);
                        SbmModel.Curv y = alt.sbm().model.curvs.get(bB.members[i]);
                        if (Math.abs(x.cvrUp - y.cvrUp) >= 0.005 || Math.abs(x.cvrDown - y.cvrDown) >= 0.005) {
                            cvrMoved = true;
                            detail = "on " + x.name + " (CVR+ " + money(x.cvrUp) + " to " + money(y.cvrUp) + ", CVR− " + money(x.cvrDown) + " to "
                                    + money(y.cvrDown) + ")";
                            break;
                        }
                    }
                    step = cvrMoved ? "the curvature risk of a factor (CVR+ and CVR−)" : "the curvature aggregation within the bucket";
                } else {
                    String moved = wsMoved(base.sbm().model, alt.sbm().model, bA, bB);
                    if (moved != null) {
                        step = "the weighted sensitivity (the risk weight)";
                        detail = moved;
                    } else {
                        step = "the correlations within the bucket (K_b)";
                        detail = pair(base.sbm().model, bA, bB);
                    }
                }
            } else {
                step = "the sum across buckets (γ and S_b)";
            }
        }
        return new Line(any.cls, any.measure, va, vb, vb - va, bucket, step, detail);
    }

    private static String wsMoved(SbmModel ma, SbmModel mb, Sbm.Bucket a, Sbm.Bucket b) {
        for (int i = 0; i < a.members.length && i < b.members.length; i++) {
            SbmModel.Risk x = ma.risks.get(a.members[i]);
            SbmModel.Risk y = mb.risks.get(b.members[i]);
            if (Math.abs(x.ws() - y.ws()) >= 0.005) {
                return "on " + label(x) + " (WS " + money(x.ws()) + " to " + money(y.ws()) + ")";
            }
        }
        return null;
    }

    /** The pair of factors whose cross term moved most between the two ρ matrices (medium). */
    private static String pair(SbmModel m, Sbm.Bucket a, Sbm.Bucket b) {
        if (a.rho == null || b.rho == null || a.rho.length != b.rho.length) {
            return null;
        }
        double best = 0;
        int bi = -1;
        int bj = -1;
        for (int i = 0; i < a.rho.length; i++) {
            for (int j = i + 1; j < a.rho.length; j++) {
                double d = Math.abs((b.rho[i][j] - a.rho[i][j]) * m.risks.get(a.members[i]).ws() * m.risks.get(a.members[j]).ws());
                if (d > best) {
                    best = d;
                    bi = i;
                    bj = j;
                }
            }
        }
        if (bi < 0) {
            return null;
        }
        return "between " + label(m.risks.get(a.members[bi])) + " and " + label(m.risks.get(a.members[bj])) + " (ρ "
                + String.format(Locale.ROOT, "%.6f", a.rho[bi][bj]) + " to " + String.format(Locale.ROOT, "%.6f", b.rho[bi][bj]) + ")";
    }

    static String label(SbmModel.Risk r) {
        String[] p = r.key.split("\\|");
        return String.join(" ", java.util.Arrays.copyOfRange(p, 1, p.length));
    }

    static String className(String cls) {
        return switch (cls) {
            case "GIRR" -> "GIRR";
            case "CSR_NS" -> "CSR";
            case "EQ" -> "Equity";
            case "COMM" -> "Commodity";
            default -> "FX";
        };
    }

    static String money(double v) {
        return String.format(Locale.ROOT, "%,.2f", v);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
