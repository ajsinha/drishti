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
package com.ash.drishti.rachana.el;

import com.ash.drishti.api.DataNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * Finance on flows and rates the data already carries (FUNCTION_CATALOGUE.md section 8, tier 2), each Excel's function under one name:
 * {@code npv}, {@code xnpv}, {@code irr}, {@code xirr}, {@code mirr}, {@code pv}, {@code fv}, {@code pmt}, {@code ipmt}, {@code ppmt},
 * {@code nper}, {@code rate}, {@code cumipmt}, {@code cumprinc}, {@code effect}, {@code nominal}, {@code fvschedule}, {@code rri},
 * {@code pduration}, {@code accrued}, {@code dollarde}, {@code dollarfr}, {@code macaulay_duration}, {@code modified_duration},
 * {@code convexity}. Pricing a trade from market data is QLRisk's, and VaR, ES, exposure, FRTB-SA and SIMM come only from their engines
 * through {@code measure(...)}: nothing here builds a curve or reads market data.
 *
 * <p>Rules: Excel's signs (money paid out negative, received positive) and Excel's argument order. An empty argument gives an empty
 * result; a list of flows with an empty element gives an empty result too (a missing flow is never skipped, which would shift every later
 * period), a masked element a masked result. Discounting is a power or an exponential, so these are real-number functions, computed in
 * binary floating point as Excel computes them; {@code accrued}, {@code dollarde} and {@code dollarfr} are exact in decimal. The root
 * finders ({@code irr}, {@code xirr}, {@code rate}) are {@link RootFinder}'s, with its tolerance and bounds, and say in words when no rate
 * makes the value 0.
 */
final class FinanceFunctions {

    private FinanceFunctions() {}

    /** The most flows a function reads: a bound on the work. */
    static final int MAX_FLOWS = 100_000;

    /** The most periods {@code cumipmt} and {@code cumprinc} walk. */
    static final int MAX_PERIODS = 100_000;

    static void register(Functions.Table t) {
        fn(t, "npv", 2, 2, (a, c) -> {
            Double r = Distributions.num("npv", a, 0);
            double[] f = flows("npv", a.get(1));
            if (r == null || f == null) {
                return null;
            }
            rateAbove("npv", r);
            return out("npv", npv(r, f, 1));
        });
        fn(t, "xnpv", 3, 3, (a, c) -> {
            Double r = Distributions.num("xnpv", a, 0);
            double[] f = flows("xnpv", a.get(1));
            double[] y = years("xnpv", a.get(2), f);
            if (r == null || f == null || y == null) {
                return null;
            }
            rateAbove("xnpv", r);
            return out("xnpv", xnpv(r, f, y));
        });
        fn(t, "irr", 1, 2, (a, c) -> {
            double[] f = flows("irr", a.get(0));
            Double guess = a.size() > 1 ? Distributions.num("irr", a, 1) : Double.valueOf(0.1);
            if (f == null || guess == null) {
                return null;
            }
            changesSign("irr", f);
            return out("irr", RootFinder.root("irr", r -> npv(r, f, 0), guess));
        });
        fn(t, "xirr", 2, 3, (a, c) -> {
            double[] f = flows("xirr", a.get(0));
            double[] y = years("xirr", a.get(1), f);
            Double guess = a.size() > 2 ? Distributions.num("xirr", a, 2) : Double.valueOf(0.1);
            if (f == null || y == null || guess == null) {
                return null;
            }
            changesSign("xirr", f);
            return out("xirr", RootFinder.root("xirr", r -> xnpv(r, f, y), guess));
        });
        fn(t, "mirr", 3, 3, (a, c) -> mirr(a));
        fn(t, "pv", 3, 5, (a, c) -> annuity("pv", a));
        fn(t, "fv", 3, 5, (a, c) -> annuity("fv", a));
        fn(t, "pmt", 3, 5, (a, c) -> annuity("pmt", a));
        fn(t, "nper", 3, 5, (a, c) -> annuity("nper", a));
        fn(t, "ipmt", 4, 6, (a, c) -> part("ipmt", a));
        fn(t, "ppmt", 4, 6, (a, c) -> part("ppmt", a));
        fn(t, "rate", 3, 6, (a, c) -> rate(a));
        fn(t, "cumipmt", 6, 6, (a, c) -> cumulative("cumipmt", a));
        fn(t, "cumprinc", 6, 6, (a, c) -> cumulative("cumprinc", a));
        fn(t, "effect", 2, 2, (a, c) -> {
            Double n = Distributions.num("effect", a, 0);
            Double m = Distributions.num("effect", a, 1);
            if (n == null || m == null) {
                return null;
            }
            double k = periodsPerYear("effect", m);
            Distributions.positive("effect", "the nominal rate", n);
            return out("effect", Math.expm1(k * Math.log1p(n / k)));
        });
        fn(t, "nominal", 2, 2, (a, c) -> {
            Double e = Distributions.num("nominal", a, 0);
            Double m = Distributions.num("nominal", a, 1);
            if (e == null || m == null) {
                return null;
            }
            double k = periodsPerYear("nominal", m);
            Distributions.positive("nominal", "the effective rate", e);
            return out("nominal", k * Math.expm1(Math.log1p(e) / k));
        });
        fn(t, "fvschedule", 2, 2, (a, c) -> {
            Double p = Distributions.num("fvschedule", a, 0);
            double[] r = flows("fvschedule", a.get(1));
            if (p == null || r == null) {
                return null;
            }
            double v = p;
            for (double x : r) {
                v *= 1 + x;
            }
            return out("fvschedule", v);
        });
        fn(t, "rri", 3, 3, (a, c) -> {
            Double n = Distributions.num("rri", a, 0);
            Double pv = Distributions.num("rri", a, 1);
            Double fv = Distributions.num("rri", a, 2);
            if (n == null || pv == null || fv == null) {
                return null;
            }
            Distributions.positive("rri", "the periods", n);
            if (pv == 0 || fv / pv < 0) {
                throw Args.error("rri", "the present and future values are not both of one sign, and not 0");
            }
            return out("rri", Math.pow(fv / pv, 1 / n) - 1);
        });
        fn(t, "pduration", 3, 3, (a, c) -> {
            Double r = Distributions.num("pduration", a, 0);
            Double pv = Distributions.num("pduration", a, 1);
            Double fv = Distributions.num("pduration", a, 2);
            if (r == null || pv == null || fv == null) {
                return null;
            }
            Distributions.positive("pduration", "the rate", r);
            Distributions.positive("pduration", "the present value", pv);
            Distributions.positive("pduration", "the future value", fv);
            return out("pduration", (Math.log(fv) - Math.log(pv)) / Math.log1p(r));
        });
        fn(t, "accrued", 3, 5, (a, c) -> accrued(a));
        fn(t, "dollarde", 2, 2, (a, c) -> dollar("dollarde", a, true));
        fn(t, "dollarfr", 2, 2, (a, c) -> dollar("dollarfr", a, false));
        fn(t, "macaulay_duration", 3, 5, (a, c) -> duration("macaulay_duration", a));
        fn(t, "modified_duration", 3, 5, (a, c) -> duration("modified_duration", a));
        fn(t, "convexity", 3, 5, (a, c) -> duration("convexity", a));
    }

    /** Registers {@code f}, whose result is masked when a list it reads has a masked element ({@link MaskedValue}). */
    static void fn(Functions.Table t, String name, int min, int max, ElFunction f) {
        t.fn(name, min, max, MaskedValue.guard(f));
    }

    /** A list of numbers; null when the list or any element is empty; {@link MaskedValue} thrown when an element is masked. */
    static double[] flows(String fn, Object v) {
        if (Values.masked(v)) {
            throw MaskedValue.INSTANCE;
        }
        DataNode.Arr list = Args.list(fn, v);
        if (list == null || list.size() == 0) {
            return null;
        }
        if (list.size() > MAX_FLOWS) {
            throw Args.error(fn, "reads at most " + MAX_FLOWS + " values, not " + list.size());
        }
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) {
            DataNode e = list.get(i);
            if (e.isMasked()) {
                throw MaskedValue.INSTANCE;
            }
            BigDecimal d = Args.decimal(fn, e);
            if (d == null) {
                return null;
            }
            out[i] = d.doubleValue();
        }
        return out;
    }

    /**
     * The dates of {@code xnpv}/{@code xirr} as years from the first (days / 365, Excel's rule), as many as the flows; a date before the
     * first is a problem (Excel's {@code #NUM!}).
     */
    private static double[] years(String fn, Object v, double[] f) {
        if (Values.masked(v)) {
            throw MaskedValue.INSTANCE;
        }
        DataNode.Arr list = Args.list(fn, v);
        if (list == null || f == null) {
            return null;
        }
        if (list.size() != f.length) {
            throw Args.error(fn, "the flows and the dates differ in number (" + f.length + " and " + list.size() + ")");
        }
        double[] out = new double[f.length];
        LocalDate first = null;
        for (int i = 0; i < out.length; i++) {
            DataNode e = list.get(i);
            if (e.isMasked()) {
                throw MaskedValue.INSTANCE;
            }
            LocalDate d = Args.date(fn, e);
            if (d == null) {
                return null;
            }
            if (first == null) {
                first = d;
            } else if (d.isBefore(first)) {
                throw Args.error(fn, "the date " + d + " is before the first date " + first);
            }
            out[i] = ChronoUnit.DAYS.between(first, d) / 365.0;
        }
        return out;
    }

    private static void rateAbove(String fn, double r) {
        if (!(r > -1)) {
            throw Args.error(fn, "the rate " + Values.text(Values.normalise(r)) + " is not above −1 (−100 %)");
        }
    }

    private static void changesSign(String fn, double[] f) {
        boolean pos = false;
        boolean neg = false;
        for (double x : f) {
            pos |= x > 0;
            neg |= x < 0;
        }
        if (!pos || !neg) {
            throw Args.error(fn, "the flows never change sign: no rate makes their value 0 (one flow paid and one received at least)");
        }
    }

    /** Σ f[i] / (1 + r)^(i + first): npv's first flow is one period away (first 1), irr's now (first 0). */
    static double npv(double r, double[] f, int first) {
        double v = 0;
        double c = 0;
        for (int i = 0; i < f.length; i++) {
            double y = f[i] / Math.pow(1 + r, i + first) - c;      // Kahan's compensated sum
            double s = v + y;
            c = (s - v) - y;
            v = s;
        }
        return v;
    }

    static double xnpv(double r, double[] f, double[] years) {
        double v = 0;
        for (int i = 0; i < f.length; i++) {
            v += f[i] / Math.pow(1 + r, years[i]);
        }
        return v;
    }

    /** {@code mirr(flows, finance_rate, reinvest_rate)}: Excel's MIRR. */
    private static Object mirr(List<Object> a) {
        double[] f = flows("mirr", a.get(0));
        Double fr = Distributions.num("mirr", a, 1);
        Double rr = Distributions.num("mirr", a, 2);
        if (f == null || fr == null || rr == null) {
            return null;
        }
        changesSign("mirr", f);
        rateAbove("mirr", fr);
        rateAbove("mirr", rr);
        int n = f.length;
        double pvNeg = 0;
        double fvPos = 0;
        for (int i = 0; i < n; i++) {
            if (f[i] < 0) {
                pvNeg += f[i] / Math.pow(1 + fr, i);
            } else {
                fvPos += f[i] * Math.pow(1 + rr, n - 1 - i);
            }
        }
        return out("mirr", Math.pow(-fvPos / pvNeg, 1.0 / (n - 1)) - 1);
    }

    /** The annuity's type: 0 (payments at the end of each period, the default) or 1 (at the start). */
    private static Integer type(String fn, List<Object> a, int i) {
        if (a.size() <= i) {
            return 0;
        }
        Integer t = Args.whole(fn, a.get(i));
        if (t != null && t != 0 && t != 1) {
            throw Args.error(fn, "the type " + t + " is not 0 (end of period) or 1 (start)");
        }
        return t;
    }

    private static Double opt(String fn, List<Object> a, int i) {
        return a.size() > i ? Distributions.num(fn, a, i) : Double.valueOf(0);
    }

    /** (1 + r)^n − 1 over r, accurate near r = 0. */
    private static double annuityFactor(double r, double n) {
        return r == 0 ? n : Math.expm1(n * Math.log1p(r)) / r;
    }

    static double fv(double r, double n, double pmt, double pv, int type) {
        if (r == 0) {
            return -(pv + pmt * n);
        }
        return -(pv * Math.pow(1 + r, n) + pmt * (1 + r * type) * annuityFactor(r, n));
    }

    static double pmt(double r, double n, double pv, double fv, int type) {
        if (r == 0) {
            return -(pv + fv) / n;
        }
        return -(pv * Math.pow(1 + r, n) + fv) / ((1 + r * type) * annuityFactor(r, n));
    }

    /** {@code pv}, {@code fv}, {@code pmt}, {@code nper}: Excel's, from the rate per period and three of the annuity's figures. */
    private static Object annuity(String fn, List<Object> a) {
        Double r = Distributions.num(fn, a, 0);
        Double x = Distributions.num(fn, a, 1);
        Double y = Distributions.num(fn, a, 2);
        Double z = opt(fn, a, 3);
        Integer type = type(fn, a, 4);
        if (r == null || x == null || y == null || z == null || type == null) {
            return null;
        }
        rateAbove(fn, r);
        return switch (fn) {
            case "pv" -> {                                          // pv(rate, nper, pmt, [fv], [type])
                yield out(fn, r == 0 ? -(y * x + z) : -(y * (1 + r * type) * annuityFactor(r, x) + z) / Math.pow(1 + r, x));
            }
            case "fv" -> out(fn, fv(r, x, y, z, type));               // fv(rate, nper, pmt, [pv], [type])
            case "pmt" -> {                                         // pmt(rate, nper, pv, [fv], [type])
                if (x == 0) {
                    throw Args.error(fn, "the number of periods is 0");
                }
                yield out(fn, pmt(r, x, y, z, type));
            }
            default -> {                                            // nper(rate, pmt, pv, [fv], [type])
                if (r == 0) {
                    if (x == 0) {
                        throw Args.error(fn, "with a rate of 0 the payment cannot be 0");
                    }
                    yield out(fn, -(y + z) / x);
                }
                double k = x * (1 + r * type) / r;
                double ratio = (k - z) / (k + y);
                if (!(ratio > 0)) {
                    throw Args.error(fn, "no number of periods reaches the future value with these payments");
                }
                yield out(fn, Math.log(ratio) / Math.log1p(r));
            }
        };
    }

    /** The interest part of payment {@code per}, as numpy-financial and Excel compute it. */
    static double ipmt(double r, double per, double n, double pv, double fv, int type) {
        double payment = pmt(r, n, pv, fv, type);
        if (type == 1 && per == 1) {
            return 0;
        }
        double interest = fv(r, per - 1, payment, pv, type) * r;
        return type == 1 ? interest / (1 + r) : interest;
    }

    /** {@code ipmt(rate, per, nper, pv, [fv], [type])}, {@code ppmt(...)}: a payment's interest and principal. */
    private static Object part(String fn, List<Object> a) {
        Double r = Distributions.num(fn, a, 0);
        Double per = Distributions.num(fn, a, 1);
        Double n = Distributions.num(fn, a, 2);
        Double pv = Distributions.num(fn, a, 3);
        Double fv = opt(fn, a, 4);
        Integer type = type(fn, a, 5);
        if (r == null || per == null || n == null || pv == null || fv == null || type == null) {
            return null;
        }
        rateAbove(fn, r);
        if (!(per >= 1 && per <= n)) {
            throw Args.error(fn, "the period " + Values.text(Values.normalise(per)) + " is not from 1 to the number of periods");
        }
        double i = ipmt(r, per, n, pv, fv, type);
        return out(fn, "ipmt".equals(fn) ? i : pmt(r, n, pv, fv, type) - i);
    }

    /** {@code rate(nper, pmt, pv, [fv], [type], [guess])}: Excel's RATE, the rate per period, by {@link RootFinder}. */
    private static Object rate(List<Object> a) {
        Double n = Distributions.num("rate", a, 0);
        Double p = Distributions.num("rate", a, 1);
        Double pv = Distributions.num("rate", a, 2);
        Double fv = opt("rate", a, 3);
        Integer type = type("rate", a, 4);
        Double guess = a.size() > 5 ? Distributions.num("rate", a, 5) : Double.valueOf(0.1);
        if (n == null || p == null || pv == null || fv == null || type == null || guess == null) {
            return null;
        }
        Distributions.positive("rate", "the number of periods", n);
        DoubleUnaryOperator value = r -> fv(r, n, p, pv, type) + fv;           // 0 where the annuity reaches the future value
        return out("rate", RootFinder.root("rate", r -> -value.applyAsDouble(r), guess));
    }

    /** {@code cumipmt}, {@code cumprinc} (rate, nper, pv, start, end, type): the interest or principal paid from period start to end. */
    private static Object cumulative(String fn, List<Object> a) {
        Double r = Distributions.num(fn, a, 0);
        Double n = Distributions.num(fn, a, 1);
        Double pv = Distributions.num(fn, a, 2);
        Integer start = Args.whole(fn, a.get(3));
        Integer end = Args.whole(fn, a.get(4));
        Integer type = type(fn, a, 5);
        if (r == null || n == null || pv == null || start == null || end == null || type == null) {
            return null;
        }
        Distributions.positive(fn, "the rate", r);
        Distributions.positive(fn, "the number of periods", n);
        Distributions.positive(fn, "the present value", pv);
        if (start < 1 || end < start || end > n || end - start >= MAX_PERIODS) {
            throw Args.error(fn, "the periods " + start + " to " + end + " are not within 1 to the number of periods, start first");
        }
        double payment = pmt(r, n, pv, 0, type);
        double sum = 0;
        for (int per = start; per <= end; per++) {
            double i = ipmt(r, per, n, pv, 0, type);
            sum += "cumipmt".equals(fn) ? i : payment - i;
        }
        return out(fn, sum);
    }

    private static double periodsPerYear(String fn, double m) {
        double k = Math.floor(m);
        if (k < 1) {
            throw Args.error(fn, "the periods per year " + Values.text(Values.normalise(m)) + " are under 1");
        }
        return k;
    }

    /**
     * {@code accrued(rate, from, to, [basis], [par])}: the interest accrued from one date to another at a yearly rate on a day-count basis
     * ({@code yearfrac}'s, {@code 30/360} by default) on a par (1 by default): rate · yearfrac(from, to, basis) · par, exact in decimal.
     * Excel's ACCRINTM is {@code accrued(rate, issue, settlement, 'ACT/365F', par)} for its basis 3.
     */
    private static Object accrued(List<Object> a) {
        BigDecimal r = Args.decimal("accrued", a.get(0));
        LocalDate from = Args.date("accrued", a.get(1));
        LocalDate to = Args.date("accrued", a.get(2));
        String basis = a.size() > 3 ? Args.text("accrued", a.get(3)) : "30/360";
        BigDecimal par = a.size() > 4 ? Args.decimal("accrued", a.get(4)) : BigDecimal.ONE;
        if (r == null || from == null || to == null || basis == null || par == null) {
            return null;
        }
        if (to.isBefore(from)) {
            throw Args.error("accrued", "the end " + to + " is before the start " + from);
        }
        return Args.out(r.multiply(DateFunctions.yearFraction("accrued", from, to, basis)).multiply(par).round(MathContext.DECIMAL64));
    }

    /**
     * {@code dollarde(price, fraction)}: a price written in fractions (1.02 with 16: one and 2/16) as a decimal (1.125); {@code dollarfr}
     * the reverse. Excel's DOLLARDE and DOLLARFR, exact in decimal; the fraction is cut to a whole number of at least 1.
     */
    private static Object dollar(String fn, List<Object> a, boolean toDecimal) {
        BigDecimal x = Args.decimal(fn, a.get(0));
        Integer f = Args.whole(fn, a.get(1));
        if (x == null || f == null) {
            return null;
        }
        if (f < 1) {
            throw Args.error(fn, "the fraction " + f + " is under 1");
        }
        BigDecimal whole = x.setScale(0, RoundingMode.DOWN);
        BigDecimal rest = x.subtract(whole);
        BigDecimal scale = BigDecimal.TEN.pow((int) Math.ceil(Math.log10(f)));
        BigDecimal fraction = BigDecimal.valueOf(f);
        BigDecimal part = toDecimal ? rest.multiply(scale).divide(fraction, MathContext.DECIMAL128)
                : rest.multiply(fraction).divide(scale, MathContext.DECIMAL128);
        return Args.out(whole.add(part).round(MathContext.DECIMAL64));
    }

    /**
     * {@code macaulay_duration(flows, when, yield, [frequency], [settle])}, {@code modified_duration(...)}, {@code convexity(...)}: on given
     * flows and a given yield. {@code when} is each flow's time in years, or its date (then {@code settle}, the date the times count from,
     * is needed: years are days / 365). The yield compounds {@code frequency} times a year (1 by default; 0: continuously). Macaulay
     * duration Σ t·CF·DF / Σ CF·DF; modified, Macaulay / (1 + y/f) (continuous: the same); convexity Σ CF·DF·t·(t + 1/f) / (P·(1 + y/f)²)
     * (continuous: Σ CF·DF·t² / P). Excel's DURATION and MDURATION are these for a bond's own flows on coupon dates.
     */
    private static Object duration(String fn, List<Object> a) {
        double[] f = flows(fn, a.get(0));
        Double y = Distributions.num(fn, a, 2);
        Double freq = a.size() > 3 ? Distributions.num(fn, a, 3) : Double.valueOf(1);
        if (Values.masked(a.get(1))) {
            return Values.mask();
        }
        if (f == null || y == null || freq == null) {
            return null;
        }
        double[] t = times(fn, a, f.length);
        if (t == null) {
            return null;
        }
        double m = Math.floor(freq);
        if (m < 0) {
            throw Args.error(fn, "the frequency " + Values.text(Values.normalise(freq)) + " is negative");
        }
        if (m > 0 && !(1 + y / m > 0)) {
            throw Args.error(fn, "the yield " + Values.text(Values.normalise(y)) + " is not above −" + Values.text(Values.normalise(m)));
        }
        double p = 0;
        double pt = 0;
        double ptt = 0;
        for (int i = 0; i < f.length; i++) {
            double df = m == 0 ? Math.exp(-y * t[i]) : Math.pow(1 + y / m, -m * t[i]);
            p += f[i] * df;
            pt += f[i] * df * t[i];
            ptt += f[i] * df * (m == 0 ? t[i] * t[i] : t[i] * (t[i] + 1 / m));
        }
        if (p == 0) {
            throw Args.error(fn, "the flows' present value is 0");
        }
        double mac = pt / p;
        return switch (fn) {
            case "macaulay_duration" -> out(fn, mac);
            case "modified_duration" -> out(fn, m == 0 ? mac : mac / (1 + y / m));
            default -> out(fn, m == 0 ? ptt / p : ptt / (p * (1 + y / m) * (1 + y / m)));
        };
    }

    /** The times of {@link #duration}: numbers as years, or dates counted from {@code settle} (argument 5) in days / 365. */
    private static double[] times(String fn, List<Object> a, int n) {
        DataNode.Arr list = Args.list(fn, a.get(1));
        if (list == null) {
            return null;
        }
        if (list.size() != n) {
            throw Args.error(fn, "the flows and their times differ in number (" + n + " and " + list.size() + ")");
        }
        LocalDate settle = a.size() > 4 ? Args.date(fn, a.get(4)) : null;
        double[] t = new double[n];
        for (int i = 0; i < n; i++) {
            Object v = Values.simplify(list.get(i));
            if (v == null) {
                return null;
            }
            if (v instanceof String s && Args.parseDate(s) != null) {
                if (settle == null) {
                    throw Args.error(fn, "times given as dates need the settlement date they count from (the fifth argument)");
                }
                t[i] = ChronoUnit.DAYS.between(settle, Args.parseDate(s)) / 365.0;
            } else {
                t[i] = Args.decimal(fn, v).doubleValue();
            }
            if (t[i] < 0) {
                throw Args.error(fn, "a flow's time is before the settlement");
            }
        }
        return t;
    }

    private static Object out(String fn, double v) {
        return Args.out(fn, v);
    }
}
