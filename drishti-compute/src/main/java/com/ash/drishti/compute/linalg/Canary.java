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
package com.ash.drishti.compute.linalg;

/**
 * The start-up check of a native library against {@link JavaBlas} (HIGH_PERFORMANCE_NATIVE.md section 9.4): a product of each kind on
 * fixed numbers, every figure within a relative 1e-12 of the Java answer (relative to the largest figure of the result).
 */
final class Canary {

    /** The parity bound of the design (section 6.2). */
    static final double TOLERANCE = 1e-12;

    private Canary() {}

    /**
     * The start-up and scheduled self-test: {@link #compare} plus products of awkward shapes (1 x 1, tall and thin, wide, sizes that are
     * not multiples of 8, both transposes, medium sizes), each against Java. Null when all agree; else the first difference.
     */
    static String selfTest(LinearAlgebra n, LinearAlgebra j) {
        String r = compare(n, j);
        int[][] shapes = {{1, 1, 1}, {1, 7, 1}, {7, 1, 3}, {1000, 2, 3}, {3, 2, 1000}, {2, 900, 5}, {9, 13, 17}, {31, 33, 7}, {130, 97, 65},
            {257, 64, 129}};
        int seed = 10;
        for (int[] sh : shapes) {
            if (r != null) {
                return r;
            }
            int m = sh[0];
            int c = sh[1];
            int k = sh[2];
            double[] a = fill(m * k, seed++);
            double[] b = fill(k * c, seed++);
            String what = m + " x " + k + " x " + c;
            r = check("dgemm " + what, run(n, a, b, m, k, c, false), run(j, a, b, m, k, c, false));
            r = r != null ? r : check("dgemm' " + what, run(n, a, b, m, k, c, true), run(j, a, b, m, k, c, true));
            if (r == null) {
                double[] x = fill(k, seed++);
                double[] yn = fill(m, seed);
                double[] yj = yn.clone();
                n.dgemv(false, m, k, 0.75, a, 0, m, x, 0, 0.5, yn, 0);
                j.dgemv(false, m, k, 0.75, a, 0, m, x, 0, 0.5, yj, 0);
                r = check("dgemv " + what, yn, yj);
                double[] x2 = fill(m, seed++);
                double[] zn = new double[k];
                double[] zj = new double[k];
                n.dgemv(true, m, k, 1.0, a, 0, m, x2, 0, 0.0, zn, 0);
                j.dgemv(true, m, k, 1.0, a, 0, m, x2, 0, 0.0, zj, 0);
                r = r != null ? r : check("dgemv' " + what, zn, zj);
                double[] pn = x.clone();
                double[] pj = x.clone();
                n.daxpy(k, -1.25, b, 0, pn, 0);
                j.daxpy(k, -1.25, b, 0, pj, 0);
                r = r != null ? r : check("daxpy " + k, pn, pj);
            }
        }
        return r;
    }

    /** Null when {@code n} agrees with {@code j}; else what differed. */
    static String compare(LinearAlgebra n, LinearAlgebra j) {
        int m = 67;
        int k = 129;
        int c = 13;
        double[] a = fill(m * k, 1);
        double[] b = fill(k * c, 2);
        double[] x = fill(k, 3);
        String r = check("dgemm", run(n, a, b, m, k, c, false), run(j, a, b, m, k, c, false));
        r = r != null ? r : check("dgemm (transposed)", run(n, a, b, m, k, c, true), run(j, a, b, m, k, c, true));
        if (r != null) {
            return r;
        }
        double[] yn = new double[m];
        double[] yj = new double[m];
        n.dgemv(false, m, k, 1.5, a, 0, m, x, 0, 0, yn, 0);
        j.dgemv(false, m, k, 1.5, a, 0, m, x, 0, 0, yj, 0);
        r = check("dgemv", yn, yj);
        if (r != null) {
            return r;
        }
        double dn = n.ddot(k, x, 0, b, 0);
        double dj = j.ddot(k, x, 0, b, 0);
        return check("ddot", new double[] {dn}, new double[] {dj});
    }

    private static double[] run(LinearAlgebra la, double[] a, double[] b, int m, int k, int c, boolean trans) {
        double[] out = new double[m * c];
        if (trans) {                                                       // A' (k x m)' with lda = k: the same numbers read the other way
            la.dgemm(true, false, m, c, k, 1.0, a, 0, k, b, 0, k, 0.0, out, 0, m);
        } else {
            la.dgemm(false, false, m, c, k, 1.0, a, 0, m, b, 0, k, 0.0, out, 0, m);
        }
        return out;
    }

    /** Fixed, varied numbers of both signs and several magnitudes (no randomness: the same check everywhere). */
    static double[] fill(int n, int seed) {
        double[] v = new double[n];
        long s = 0x9E3779B97F4A7C15L * seed;
        for (int i = 0; i < n; i++) {
            s ^= s << 13;
            s ^= s >>> 7;
            s ^= s << 17;
            v[i] = ((s >>> 11) * 0x1.0p-53 - 0.5) * Math.pow(10, i % 5);
        }
        return v;
    }

    /** Null when every figure of {@code got} is within the tolerance of {@code want}, relative to the largest of {@code want}. */
    static String check(String what, double[] got, double[] want) {
        double scale = 0;
        for (double w : want) {
            scale = Math.max(scale, Math.abs(w));
        }
        for (int i = 0; i < want.length; i++) {
            double d = Math.abs(got[i] - want[i]);
            if (!(d <= TOLERANCE * Math.max(scale, Double.MIN_NORMAL))) {
                return what + " differs at " + i + ": " + got[i] + " against " + want[i];
            }
        }
        return null;
    }
}
