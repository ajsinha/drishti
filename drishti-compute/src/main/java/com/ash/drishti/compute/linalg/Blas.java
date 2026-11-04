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

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The process's one {@link LinearAlgebra}, chosen once at start from {@code drishti.native.blas.mode} (HIGH_PERFORMANCE_NATIVE.md
 * section 6.3):
 *
 * <ul>
 *   <li>{@code auto} (the default): the bundled OpenBLAS when it loads and passes its check; else Java, <b>loudly</b>: the status is
 *       {@code DEGRADED} with the reason, the server logs a warning banner, raises an alert and shows a notice to administrators;
 *   <li>{@code java}: Java only, chosen: nothing native is loaded and nothing warns.
 * </ul>
 *
 * <p>Nothing about BLAS ever stops the server (the product owner, 2026-11-03): an unknown mode is read as {@code auto} and said in the
 * status; a platform with no bundled build (anything but 64-bit Linux or Windows on x86-64) is not even tried.
 *
 * <p>Results do not depend on the choice beyond the parity bound (relative 1e-12; the Java kernels of the sums are exact against the
 * code before BLAS). The server binds at start ({@link #bind}); anything that asks first ({@link #get}, a tool, a test) binds from the
 * system properties {@code drishti.native.blas.mode} and {@code drishti.native.cache-dir} (or {@code DRISHTI_NATIVE_BLAS_MODE},
 * {@code DRISHTI_NATIVE_CACHE_DIR}; else {@code <drishti.data.dir>/native-cache}).
 */
public final class Blas {

    /** {@code drishti.native.blas.mode}. */
    public enum Mode {
        AUTO, JAVA;

        /** {@code java}, else {@code auto} (blank, {@code auto}, or a word it does not know: see {@link #known}). */
        public static Mode of(String s) {
            return s != null && s.trim().equalsIgnoreCase("java") ? JAVA : AUTO;
        }

        /** True for blank, {@code auto} and {@code java}; anything else is read as {@code auto} and said in the status. */
        public static boolean known(String s) {
            return s == null || s.isBlank() || s.trim().equalsIgnoreCase("auto") || s.trim().equalsIgnoreCase("java");
        }

        public String text() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * What was chosen and why.
     *
     * @param mode the mode asked
     * @param implementation {@code openblas} or {@code java}
     * @param library the native library loaded (in the JavaCPP cache), or null
     * @param version the bundled OpenBLAS's version, or null
     * @param threads OpenBLAS's threads per call (1), or 0 on Java
     * @param reason why Java serves (chosen, or what failed in {@code auto}), or null
     */
    public record Status(Mode mode, String implementation, String library, String version, int threads, String reason) {

        public boolean isNative() {
            return "openblas".equals(implementation);
        }

        /** {@code auto} fell back to Java: warn, alert, notice, health {@code DEGRADED}. {@code java} chosen is not degraded. */
        public boolean degraded() {
            return mode == Mode.AUTO && !isNative();
        }

        /** {@code OK} or {@code DEGRADED} (never down: the Java path gives the same results, slower). */
        public String health() {
            return degraded() ? "DEGRADED" : "OK";
        }

        /** {@code native (bundled OpenBLAS 0.3.26)} or {@code java (reason)}: the status line, the log and the health row. */
        public String summary() {
            return isNative() ? "native (bundled OpenBLAS" + (version == null ? "" : " " + version) + ")" : "java (" + reason + ")";
        }

        /** The start-up log line and {@code drishti.py server health}'s BLAS line. */
        public String line() {
            return "BLAS: mode " + mode.text() + ", " + summary() + (isNative() ? ", " + threads + " thread per call" : "");
        }

        /**
         * The warning banner of an {@code auto} fallback (logged at WARN, one line each): what failed, why, what it means, how to fix it,
         * how to silence it. Empty when not degraded.
         */
        public List<String> banner() {
            if (!degraded()) {
                return List.of();
            }
            return List.of(
                    "************************************************************************************************",
                    "NATIVE BLAS NOT LOADED: drishti.native.blas.mode is auto and the bundled OpenBLAS could not be used.",
                    "Reason: " + reason,
                    "Running on the pure-Java path: results identical (within 1e-12), slower for many-scope VaR/ES, exposure and FRTB.",
                    "Fix: a 64-bit Java on Linux or Windows x86-64; the native cache writable (drishti.native.cache-dir, default",
                    "     <data>/native-cache); the openblas jars in the server's lib folder. See docs/admin/OPERATIONS.md section 2.1.",
                    "Silence: set drishti.native.blas.mode: java (Java chosen: no warning).",
                    "************************************************************************************************");
        }

        /** For Admin -> Health and {@code GET /api/v1/admin/health} ({@code native.blas}). */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", health());
            m.put("mode", mode.text());
            m.put("implementation", implementation);
            m.put("native", isNative());
            m.put("library", library);
            m.put("version", version);
            m.put("threads", threads);
            m.put("reason", reason);
            m.put("summary", summary());
            m.put("line", line());
            return m;
        }
    }

    record Binding(LinearAlgebra la, Status status) {}

    private static volatile Binding bound;

    private Blas() {}

    /** The bound implementation (binding from the system properties if nothing bound yet). */
    public static LinearAlgebra get() {
        Binding b = bound;
        return b != null ? b.la() : bindDefault().la();
    }

    /** What is bound (binding from the system properties if nothing bound yet). */
    public static Status status() {
        Binding b = bound;
        return b != null ? b.status() : bindDefault().status();
    }

    /**
     * Binds once for the process. A second call with the same mode returns the same status; with another it keeps the first binding (said
     * in the returned status's reason), because a JNI library cannot be swapped and every sum must use one path.
     *
     * @param cacheDir where the bundled library is unpacked ({@code drishti.native.cache-dir}, under the data folder)
     * Never throws: whatever fails, Java serves.
     */
    public static synchronized Status bind(Mode mode, Path cacheDir) {
        if (bound != null) {
            Status s = bound.status();
            if (s.mode() == mode) {
                return s;
            }
            return new Status(s.mode(), s.implementation(), s.library(), s.version(), s.threads(),
                    "already bound in mode " + s.mode().text() + " before " + mode.text() + " was asked; restart to change it");
        }
        Binding b = select(mode, cacheDir);
        bound = b;
        return b.status();
    }

    private static synchronized Binding bindDefault() {
        if (bound == null) {
            bound = select(Mode.of(setting("drishti.native.blas.mode", "DRISHTI_NATIVE_BLAS_MODE")), defaultCacheDir());
        }
        return bound;
    }

    /** {@code drishti.native.cache-dir}, else {@code <drishti.data.dir>/native-cache} (never a hidden folder in the home). */
    public static Path defaultCacheDir() {
        String c = setting("drishti.native.cache-dir", "DRISHTI_NATIVE_CACHE_DIR");
        if (c != null && !c.isBlank()) {
            return Path.of(c);
        }
        String data = System.getProperty("drishti.data.dir");
        return Path.of(data == null || data.isBlank() ? "./data" : data, "native-cache");
    }

    private static String setting(String property, String env) {
        String p = System.getProperty(property);
        return p != null ? p : System.getenv(env);
    }

    /** The choice of {@link #bind}, without binding (tests ask it for each mode). */
    static Binding select(Mode mode, Path cacheDir) {
        if (mode == Mode.JAVA) {
            return new Binding(JavaBlas.INSTANCE, new Status(mode, "java", null, null, 0, "drishti.native.blas.mode is java"));
        }
        String platform = NativeBlas.unsupported(System.getProperty("os.name", ""), System.getProperty("os.arch", ""),
                System.getProperty("sun.arch.data.model"));
        if (platform != null) {                                           // 32-bit, ARM, macOS...: nothing bundled, nothing tried
            return new Binding(JavaBlas.INSTANCE, new Status(mode, "java", null, null, 0, platform));
        }
        try {
            NativeBlas nb = NativeBlas.load(cacheDir);
            return new Binding(nb, new Status(mode, "openblas", nb.library(), nb.version(), NativeBlas.threads(), null));
        } catch (BlasUnavailableException e) {
            return new Binding(JavaBlas.INSTANCE, new Status(mode, "java", null, null, 0, e.reason()));
        } catch (RuntimeException | LinkageError e) {                    // never stops the server
            return new Binding(JavaBlas.INSTANCE, new Status(mode, "java", null, null, 0, "the bundled OpenBLAS did not load: " + e));
        }
    }

    /** The bundled OpenBLAS for a test, or null where it cannot load (the test then skips its native half). */
    public static LinearAlgebra nativeOrNull() {
        try {
            return NativeBlas.load(defaultCacheDir());
        } catch (BlasUnavailableException e) {
            return null;
        }
    }
}
