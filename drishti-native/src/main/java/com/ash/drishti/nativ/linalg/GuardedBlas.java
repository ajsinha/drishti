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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The native {@link LinearAlgebra} behind its guards (HIGH_PERFORMANCE_NATIVE.md section 6.5, Resilience): what callers get from
 * {@link Blas#get()} when OpenBLAS is bound.
 *
 * <ul>
 *   <li><b>Input guards</b>: dimensions, leading dimensions, offsets and array lengths are checked in Java, in {@code long} (no int
 *       overflow), before any native call; a bad argument is an {@link IllegalArgumentException} here and never reaches native code, where
 *       a fault would end the JVM;
 *   <li><b>Size bound</b>: a call whose operands pass {@code maxCallBytes} runs on Java (native copies stay bounded);
 *   <li><b>Per-call fallback</b>: any {@link Throwable} from the native call (a link error, an exception, an out-of-memory) redoes that call
 *       on Java, from the caller's original output, and trips the breaker;
 *   <li><b>Divergence</b>: a NaN or an infinity in the native output where the inputs had none redoes the call on Java and trips the breaker;
 *   <li><b>Circuit breaker</b>: once tripped, every later call runs on Java for the rest of the run; the listeners hear it once (the server
 *       logs the banner, raises the alert, turns health DEGRADED);
 *   <li><b>Canary</b>: {@link #selfTest()} at start and on a schedule ({@code drishti.native.blas.canary-interval}); a failure trips it.
 * </ul>
 */
public final class GuardedBlas implements LinearAlgebra {

    private final LinearAlgebra primary;
    private final LinearAlgebra fallback;
    private final long maxCallBytes;
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private volatile String tripped;

    /**
     * @param primary the native implementation
     * @param fallback {@link JavaBlas}
     * @param maxCallBytes the most operand bytes one native call may take (larger: Java); 0 for no bound
     */
    public GuardedBlas(LinearAlgebra primary, LinearAlgebra fallback, long maxCallBytes) {
        this.primary = primary;
        this.fallback = fallback;
        this.maxCallBytes = maxCallBytes;
    }

    /** Why the breaker tripped, or null while native serves. */
    public String tripped() {
        return tripped;
    }

    /** Told once, with the reason, when the breaker trips. */
    public void onTrip(Consumer<String> listener) {
        listeners.add(listener);
        String t = tripped;
        if (t != null) {
            listener.accept(t);
        }
    }

    /** Switches to Java for the rest of the run (once; the first reason stays). */
    public void trip(String reason) {
        synchronized (this) {
            if (tripped != null) {
                return;
            }
            tripped = reason;
        }
        for (Consumer<String> l : listeners) {
            try {
                l.accept(reason);
            } catch (RuntimeException e) {
                // a listener's failure never stops the fallback
            }
        }
    }

    /** The self-test against Java (start and the schedule): null when it passes; else the breaker trips and the reason is returned. */
    public String selfTest() {
        if (tripped != null) {
            return tripped;
        }
        String r;
        try {
            r = Canary.selfTest(primary, fallback);
        } catch (Throwable t) {
            r = "the self-test threw " + t;
        }
        if (r != null) {
            trip("self-test failed: " + r);
        }
        return r;
    }

    /**
     * The self-test again, whatever the breaker says (an administrator switching back to OpenBLAS): null when it passes, and the breaker is
     * closed again; else why not, and the breaker stays as it was.
     */
    public synchronized String retest() {
        String r;
        try {
            r = Canary.selfTest(primary, fallback);
        } catch (Throwable t) {
            r = "the self-test threw " + t;
        }
        if (r == null) {
            tripped = null;
        }
        return r;
    }

    @Override
    public String name() {
        return tripped == null ? primary.name() : fallback.name();
    }

    @Override
    public boolean isNative() {
        return tripped == null && primary.isNative();
    }

    @Override
    public void dgemm(boolean transA, boolean transB, int m, int n, int k, double alpha, double[] a, int aOff, int lda, double[] b, int bOff, int ldb,
            double beta, double[] c, int cOff, int ldc) {
        need(m >= 0 && n >= 0 && k >= 0, "dgemm: negative dimension " + m + " x " + n + " x " + k);
        long sa = matrix("dgemm A", a, aOff, lda, transA ? k : m, transA ? m : k);
        long sb = matrix("dgemm B", b, bOff, ldb, transB ? n : k, transB ? k : n);
        long sc = matrix("dgemm C", c, cOff, ldc, m, n);
        if (m == 0 || n == 0) {
            return;
        }
        if (useJava(sa + sb + sc)) {
            fallback.dgemm(transA, transB, m, n, k, alpha, a, aOff, lda, b, bOff, ldb, beta, c, cOff, ldc);
            return;
        }
        double[] keep = beta == 0 ? null : c.clone();
        try {
            primary.dgemm(transA, transB, m, n, k, alpha, a, aOff, lda, b, bOff, ldb, beta, c, cOff, ldc);
            if (!finite(c, cOff, ldc, m, n) && finite(a, aOff, lda, transA ? k : m, transA ? m : k) && finite(b, bOff, ldb, transB ? n : k, transB ? k : n)
                    && (keep == null || finite(keep, cOff, ldc, m, n)) && Double.isFinite(alpha) && Double.isFinite(beta)) {
                throw new Divergence("dgemm gave NaN or infinity from finite inputs");
            }
        } catch (Throwable t) {
            restore(keep, c);
            failed("dgemm", t);
            fallback.dgemm(transA, transB, m, n, k, alpha, a, aOff, lda, b, bOff, ldb, beta, c, cOff, ldc);
        }
    }

    @Override
    public void dgemv(boolean trans, int m, int n, double alpha, double[] a, int aOff, int lda, double[] x, int xOff, double beta, double[] y, int yOff) {
        need(m >= 0 && n >= 0, "dgemv: negative dimension " + m + " x " + n);
        int lx = trans ? m : n;
        int ly = trans ? n : m;
        long s = matrix("dgemv A", a, aOff, lda, m, n) + vector("dgemv x", x, xOff, lx) + vector("dgemv y", y, yOff, ly);
        if (m == 0 || n == 0) {
            return;
        }
        if (useJava(s)) {
            fallback.dgemv(trans, m, n, alpha, a, aOff, lda, x, xOff, beta, y, yOff);
            return;
        }
        double[] keep = beta == 0 ? null : y.clone();
        try {
            primary.dgemv(trans, m, n, alpha, a, aOff, lda, x, xOff, beta, y, yOff);
            if (!finite(y, yOff, ly, ly, 1) && finite(a, aOff, lda, m, n) && finite(x, xOff, lx, lx, 1) && (keep == null || finite(keep, yOff, ly, ly, 1))
                    && Double.isFinite(alpha) && Double.isFinite(beta)) {
                throw new Divergence("dgemv gave NaN or infinity from finite inputs");
            }
        } catch (Throwable t) {
            restore(keep, y);
            failed("dgemv", t);
            fallback.dgemv(trans, m, n, alpha, a, aOff, lda, x, xOff, beta, y, yOff);
        }
    }

    @Override
    public double ddot(int n, double[] x, int xOff, double[] y, int yOff) {
        need(n >= 0, "ddot: negative length " + n);
        long s = vector("ddot x", x, xOff, n) + vector("ddot y", y, yOff, n);
        if (n == 0) {
            return 0;
        }
        if (useJava(s)) {
            return fallback.ddot(n, x, xOff, y, yOff);
        }
        try {
            double d = primary.ddot(n, x, xOff, y, yOff);
            if (!Double.isFinite(d) && finite(x, xOff, n, n, 1) && finite(y, yOff, n, n, 1)) {
                throw new Divergence("ddot gave NaN or infinity from finite inputs");
            }
            return d;
        } catch (Throwable t) {
            failed("ddot", t);
            return fallback.ddot(n, x, xOff, y, yOff);
        }
    }

    @Override
    public void daxpy(int n, double alpha, double[] x, int xOff, double[] y, int yOff) {
        need(n >= 0, "daxpy: negative length " + n);
        long s = vector("daxpy x", x, xOff, n) + vector("daxpy y", y, yOff, n);
        if (n == 0) {
            return;
        }
        if (useJava(s)) {
            fallback.daxpy(n, alpha, x, xOff, y, yOff);
            return;
        }
        double[] keep = y.clone();
        try {
            primary.daxpy(n, alpha, x, xOff, y, yOff);
            if (!finite(y, yOff, n, n, 1) && finite(x, xOff, n, n, 1) && finite(keep, yOff, n, n, 1) && Double.isFinite(alpha)) {
                throw new Divergence("daxpy gave NaN or infinity from finite inputs");
            }
        } catch (Throwable t) {
            restore(keep, y);
            failed("daxpy", t);
            fallback.daxpy(n, alpha, x, xOff, y, yOff);
        }
    }

    // ---- the guards ------------------------------------------------------------------------------------------------------------

    private boolean useJava(long bytes) {
        return tripped != null || maxCallBytes > 0 && bytes > maxCallBytes;
    }

    /** Any throwable of a native call, even an out-of-memory or a link error: the breaker trips and Java redoes the call. */
    private void failed(String call, Throwable t) {
        trip(call + " failed: " + t);
    }

    private static void restore(double[] keep, double[] out) {
        if (keep != null) {
            System.arraycopy(keep, 0, out, 0, out.length);
        }
    }

    private static void need(boolean ok, String what) {
        if (!ok) {
            throw new IllegalArgumentException(what);
        }
    }

    /** Checks a column-major {@code rows x cols} matrix at {@code off} with leading dimension {@code ld} fits {@code v}; its bytes. */
    static long matrix(String what, double[] v, int off, int ld, int rows, int cols) {
        need(v != null, what + " is null");
        need(off >= 0, what + ": negative offset " + off);
        need(ld >= Math.max(1, rows), what + ": leading dimension " + ld + " below its " + rows + " rows");
        if (rows == 0 || cols == 0) {
            return 0;
        }
        long last = (long) off + (long) (cols - 1) * ld + rows;           // one past the last element, in long: no overflow
        need(last <= v.length, what + ": " + rows + " x " + cols + " with ld " + ld + " from " + off + " needs " + last + " values, the array has "
                + v.length);
        return 8L * rows * cols;
    }

    static long vector(String what, double[] v, int off, int n) {
        return matrix(what, v, off, Math.max(1, n), n, 1);
    }

    /** True when the {@code rows x cols} values at {@code off} (leading dimension {@code ld}) are all finite. */
    static boolean finite(double[] v, int off, int ld, int rows, int cols) {
        for (int j = 0; j < cols; j++) {
            int o = off + j * ld;
            for (int i = 0; i < rows; i++) {
                if (!Double.isFinite(v[o + i])) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Native output that cannot be right (NaN or infinity from finite inputs). */
    static final class Divergence extends RuntimeException {
        Divergence(String m) {
            super(m);
        }
    }
}
