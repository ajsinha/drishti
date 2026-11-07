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
package com.ash.drishti.nativ.linalg;

/**
 * The pure-Java {@link LinearAlgebra}: the reference BLAS loop orders, written so the JIT vectorises the inner loops (contiguous
 * {@code y += t * x} over a column). It never loads a native library, so {@code drishti.native.blas.mode: java} touches nothing native.
 *
 * <p><b>Summation order</b> (what parity tests rely on): {@link #ddot} adds {@code x[i] * y[i]} in index order from zero;
 * {@link #dgemv} with {@code trans} sums each output in index order from zero and then forms {@code alpha * sum + beta * y}; without
 * {@code trans} it adds the columns in column order; {@link #dgemm} adds {@code op(B)(l, j) * op(A)(:, l)} in {@code l} order and skips a
 * zero {@code op(B)(l, j)}, as the reference implementation does. A loop that writes {@code r += a[j] * x[j]} from zero therefore gives
 * the same bits as {@code dgemv(true, ...)} with {@code alpha = 1, beta = 0}.
 */
public final class JavaBlas implements LinearAlgebra {

    /** The one instance. */
    public static final JavaBlas INSTANCE = new JavaBlas();

    private JavaBlas() {}

    @Override
    public String name() {
        return "java";
    }

    @Override
    public boolean isNative() {
        return false;
    }

    @Override
    public void dgemm(boolean transA, boolean transB, int m, int n, int k, double alpha, double[] a, int aOff, int lda, double[] b, int bOff, int ldb,
            double beta, double[] c, int cOff, int ldc) {
        if (m == 0 || n == 0) {
            return;
        }
        for (int j = 0; j < n; j++) {
            int cj = cOff + j * ldc;
            if (!transA) {
                scale(c, cj, m, beta);
                if (alpha == 0) {
                    continue;
                }
                for (int l = 0; l < k; l++) {
                    double blj = transB ? b[bOff + j + l * ldb] : b[bOff + l + j * ldb];
                    if (blj == 0) {
                        continue;
                    }
                    double t = alpha * blj;
                    int al = aOff + l * lda;
                    for (int i = 0; i < m; i++) {
                        c[cj + i] += t * a[al + i];
                    }
                }
            } else {
                for (int i = 0; i < m; i++) {
                    int ai = aOff + i * lda;                            // op(A)(i, l) = A(l, i): column i of A, contiguous
                    double s = 0;
                    for (int l = 0; l < k; l++) {
                        double blj = transB ? b[bOff + j + l * ldb] : b[bOff + l + j * ldb];
                        s += a[ai + l] * blj;
                    }
                    c[cj + i] = beta == 0 ? alpha * s : alpha * s + beta * c[cj + i];
                }
            }
        }
    }

    @Override
    public void dgemv(boolean trans, int m, int n, double alpha, double[] a, int aOff, int lda, double[] x, int xOff, double beta, double[] y, int yOff) {
        if (!trans) {                                                    // y (m) = alpha A x (n) + beta y: the columns added in order
            scale(y, yOff, m, beta);
            if (alpha == 0) {
                return;
            }
            for (int j = 0; j < n; j++) {
                double t = alpha * x[xOff + j];
                if (t == 0) {
                    continue;
                }
                int aj = aOff + j * lda;
                for (int i = 0; i < m; i++) {
                    y[yOff + i] += t * a[aj + i];
                }
            }
            return;
        }
        for (int i = 0; i < n; i++) {                                    // y (n) = alpha A' x (m) + beta y: one dot per column of A
            int ai = aOff + i * lda;
            double s = 0;
            for (int l = 0; l < m; l++) {
                s += a[ai + l] * x[xOff + l];
            }
            y[yOff + i] = beta == 0 ? alpha * s : alpha * s + beta * y[yOff + i];
        }
    }

    @Override
    public double ddot(int n, double[] x, int xOff, double[] y, int yOff) {
        double s = 0;
        for (int i = 0; i < n; i++) {
            s += x[xOff + i] * y[yOff + i];
        }
        return s;
    }

    @Override
    public void daxpy(int n, double alpha, double[] x, int xOff, double[] y, int yOff) {
        if (alpha == 0) {
            return;
        }
        for (int i = 0; i < n; i++) {
            y[yOff + i] += alpha * x[xOff + i];
        }
    }

    private static void scale(double[] v, int off, int n, double beta) {
        if (beta == 1) {
            return;
        }
        if (beta == 0) {
            java.util.Arrays.fill(v, off, off + n, 0.0);
            return;
        }
        for (int i = 0; i < n; i++) {
            v[off + i] *= beta;
        }
    }
}
