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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The guards of {@link GuardedBlas} with a native stand-in that fails on purpose: one that throws, one that returns NaN, one that returns
 * wrong numbers, one that dies at load. Every answer is Java's; the breaker trips once, with the reason; nothing ever stops.
 */
class BlasResilienceTest {

    /** A stand-in for OpenBLAS: Java's answers, spoiled from call {@code after} on in the way asked. */
    static final class Spoiled implements LinearAlgebra {
        enum How { THROW, NAN, WRONG, LINK_ERROR, OUT_OF_MEMORY }

        final How how;
        final int after;
        final AtomicInteger calls = new AtomicInteger();

        Spoiled(How how, int after) {
            this.how = how;
            this.after = after;
        }

        @Override
        public String name() {
            return "openblas";
        }

        @Override
        public boolean isNative() {
            return true;
        }

        private boolean spoil() {
            return calls.incrementAndGet() > after;
        }

        private void after(double[] out, int off, int n) {
            switch (how) {
                case THROW -> throw new IllegalStateException("native fault");
                case LINK_ERROR -> throw new UnsatisfiedLinkError("cblas_dgemm");
                case OUT_OF_MEMORY -> throw new OutOfMemoryError("off-heap");
                case NAN -> out[off] = Double.NaN;
                case WRONG -> out[off + n - 1] += 1.0;
                default -> { }
            }
        }

        @Override
        public void dgemm(boolean ta, boolean tb, int m, int n, int k, double alpha, double[] a, int ao, int lda, double[] b, int bo, int ldb, double beta,
                double[] c, int co, int ldc) {
            JavaBlas.INSTANCE.dgemm(ta, tb, m, n, k, alpha, a, ao, lda, b, bo, ldb, beta, c, co, ldc);
            if (spoil()) {
                after(c, co, m);
            }
        }

        @Override
        public void dgemv(boolean t, int m, int n, double alpha, double[] a, int ao, int lda, double[] x, int xo, double beta, double[] y, int yo) {
            JavaBlas.INSTANCE.dgemv(t, m, n, alpha, a, ao, lda, x, xo, beta, y, yo);
            if (spoil()) {
                after(y, yo, t ? n : m);
            }
        }

        @Override
        public double ddot(int n, double[] x, int xo, double[] y, int yo) {
            double d = JavaBlas.INSTANCE.ddot(n, x, xo, y, yo);
            if (spoil()) {
                double[] v = {d};
                after(v, 0, 1);
                return v[0];
            }
            return d;
        }

        @Override
        public void daxpy(int n, double alpha, double[] x, int xo, double[] y, int yo) {
            JavaBlas.INSTANCE.daxpy(n, alpha, x, xo, y, yo);
            if (spoil()) {
                after(y, yo, n);
            }
        }
    }

    private static double[] gemm(LinearAlgebra la, double beta) {
        double[] a = Canary.fill(30 * 20, 1);
        double[] b = Canary.fill(20 * 10, 2);
        double[] c = Canary.fill(30 * 10, 3);
        la.dgemm(false, false, 30, 10, 20, 1.0, a, 0, 30, b, 0, 20, beta, c, 0, 30);
        return c;
    }

    @Test
    void aThrowingCallIsRedoneOnJavaAndTheBreakerTripsOnce() {
        for (Spoiled.How how : List.of(Spoiled.How.THROW, Spoiled.How.LINK_ERROR, Spoiled.How.OUT_OF_MEMORY)) {
            Spoiled s = new Spoiled(how, 0);
            GuardedBlas g = new GuardedBlas(s, JavaBlas.INSTANCE, 0);
            List<String> heard = new ArrayList<>();
            g.onTrip(heard::add);
            assertThat(gemm(g, 0.5)).containsExactly(gemm(JavaBlas.INSTANCE, 0.5));   // beta != 0: the caller's C restored before Java redid it
            assertThat(g.isNative()).isFalse();
            assertThat(g.tripped()).startsWith("dgemm failed: ");
            int before = s.calls.get();
            assertThat(gemm(g, 0)).containsExactly(gemm(JavaBlas.INSTANCE, 0));       // tripped: native is not called again
            assertThat(s.calls.get()).isEqualTo(before);
            assertThat(heard).hasSize(1);
        }
    }

    @Test
    void nanFromFiniteInputsIsADivergence() {
        GuardedBlas g = new GuardedBlas(new Spoiled(Spoiled.How.NAN, 0), JavaBlas.INSTANCE, 0);
        double[] y = new double[5];
        g.dgemv(false, 5, 4, 1.0, Canary.fill(20, 4), 0, 5, Canary.fill(4, 5), 0, 0.0, y, 0);
        double[] want = new double[5];
        JavaBlas.INSTANCE.dgemv(false, 5, 4, 1.0, Canary.fill(20, 4), 0, 5, Canary.fill(4, 5), 0, 0.0, want, 0);
        assertThat(y).containsExactly(want);
        assertThat(g.tripped()).contains("NaN or infinity from finite inputs");
        // NaN in, NaN out is not a divergence
        GuardedBlas h = new GuardedBlas(new Spoiled(Spoiled.How.NAN, 0), JavaBlas.INSTANCE, 0);
        double[] x = {Double.NaN, 1};
        h.ddot(2, x, 0, new double[] {1, 1}, 0);
        assertThat(h.tripped()).isNull();
    }

    @Test
    void wrongNumbersFailTheSelfTestAtStartAndAutoServesJava() {
        Blas.Binding b = Blas.select(new Blas.Settings(Blas.Mode.AUTO, null, 0, 0), () -> new Spoiled(Spoiled.How.WRONG, 0));
        assertThat(b.la()).isSameAs(JavaBlas.INSTANCE);
        assertThat(b.now().degraded()).isTrue();
        assertThat(b.now().reason()).startsWith("self-test failed: dgemm");
        assertThat(String.join("\n", b.now().banner())).contains("self-test failed");
    }

    @Test
    void theScheduledSelfTestTripsTheBreakerWhenNativeGoesWrongLater() {
        Spoiled s = new Spoiled(Spoiled.How.WRONG, 10_000);            // right at start, wrong later
        Blas.Binding b = Blas.select(new Blas.Settings(Blas.Mode.AUTO, null, 0, 0), () -> s);
        assertThat(b.now().isNative()).isTrue();
        assertThat(b.guard().selfTest()).isNull();
        s.calls.set(10_000);
        assertThat(b.guard().selfTest()).isNotNull();
        assertThat(b.now().isNative()).isFalse();
        assertThat(b.now().reason()).startsWith("OpenBLAS switched off for this run: self-test failed");
        assertThat(b.now().health()).isEqualTo("DEGRADED");
        assertThat(gemm(b.la(), 0)).containsExactly(gemm(JavaBlas.INSTANCE, 0));
    }

    @Test
    void aLoaderThatDiesNeverStopsTheServer() {
        Blas.Binding b = Blas.select(new Blas.Settings(Blas.Mode.AUTO, null, 0, 0), () -> {
            throw new UnsatisfiedLinkError("no jniopenblas_nolapack in java.library.path");
        });
        assertThat(b.la()).isSameAs(JavaBlas.INSTANCE);
        assertThat(b.now().reason()).contains("did not load").contains("UnsatisfiedLinkError");
    }

    @Test
    void badArgumentsStopInJavaBeforeAnyNativeCall() {
        Spoiled s = new Spoiled(Spoiled.How.THROW, 0);
        GuardedBlas g = new GuardedBlas(s, JavaBlas.INSTANCE, 0);
        double[] a = new double[6];
        double[] c = new double[4];
        assertThatThrownBy(() -> g.dgemm(false, false, 2, 2, 3, 1, a, 0, 2, new double[5], 0, 3, 0, c, 0, 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dgemm B");
        assertThatThrownBy(() -> g.dgemm(false, false, 2, 2, 3, 1, a, 1, 2, new double[6], 0, 3, 0, c, 0, 2))
                .hasMessageContaining("dgemm A");                         // the offset runs past the end
        assertThatThrownBy(() -> g.dgemm(false, false, 2, 2, 3, 1, a, 0, 1, new double[6], 0, 3, 0, c, 0, 2))
                .hasMessageContaining("leading dimension");
        assertThatThrownBy(() -> g.dgemm(false, false, -1, 2, 3, 1, a, 0, 2, new double[6], 0, 3, 0, c, 0, 2))
                .hasMessageContaining("negative dimension");
        assertThatThrownBy(() -> g.dgemm(false, false, 65_536, 65_536, 1, 1, a, 0, 65_536, a, 0, 1, 0, c, 0, 65_536))
                .hasMessageContaining("needs");                           // 2^32 values: counted in long, no overflow
        assertThatThrownBy(() -> g.ddot(3, new double[2], 0, new double[3], 0)).hasMessageContaining("ddot x");
        assertThatThrownBy(() -> g.dgemv(true, 3, 2, 1, new double[6], 0, 3, new double[2], 0, 0, new double[2], 0)).hasMessageContaining("dgemv x");
        assertThat(s.calls.get()).isZero();                              // not one reached the stand-in
        assertThat(g.tripped()).isNull();                                // a caller's mistake is not the library's
    }

    @Test
    void aCallLargerThanTheBoundRunsOnJava() {
        Spoiled s = new Spoiled(Spoiled.How.THROW, 0);
        GuardedBlas g = new GuardedBlas(s, JavaBlas.INSTANCE, 1_000);    // 1,000 bytes
        assertThat(gemm(g, 0)).containsExactly(gemm(JavaBlas.INSTANCE, 0));
        assertThat(s.calls.get()).isZero();
        assertThat(g.tripped()).isNull();
    }

    @Test
    void anAdministratorSwitchesToJavaAndBackLiveAfterTheSelfTest() {
        Blas.Settings st = new Blas.Settings(Blas.Mode.AUTO, null, 0, 0);
        Spoiled s = new Spoiled(Spoiled.How.WRONG, 1_000_000);              // right until told otherwise
        Blas.Binding before = Blas.swapForTest(Blas.select(st, () -> s), st);
        try {
            assertThat(Blas.get().isNative()).isTrue();
            Blas.switchToJava("switched off by alice at 2026-11-04T00:10:00Z");
            assertThat(Blas.get()).isSameAs(JavaBlas.INSTANCE);
            assertThat(Blas.forClusterSums()).isSameAs(JavaBlas.INSTANCE);
            Blas.Status chosen = Blas.status();
            assertThat(chosen.summary()).isEqualTo("java (chosen by admin: switched off by alice at 2026-11-04T00:10:00Z)");
            assertThat(chosen.degraded()).isFalse();                         // a notice, not the fallback warning
            assertThat(chosen.banner()).isEmpty();
            assertThat(Blas.switchToNative()).isNull();                      // the self-test passes: native again
            assertThat(Blas.get().isNative()).isTrue();
            assertThat(Blas.status().isNative()).isTrue();
            Blas.switchToJava("switched off by bob");
            s.calls.set(1_000_000);                                          // the library goes wrong meanwhile
            assertThat(Blas.switchToNative()).startsWith("OpenBLAS failed its self-test against Java (dgemm");
            assertThat(Blas.get()).isSameAs(JavaBlas.INSTANCE);              // refused in words: Java stays
            assertThat(Blas.adminJava()).isEqualTo("switched off by bob");
        } finally {
            Blas.swapForTest(before, null);
        }
    }

    @Test
    void configuredJavaRefusesTheSwitchToNativeInWords() {
        Blas.Settings st = new Blas.Settings(Blas.Mode.JAVA, null, 0, 0);
        Blas.Binding before = Blas.swapForTest(Blas.select(st), st);
        try {
            assertThat(Blas.switchToNative()).contains("drishti.native.blas.mode is java");
            assertThat(Blas.get()).isSameAs(JavaBlas.INSTANCE);
        } finally {
            Blas.swapForTest(before, null);
        }
    }

    @Test
    void theRealOpenBlasPassesTheSelfTest() {
        LinearAlgebra nb = Blas.nativeOrNull();
        org.junit.jupiter.api.Assumptions.assumeTrue(nb != null, "the bundled OpenBLAS does not load here");
        assertThat(Canary.selfTest(nb, JavaBlas.INSTANCE)).isNull();
    }
}
