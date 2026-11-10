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

/**
 * The SBM's prescribed correlations (the medium scenario; FRTB_SA_GUIDE.md section 4.4): within a bucket (ρ, MAR21.46 to MAR21.94) and
 * across buckets (γ, MAR21.50 to MAR21.95), and the curvature correlations (the delta ones squared, with the dimensions curvature keeps,
 * MAR21.100 and MAR21.101). The scenarios are applied afterwards ({@link FrtbParams.Scenarios#apply}), to the squared value for curvature.
 */
public final class SbmCorrelations {

    private final FrtbParams p;
    private final FrtbChoices c;

    public SbmCorrelations(FrtbParams p, FrtbChoices c) {
        this.p = p;
        this.c = c;
    }

    /** The GIRR tenor correlation (MAR21.46): the published table or the exact formula, by the {@code girr-correlation} switch. */
    public double girrTenor(double tk, double tl) {
        if (c.girrCorrelation().equals("table")) {
            int i = p.girrTenorIndex(tk);
            int j = p.girrTenorIndex(tl);
            if (i >= 0 && j >= 0) {
                return p.girrTable[i][j];
            }
        }
        return girrExact(tk, tl);
    }

    /** MAR21.46's formula: {@code max(exp(−θ·|Tk − Tl| / min(Tk, Tl)), floor)}. */
    public double girrExact(double tk, double tl) {
        return Math.max(Math.exp(-p.girrTheta * Math.abs(tk - tl) / Math.min(tk, tl)), p.girrFloor);
    }

    /** {@code exp(−α·|Tk − Tl| / min(Tk, Tl))} (MAR21.93, MAR21.94). */
    public static double maturity(double alpha, double tk, double tl) {
        return Math.exp(-alpha * Math.abs(tk - tl) / Math.min(tk, tl));
    }

    /** ρ between two delta or vega factors of one bucket (the medium scenario). */
    public double rho(SbmModel.Risk a, SbmModel.Risk b) {
        if (a == b) {
            return 1;
        }
        boolean vega = a.measure.equals(SbmModel.VEGA);
        int bucket = SbmModel.bucketNo(a.bucket);
        return switch (a.cls) {
            case "GIRR" -> {
                if (vega) {
                    yield Math.min(maturity(p.girrVegaAlpha, a.t1, b.t1) * maturity(p.girrVegaAlpha, a.t2, b.t2), 1);
                }
                if (a.type.equals("BASIS") || b.type.equals("BASIS")) {
                    yield p.girrBasisToAnything;
                }
                if (a.type.equals("INFLATION") || b.type.equals("INFLATION")) {
                    yield p.girrInflationToCurve;
                }
                yield girrTenor(a.t1, b.t1) * (a.curve.equals(b.curve) ? 1 : p.girrDifferentCurve);
            }
            case "CSR_NS" -> {
                double name = a.name.equals(b.name) ? 1 : p.csrIndex.contains(bucket) ? p.csrNameIndex : p.csrName;
                if (vega) {
                    yield Math.min(name * maturity(p.csrVegaAlpha, a.t1, b.t1), 1);
                }
                yield name * (a.t1 == b.t1 ? 1 : p.csrTenor) * (a.curve.equals(b.curve) ? 1 : p.csrBasis);
            }
            case "EQ" -> {
                double name = a.name.equals(b.name) ? 1 : p.eqName.getOrDefault(bucket, 0.0);
                if (vega) {
                    yield Math.min(name * maturity(p.eqVegaAlpha, a.t1, b.t1), 1);
                }
                boolean mixed = !a.type.equals(b.type);
                yield a.name.equals(b.name) ? (mixed ? p.eqSpotRepo : 1) : name * (mixed ? p.eqSpotRepo : 1);
            }
            case "COMM" -> {
                double cty = a.name.equals(b.name) ? 1 : p.cmCommodity.getOrDefault(bucket, 0.0);
                if (vega) {
                    yield Math.min(cty * maturity(p.cmVegaAlpha, a.t1, b.t1), 1);
                }
                yield cty * (a.t1 == b.t1 ? 1 : p.cmTenor) * (a.curve.equals(b.curve) ? 1 : p.cmLocation);
            }
            default -> vega ? Math.min(maturity(p.fxVegaAlpha, a.t1, b.t1), 1) : 1;
        };
    }

    /** The delta correlation between two curvature factors of one bucket, before squaring (name, equity or commodity only). */
    public double curvatureRho(SbmModel.Curv a, SbmModel.Curv b) {
        if (a == b) {
            return 1;
        }
        int bucket = SbmModel.bucketNo(a.bucket);
        return switch (a.cls) {
            case "CSR_NS" -> a.name.equals(b.name) ? 1 : p.csrIndex.contains(bucket) ? p.csrNameIndex : p.csrName;
            case "EQ" -> a.name.equals(b.name) ? 1 : p.eqName.getOrDefault(bucket, 0.0);
            case "COMM" -> a.name.equals(b.name) ? 1 : p.cmCommodity.getOrDefault(bucket, 0.0);
            default -> 1;                                             // GIRR and FX: one curvature factor a bucket
        };
    }

    /** γ between two buckets of a class (the medium scenario; the same for delta, vega and, squared, curvature). */
    public double gamma(String cls, String b, String c2) {
        if (b.equals(c2)) {
            return 1;
        }
        int x = SbmModel.bucketNo(b);
        int y = SbmModel.bucketNo(c2);
        return switch (cls) {
            case "GIRR" -> p.girrGamma;
            case "CSR_NS" -> {
                if (x == p.csrOther || y == p.csrOther) {
                    yield 0;
                }
                int sx = p.csrSector(x);
                int sy = p.csrSector(y);
                double sector = sx < 0 || sy < 0 ? 0 : p.csrSectorTable[sx][sy];
                boolean both = x >= 1 && x <= 15 && y >= 1 && y <= 15;
                boolean differ = (x <= 8) != (y <= 8);
                yield sector * (both && differ ? p.csrRating : 1);
            }
            case "EQ" -> {
                if (x == p.eqOther || y == p.eqOther) {
                    yield p.eqGammaOther;
                }
                boolean ix = x == 12 || x == 13;
                boolean iy = y == 12 || y == 13;
                yield ix && iy ? p.eqGammaIndex : ix || iy ? p.eqGammaIndexName : p.eqGammaDefault;
            }
            case "COMM" -> x == p.cmOther || y == p.cmOther ? p.cmGammaOther : p.cmGamma;
            default -> p.fxGamma;
        };
    }

    /** True for a bucket that adds absolute values with no correlation (CSR 16, equity 11; MAR21.56, MAR21.79). */
    public boolean otherSector(String cls, String bucket) {
        int b = SbmModel.bucketNo(bucket);
        return cls.equals("CSR_NS") && b == p.csrOther || cls.equals("EQ") && b == p.eqOther;
    }
}
