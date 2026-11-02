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
package com.ash.drishti.bicompute.custom;

import com.ash.drishti.bicompute.spi.AggFunction;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java plug-in functions (PIVOT_ENGINE.md section 8.2): a jar in the plug-in folder ({@code functions.java.dir}), pinned by its SHA-256 when
 * an administrator approves it (as compute's Java libraries are), holding a public class with a no-argument constructor that implements
 * {@link AggFunction}. The jar is read only when its bytes still have the pinned digest; each jar gets its own class loader, kept while the
 * server runs. A plug-in runs in the server: it is reviewed code, approved by an administrator.
 */
public final class JavaPlugins {

    private final Path dir;
    private final Map<String, ClassLoader> loaders = new ConcurrentHashMap<>();

    public JavaPlugins(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    /** The SHA-256 of a jar in the folder, hex. */
    public String digest(String jar) {
        Path p = jarPath(jar);
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_INVALID, "the plug-in jar " + jar + " cannot be read in " + dir + ": " + e.getMessage());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path jarPath(String jar) {
        if (jar == null || !jar.matches("[A-Za-z0-9._-]+\\.jar")) {
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_INVALID, "a plug-in is a jar's file name in the plug-in folder (name.jar), not '" + jar + "'");
        }
        Path p = dir.resolve(jar).normalize();
        if (!p.startsWith(dir.normalize()) || !Files.isRegularFile(p)) {
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_INVALID, "no plug-in jar " + jar + " in " + dir);
        }
        return p;
    }

    /** A new instance of the plug-in's function, the jar checked against {@code sha256} first (null: not pinned yet, the playground only). */
    @SuppressWarnings("unchecked")
    public AggFunction<Object> load(String jar, String className, String sha256) {
        String actual = digest(jar);
        if (sha256 != null && !sha256.isBlank() && !sha256.equalsIgnoreCase(actual)) {
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_APPROVAL, "the plug-in jar " + jar + " changed since it was approved (SHA-256 "
                    + actual.substring(0, 12) + "..., approved " + sha256.substring(0, Math.min(12, sha256.length())) + "...): approve the new jar");
        }
        ClassLoader cl = loaders.computeIfAbsent(actual, k -> {
            try {
                return new URLClassLoader("pivot-fn-" + jar, new URL[] {jarPath(jar).toUri().toURL()}, AggFunction.class.getClassLoader());
            } catch (IOException e) {
                throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_INVALID, "the plug-in jar " + jar + " cannot be opened: " + e.getMessage());
            }
        });
        try {
            Class<?> c = Class.forName(className, true, cl);
            if (!AggFunction.class.isAssignableFrom(c)) {
                throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_INVALID, className + " does not implement " + AggFunction.class.getName());
            }
            return (AggFunction<Object>) c.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_INVALID, "the plug-in class " + className + " cannot be made from " + jar + ": "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }
}
