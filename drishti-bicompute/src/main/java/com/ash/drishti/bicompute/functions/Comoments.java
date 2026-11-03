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
package com.ash.drishti.bicompute.functions;

import com.ash.drishti.bicompute.spi.AggFunction;
import com.ash.drishti.bicompute.spi.Batch;
import com.ash.drishti.bicompute.spi.Kind;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * The two-variable statistics (FUNCTION_CATALOGUE.md section 4): {@code corr(a, b)}, {@code covar(a, b)} (sample, Excel's
 * {@code COVARIANCE.S}), and the least-squares line of {@code y} on {@code x}: {@code slope(y, x)}, {@code intercept(y, x)},
 * {@code rsq(y, x)}, written in Excel's order (y first). The state is the co-moments (n, mean a, mean b, Σ(a−ā)², Σ(b−b̄)², Σ(a−ā)(b−b̄)),
 * each batch's in two passes, merged by Chan's formulas: mergeable. Only rows where both values are present count, as Excel's
 * {@code CORREL} ignores a pair with an empty or text value.
 */
public final class Comoments implements AggFunction<Object> {

    /** The names this class answers. */
    public static final List<String> NAMES = List.of("corr", "covar", "slope", "intercept", "rsq");

    private final String name;

    /** {@code name}: corr, covar, slope, intercept or rsq. */
    public Comoments(String name) {
        if (!NAMES.contains(name)) {
            throw new IllegalArgumentException("no two-variable statistic " + name);
        }
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Kind kind() {
        return Kind.MERGEABLE;
    }

    @Override
    public int inputs() {
        return 2;
    }

    @Override
    public String resultUnit() {
        return "intercept".equals(name) ? null : "";
    }

    /** The state: {n, mean a, mean b, Caa, Cbb, Cab}. */
    @Override
    public Object start() {
        return new double[6];
    }

    @Override
    public Object add(Object s, Batch b) {
        double[] x = b.num(0);
        double[] y = b.num(1);
        int n = 0;
        Builtins.KSum sa = new Builtins.KSum(0, 0, 0);
        Builtins.KSum sb = new Builtins.KSum(0, 0, 0);
        for (int i = 0; i < b.rows(); i++) {
            if (!Double.isNaN(x[i]) && !Double.isNaN(y[i])) {
                sa.add(x[i]);
                sb.add(y[i]);
                n++;
            }
        }
        if (n == 0) {
            return s;
        }
        double ma = sa.value() / n;
        double mb = sb.value() / n;
        double caa = 0;
        double cbb = 0;
        double cab = 0;
        for (int i = 0; i < b.rows(); i++) {
            if (!Double.isNaN(x[i]) && !Double.isNaN(y[i])) {
                double da = x[i] - ma;
                double db = y[i] - mb;
                caa += da * da;
                cbb += db * db;
                cab += da * db;
            }
        }
        return combine((double[]) s, new double[] {n, ma, mb, caa, cbb, cab});
    }

    @Override
    public Object merge(Object a, Object b) {
        return combine((double[]) a, (double[]) b);
    }

    static double[] combine(double[] a, double[] b) {
        if (a[0] == 0) {
            return b.clone();
        }
        if (b[0] == 0) {
            return a.clone();
        }
        double n = a[0] + b[0];
        double f = a[0] * b[0] / n;
        double da = b[1] - a[1];
        double db = b[2] - a[2];
        return new double[] {n, a[1] + da * b[0] / n, a[2] + db * b[0] / n, a[3] + b[3] + da * da * f, a[4] + b[4] + db * db * f,
            a[5] + b[5] + da * db * f};
    }

    @Override
    public Double finish(Object s) {
        double[] st = (double[]) s;
        double n = st[0];
        double caa = Math.max(0, st[3]);
        double cbb = Math.max(0, st[4]);
        if (n < 2) {
            return null;
        }
        return switch (name) {
            case "covar" -> st[5] / (n - 1);
            case "corr" -> caa == 0 || cbb == 0 ? null : clamp(st[5] / Math.sqrt(caa * cbb));
            case "rsq" -> caa == 0 || cbb == 0 ? null : Math.min(1, st[5] * st[5] / (caa * cbb));
            case "slope" -> cbb == 0 ? null : st[5] / cbb;
            default -> cbb == 0 ? null : st[1] - st[5] / cbb * st[2];
        };
    }

    private static double clamp(double r) {
        return Math.max(-1, Math.min(1, r));
    }

    @Override
    public String emptyNote() {
        return switch (name) {
            case "covar" -> "fewer than two pairs of values in the cell";
            case "slope", "intercept" -> "fewer than two pairs of values in the cell, or x does not vary (Excel's #DIV/0!)";
            default -> "fewer than two pairs of values in the cell, or a variable does not vary (Excel's #DIV/0!)";
        };
    }

    @Override
    public byte[] encode(Object s) {
        ByteBuffer bb = ByteBuffer.allocate(48);
        for (double d : (double[]) s) {
            bb.putDouble(d);
        }
        return bb.array();
    }

    @Override
    public Object decode(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b);
        double[] st = new double[6];
        for (int i = 0; i < 6; i++) {
            st[i] = bb.getDouble();
        }
        return st;
    }

    @Override
    public long bytes(Object s) {
        return 64;
    }

    /** The co-moments over the rows where both inputs are present, from DuckDB's population forms times n. */
    @Override
    public List<String> sqlState(List<String> in) {
        String a = in.get(0);
        String b = in.get(1);
        String both = "(" + a + ") IS NOT NULL AND (" + b + ") IS NOT NULL";
        String ca = "CASE WHEN " + both + " THEN " + a + " END";
        String cb = "CASE WHEN " + both + " THEN " + b + " END";
        return List.of("count(CASE WHEN " + both + " THEN 1 END)", "avg(" + ca + ")", "avg(" + cb + ")", "var_pop(" + ca + ")",
                "var_pop(" + cb + ")", "covar_pop(" + a + ", " + b + ")");
    }

    @Override
    public Object fromSql(Object[] p) {
        double n = Builtins.lng(p[0]);
        double[] st = new double[6];
        st[0] = n;
        for (int i = 1; i < 6; i++) {
            double v = Builtins.num(p[i]);
            st[i] = Double.isNaN(v) || n == 0 ? 0 : i >= 3 ? v * n : v;
        }
        return st;
    }
}
