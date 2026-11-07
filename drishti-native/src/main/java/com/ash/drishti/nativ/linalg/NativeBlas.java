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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.openblas.global.openblas_nolapack;

/**
 * The native {@link LinearAlgebra}: OpenBLAS <b>bundled</b> with Drishti through JavaCPP ({@code org.bytedeco:openblas}, the
 * {@code linux-x86_64} and {@code windows-x86_64} builds; nothing to install on a server). The <b>only</b> class of Drishti that names
 * {@code org.bytedeco} (ArchitectureRulesTest); built only by {@link Blas}.
 *
 * <p>Loaded once per process. JavaCPP unpacks the library into its cache, which Drishti puts under the data folder
 * ({@code drishti.native.cache-dir}, default {@code <data>/native-cache}; the property {@code org.bytedeco.javacpp.cachedir}), never a
 * hidden folder in the user's home. Once loaded, OpenBLAS runs <b>one thread per call</b> ({@code openblas_set_num_threads(1)}): Drishti's
 * own pools parallelise. Before it is used {@link GuardedBlas#selfTest()} must agree with {@link JavaBlas} to a relative 1e-12, and it is only
 * ever called through {@link GuardedBlas}. Other
 * platforms (macOS, ARM) have no bundled build here: {@code auto} serves Java there.
 *
 * <p>JavaCPP passes {@code double[]} without offsets, so an operand at an offset other than 0 is copied (Drishti's callers pass 0).
 */
public final class NativeBlas implements LinearAlgebra {

    private final String library;
    private final String version;

    private NativeBlas(String library, String version) {
        this.library = library;
        this.version = version;
    }

    /** The JNI library JavaCPP loaded (in its cache). */
    public String library() {
        return library;
    }

    /** The bundled OpenBLAS's version ({@code 0.3.26}), or null. */
    public String version() {
        return version;
    }

    @Override
    public String name() {
        return "openblas";
    }

    @Override
    public boolean isNative() {
        return true;
    }

    @Override
    public void dgemm(boolean transA, boolean transB, int m, int n, int k, double alpha, double[] a, int aOff, int lda, double[] b, int bOff, int ldb,
            double beta, double[] c, int cOff, int ldc) {
        if (m == 0 || n == 0) {
            return;
        }
        int ka = transA ? m : k;                                            // columns of A as stored
        int kb = transB ? k : n;
        double[] aa = at(a, aOff, lda, ka);
        double[] bb = at(b, bOff, ldb, kb);
        double[] cc = at(c, cOff, ldc, n);
        openblas_nolapack.cblas_dgemm(openblas_nolapack.CblasColMajor, transA ? openblas_nolapack.CblasTrans : openblas_nolapack.CblasNoTrans,
                transB ? openblas_nolapack.CblasTrans : openblas_nolapack.CblasNoTrans, m, n, k, alpha, aa, Math.max(1, lda), bb, Math.max(1, ldb),
                beta, cc, Math.max(1, ldc));
        back(cc, c, cOff, ldc, n);
    }

    @Override
    public void dgemv(boolean trans, int m, int n, double alpha, double[] a, int aOff, int lda, double[] x, int xOff, double beta, double[] y, int yOff) {
        if (m == 0 || n == 0) {
            return;
        }
        double[] aa = at(a, aOff, lda, n);
        double[] xx = at(x, xOff, trans ? m : n, 1);
        double[] yy = at(y, yOff, trans ? n : m, 1);
        openblas_nolapack.cblas_dgemv(openblas_nolapack.CblasColMajor, trans ? openblas_nolapack.CblasTrans : openblas_nolapack.CblasNoTrans, m, n,
                alpha, aa, Math.max(1, lda), xx, 1, beta, yy, 1);
        back(yy, y, yOff, trans ? n : m, 1);
    }

    @Override
    public double ddot(int n, double[] x, int xOff, double[] y, int yOff) {
        return n <= 0 ? 0 : openblas_nolapack.cblas_ddot(n, at(x, xOff, n, 1), 1, at(y, yOff, n, 1), 1);
    }

    @Override
    public void daxpy(int n, double alpha, double[] x, int xOff, double[] y, int yOff) {
        if (n <= 0) {
            return;
        }
        double[] yy = at(y, yOff, n, 1);
        openblas_nolapack.cblas_daxpy(n, alpha, at(x, xOff, n, 1), 1, yy, 1);
        back(yy, y, yOff, n, 1);
    }

    /** {@code v} itself at offset 0, else a copy of the {@code cols x ld} values from {@code off}. */
    private static double[] at(double[] v, int off, int ld, int cols) {
        if (off == 0) {
            return v;
        }
        return java.util.Arrays.copyOfRange(v, off, Math.min(v.length, off + Math.max(1, ld) * cols));
    }

    private static void back(double[] copy, double[] v, int off, int ld, int cols) {
        if (copy != v) {
            System.arraycopy(copy, 0, v, off, copy.length);
        }
    }

    // ---- loading ---------------------------------------------------------------------------------------------------------------

    private static NativeBlas loaded;
    private static BlasUnavailableException failed;

    /** The platforms with a bundled build (the jars Drishti ships): 64-bit Java on Linux or Windows, x86-64. */
    static boolean bundledFor(String os, String arch) {
        return unsupported(os, arch, null) == null;
    }

    /**
     * Null when the bundled build fits this platform; else why not, naming it ({@code no bundled OpenBLAS for linux-aarch64},
     * {@code ...; 32-bit JVM}). Checked before anything is loaded.
     *
     * @param dataModel {@code sun.arch.data.model} ({@code 64}, {@code 32}; null: from the architecture)
     */
    static String unsupported(String os, String arch, String dataModel) {
        String o = os.toLowerCase(Locale.ROOT);
        String a = arch.toLowerCase(Locale.ROOT);
        String family = o.startsWith("linux") ? "linux" : o.startsWith("windows") ? "windows" : o.contains("mac") ? "macosx" : o.replace(' ', '_');
        String platform = family + "-" + a;
        boolean x64 = a.equals("amd64") || a.equals("x86_64");
        if ("32".equals(dataModel)) {
            return "no bundled OpenBLAS for " + platform + "; a 32-bit JVM (a 64-bit Java 21 is needed)";
        }
        if (!x64 || !(family.equals("linux") || family.equals("windows"))) {
            return "no bundled OpenBLAS for " + platform + " (bundled: linux-x86_64 and windows-x86_64)";
        }
        return null;
    }

    /**
     * The bundled OpenBLAS, loaded and checked; the same instance for the life of the process (a JNI library cannot be loaded twice).
     *
     * @param cacheDir where JavaCPP unpacks the library (under the data folder); null keeps a cache folder already set
     * @throws BlasUnavailableException with the reason, when it cannot be used
     */
    static synchronized NativeBlas load(Path cacheDir) {
        return load(cacheDir, 0);
    }

    /**
     * {@link #load(Path)}, bounding JavaCPP's own off-heap allocations ({@code org.bytedeco.javacpp.maxBytes}) to {@code maxNativeBytes}
     * (0: JavaCPP's default) and turning off its resident-memory check ({@code maxPhysicalBytes}: the server's heap and caches are counted
     * there and would refuse allocations for the wrong reason). Drishti hands JavaCPP Java arrays, bounded per call by
     * {@link GuardedBlas}.
     */
    static synchronized NativeBlas load(Path cacheDir, long maxNativeBytes) {
        if (loaded == null && failed == null) {
            if (maxNativeBytes > 0 && System.getProperty("org.bytedeco.javacpp.maxBytes") == null) {
                System.setProperty("org.bytedeco.javacpp.maxBytes", Long.toString(maxNativeBytes));
            }
            if (System.getProperty("org.bytedeco.javacpp.maxPhysicalBytes") == null) {
                System.setProperty("org.bytedeco.javacpp.maxPhysicalBytes", "0");
            }
        }
        return loadOnce(cacheDir);
    }

    private static synchronized NativeBlas loadOnce(Path cacheDir) {   // pinning-ok: once per JVM, at the first use, never per request
        if (loaded != null) {
            return loaded;
        }
        if (failed != null) {
            throw failed;                                                  // JavaCPP tries once per process: the first reason stays
        }
        String why = unsupported(System.getProperty("os.name", ""), System.getProperty("os.arch", ""), System.getProperty("sun.arch.data.model"));
        if (why != null) {
            throw new BlasUnavailableException(why, null);
        }
        try {
            if (cacheDir != null && System.getProperty("org.bytedeco.javacpp.cachedir") == null) {
                Files.createDirectories(cacheDir);
                System.setProperty("org.bytedeco.javacpp.cachedir", cacheDir.toAbsolutePath().toString());
            }
            String lib = Loader.load(openblas_nolapack.class);
            openblas_nolapack.blas_set_num_threads(1);                    // openblas_set_num_threads(1): Drishti's pools own parallelism
            NativeBlas nb = new NativeBlas(lib, bundledVersion());       // checked against Java by Blas (GuardedBlas.selfTest) before use
            loaded = nb;
            return nb;
        } catch (BlasUnavailableException e) {
            failed = e;
            throw e;
        } catch (IOException | RuntimeException | LinkageError e) {
            failed = new BlasUnavailableException("the bundled OpenBLAS did not load: " + e, e);
            throw failed;
        }
    }

    /** OpenBLAS's threads per call as it reports them (1 once loaded). */
    static int threads() {
        return openblas_nolapack.blas_get_num_threads();
    }

    /** {@code 0.3.26} from the preset's own version ({@code 0.3.26-1.5.10}). */
    static String bundledVersion() {
        try (InputStream in = openblas_nolapack.class.getResourceAsStream("/META-INF/maven/org.bytedeco/openblas/pom.properties")) {
            if (in == null) {
                return null;
            }
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version");
            return v == null ? null : v.replaceFirst("-\\d+\\.\\d+\\.\\d+$", "");
        } catch (IOException e) {
            return null;
        }
    }
}
