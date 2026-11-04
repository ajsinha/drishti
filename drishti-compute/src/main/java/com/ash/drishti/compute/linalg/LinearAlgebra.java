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
 * Dense linear algebra on {@code double[]}: the few BLAS routines Drishti's hot paths use (HIGH_PERFORMANCE_NATIVE.md section 6). Two
 * implementations: {@link NativeBlas} (OpenBLAS through JNI, {@code dev.ludovic.netlib}) and {@link JavaBlas} (pure Java); the binding is
 * chosen once at start ({@link Blas}) and every caller sees only this interface.
 *
 * <p><b>Layout: column-major</b>, as in Fortran BLAS. A matrix {@code m x n} with leading dimension {@code ld >= m} holds element
 * {@code (i, j)} at {@code a[off + i + j * ld]}. A row-major {@code r x c} array is therefore the column-major {@code c x r} matrix
 * (its transpose) with {@code ld = c}. Vectors have unit stride.
 *
 * <p>Every routine is safe to call from many threads at once; none keeps state between calls.
 */
public interface LinearAlgebra {

    /** What answers: {@code java} or {@code openblas}. */
    String name();

    /** True when the routines run in a native library. */
    boolean isNative();

    /**
     * {@code C = alpha * op(A) * op(B) + beta * C}, {@code op(X)} = X or its transpose; {@code C} is {@code m x n}, {@code op(A)}
     * {@code m x k}, {@code op(B)} {@code k x n}. {@code beta == 0} ignores what {@code C} held (no NaN carried over).
     */
    void dgemm(boolean transA, boolean transB, int m, int n, int k, double alpha, double[] a, int aOff, int lda, double[] b, int bOff, int ldb,
            double beta, double[] c, int cOff, int ldc);

    /**
     * {@code y = alpha * op(A) * x + beta * y}; {@code A} is {@code m x n}, so {@code x} has {@code n} values and {@code y} {@code m}
     * ({@code trans}: the other way round). {@code beta == 0} ignores what {@code y} held.
     */
    void dgemv(boolean trans, int m, int n, double alpha, double[] a, int aOff, int lda, double[] x, int xOff, double beta, double[] y, int yOff);

    /** {@code sum x[i] * y[i]} over {@code n} values, in index order. */
    double ddot(int n, double[] x, int xOff, double[] y, int yOff);

    /** {@code y += alpha * x} over {@code n} values. */
    void daxpy(int n, double alpha, double[] x, int xOff, double[] y, int yOff);
}
