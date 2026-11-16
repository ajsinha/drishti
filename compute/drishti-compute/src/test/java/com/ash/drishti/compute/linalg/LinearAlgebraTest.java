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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The two {@link LinearAlgebra} implementations: Java against a plain triple loop (to the bit where the order is the same), OpenBLAS
 * against Java (relative 1e-12; skipped where nothing is bundled), {@code java} mode loading nothing native (in a JVM of its own),
 * {@code auto} falling back loudly (never failing) where nothing is bundled.
 */
class LinearAlgebraTest {

    @Test
    void javaGemmEqualsThePlainLoopsToTheBit() {
        Random r = new Random(7);
        int m = 23;
        int k = 41;
        int n = 9;
        double[] a = random(r, m * k);
        double[] b = random(r, k * n);
        double[] c = new double[m * n];
        JavaBlas.INSTANCE.dgemm(false, false, m, n, k, 1.0, a, 0, m, b, 0, k, 0.0, c, 0, m);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < m; i++) {
                double s = 0;
                for (int l = 0; l < k; l++) {
                    s += b[l + j * k] * a[i + l * m];
                }
                assertThat(c[i + j * m]).isEqualTo(s);
            }
        }
        // transposes: A' B' against the same loops read the other way
        double[] ct = new double[m * n];
        JavaBlas.INSTANCE.dgemm(true, true, m, n, k, 1.0, a, 0, k, b, 0, n, 0.0, ct, 0, m);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < m; i++) {
                double s = 0;
                for (int l = 0; l < k; l++) {
                    s += a[l + i * k] * b[j + l * n];
                }
                assertThat(ct[i + j * m]).isEqualTo(s);
            }
        }
    }

    @Test
    void javaGemvAndDotKeepTheLoopOrder() {
        Random r = new Random(11);
        int m = 37;
        int n = 19;
        double[] a = random(r, m * n);
        double[] x = random(r, m);
        double[] y = new double[n];
        JavaBlas.INSTANCE.dgemv(true, m, n, 1.0, a, 0, m, x, 0, 0.0, y, 0);
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int l = 0; l < m; l++) {
                s += a[l + i * m] * x[l];
            }
            assertThat(y[i]).isEqualTo(s);
        }
        double d = 0;
        for (int i = 0; i < m; i++) {
            d += x[i] * a[i];
        }
        assertThat(JavaBlas.INSTANCE.ddot(m, x, 0, a, 0)).isEqualTo(d);
        double[] acc = x.clone();
        JavaBlas.INSTANCE.daxpy(m, 2.0, a, 0, acc, 0);
        assertThat(acc[3]).isEqualTo(x[3] + 2.0 * a[3]);
    }

    @Test
    void openBlasAgreesWithJavaWithinTheParityBound() {
        LinearAlgebra nb = Blas.nativeOrNull();
        assumeTrue(nb != null, "no OpenBLAS on this machine (set drishti.native.blas.library to try one)");
        assertThat(nb.isNative()).isTrue();
        assertThat(Canary.compare(nb, JavaBlas.INSTANCE)).isNull();
        Random r = new Random(3);
        int s = 509;
        int t = 700;
        int k = 37;
        double[] rows = random(r, s * t);
        double[] ind = new double[t * k];
        for (int i = 0; i < ind.length; i++) {
            ind[i] = r.nextInt(3) == 0 ? 1 : 0;
        }
        double[] cn = new double[s * k];
        double[] cj = new double[s * k];
        nb.dgemm(false, false, s, k, t, 1.0, rows, 0, s, ind, 0, t, 0.0, cn, 0, s);
        JavaBlas.INSTANCE.dgemm(false, false, s, k, t, 1.0, rows, 0, s, ind, 0, t, 0.0, cj, 0, s);
        assertThat(Canary.check("scope sums", cn, cj)).isNull();
    }

    @Test
    void javaModeLoadsNothingNative() throws IOException, InterruptedException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(java, "-Xmx64m", "-verbose:class", "-cp", System.getProperty("java.class.path"),
                "-Ddrishti.native.blas.mode=java", BlasProbe.class.getName()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(out).contains("probe: java 6.0");
        assertThat(out).doesNotContain("org.bytedeco").doesNotContain("com.ash.drishti.compute.linalg.NativeBlas");
    }

    @Test
    void autoFallsBackToJavaLoudlyWhereNothingIsBundledAndNeverThrows() {
        String os = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");
        String model = System.getProperty("sun.arch.data.model");
        try {
            System.setProperty("os.name", "Mac OS X");
            System.setProperty("os.arch", "aarch64");                      // Apple silicon: not tried, Java with the reason
            Blas.Status s = Blas.select(new Blas.Settings(Blas.Mode.AUTO, null, 0, 0)).status();
            assertThat(s.implementation()).isEqualTo("java");
            assertThat(s.degraded()).isTrue();
            assertThat(s.health()).isEqualTo("DEGRADED");
            assertThat(s.summary()).isEqualTo("java (no bundled OpenBLAS for macosx-aarch64 (bundled: linux-x86_64 and windows-x86_64))");
            assertThat(String.join("\n", s.banner())).contains("NATIVE BLAS NOT LOADED").contains("Reason: no bundled OpenBLAS for macosx-aarch64")
                    .contains("pure-Java path: results identical").contains("drishti.native.blas.mode: java");
            System.setProperty("os.name", "Linux");
            System.setProperty("os.arch", "amd64");
            System.setProperty("sun.arch.data.model", "32");                // a 32-bit JVM on x86-64
            assertThat(Blas.select(new Blas.Settings(Blas.Mode.AUTO, null, 0, 0)).status().reason()).isEqualTo(
                    "no bundled OpenBLAS for linux-amd64; a 32-bit JVM (a 64-bit Java 21 is needed)");
            System.setProperty("sun.arch.data.model", "64");
            System.setProperty("os.name", "Windows 11");
            System.setProperty("os.arch", "aarch64");                      // Windows on ARM
            assertThat(Blas.select(new Blas.Settings(Blas.Mode.AUTO, null, 0, 0)).status().reason()).contains("windows-aarch64");
        } finally {
            System.setProperty("os.name", os);
            System.setProperty("os.arch", arch);
            if (model != null) {
                System.setProperty("sun.arch.data.model", model);
            }
        }
        Blas.Status j = Blas.select(new Blas.Settings(Blas.Mode.JAVA, null, 0, 0)).status();       // chosen: no warning
        assertThat(j.degraded()).isFalse();
        assertThat(j.banner()).isEmpty();
        assertThat(j.health()).isEqualTo("OK");
        assertThat(j.line()).isEqualTo("BLAS: mode java, java (drishti.native.blas.mode is java)");
    }

    @Test
    void theBundledLibraryUnpacksUnderTheConfiguredCacheAndRunsOneThread() {
        assumeTrue(NativeBlas.bundledFor(System.getProperty("os.name"), System.getProperty("os.arch")), "no bundled build for this platform");
        LinearAlgebra nb = Blas.nativeOrNull();
        assumeTrue(nb != null, "the bundled OpenBLAS did not load here");
        String cache = System.getProperty("org.bytedeco.javacpp.cachedir");
        assertThat(cache).isNotNull().doesNotContain("/.javacpp");
        assertThat(((NativeBlas) nb).library()).startsWith(cache);
        assertThat(NativeBlas.threads()).isEqualTo(1);
        assertThat(((NativeBlas) nb).version()).isEqualTo("0.3.26");
    }

    @Test
    void modesAreAutoAndJavaAndAnUnknownWordIsAuto() {
        assertThat(List.of(Blas.Mode.of("auto"), Blas.Mode.of(" JAVA "), Blas.Mode.of(null), Blas.Mode.of("native")))
                .containsExactly(Blas.Mode.AUTO, Blas.Mode.JAVA, Blas.Mode.AUTO, Blas.Mode.AUTO);
        assertThat(Blas.Mode.known("native")).isFalse();
        assertThat(Blas.Mode.known("java")).isTrue();
    }

    @Test
    void bundledBuildsAreLinuxAndWindowsOnX86() {
        assertThat(NativeBlas.bundledFor("Linux", "amd64")).isTrue();
        assertThat(NativeBlas.bundledFor("Windows 11", "amd64")).isTrue();
        assertThat(NativeBlas.bundledFor("Mac OS X", "aarch64")).isFalse();
        assertThat(NativeBlas.bundledFor("Linux", "aarch64")).isFalse();
    }

    static double[] random(Random r, int n) {
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            v[i] = (r.nextDouble() - 0.5) * Math.pow(10, r.nextInt(6));
        }
        return v;
    }
}
