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
package com.ash.drishti.compute.env;

import com.ash.drishti.compute.approval.ApprovalItem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a connector (or a pool) needs to run (docs/architecture/PYTHON_COMPUTE.md section 4): a locked Python environment, Java libraries
 * pinned by sha256, and connector plugins. Parsed from the {@code requires:} block; nothing in it is fetched, built or run until an
 * administrator approves it ({@link #items}).
 *
 * @param python the Python part, or null
 * @param java the Java libraries
 * @param plugins the connector plugins the computation depends on (checked, never installed)
 */
public record Requires(Python python, List<JavaLib> java, List<String> plugins) {

    /**
     * @param version the interpreter's {@code major.minor}
     * @param lock the lock file's path (relative to the folder of the file that names it)
     * @param index {@code shipped}, a named index of {@code compute.indexes}, or {@code path:<folder>}
     */
    public record Python(String version, String lock, String index) {}

    /**
     * @param coords {@code group:artifact:version}
     * @param sha256 the jar's hash
     * @param classifier the jar's classifier (empty for none)
     * @param source {@code shipped} or {@code bank-maven}
     * @param nativeCode the jar carries a native library: Java worker only, never in the server
     */
    public record JavaLib(String coords, String sha256, String classifier, String source, boolean nativeCode) {
        public String group() {
            return coords.split(":")[0];
        }

        public String artifact() {
            return coords.split(":")[1];
        }

        public String version() {
            return coords.split(":")[2];
        }

        /** The jar's file name: {@code artifact-version[-classifier].jar}. */
        public String fileName() {
            return artifact() + "-" + version() + (classifier.isEmpty() ? "" : "-" + classifier) + ".jar";
        }
    }

    public Requires {
        java = java == null ? List.of() : List.copyOf(java);
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
    }

    public boolean isEmpty() {
        return python == null && java.isEmpty() && plugins.isEmpty();
    }

    /** Parses a {@code requires:} mapping; {@code where} names it in errors. */
    @SuppressWarnings("unchecked")
    public static Requires parse(Object raw, String where) {
        if (raw == null) {
            return new Requires(null, List.of(), List.of());
        }
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException(where + ".requires is a mapping (python, java, plugins)");
        }
        for (Object k : m.keySet()) {
            if (!List.of("python", "java", "plugins", "requested-by").contains(String.valueOf(k))) {
                throw new IllegalArgumentException(where + ".requires: unknown key '" + k + "' (allowed: python, java, plugins)");
            }
        }
        Python py = null;
        Object p = m.get("python");
        if (p != null) {
            if (!(p instanceof Map<?, ?> pm)) {
                throw new IllegalArgumentException(where + ".requires.python is a mapping (version, lock, index)");
            }
            if (pm.get("version") instanceof Number n) {
                throw new IllegalArgumentException(where + ".requires.python.version is " + n + " as a number: write it in quotes, such as \"3.10\"");
            }
            String version = str(pm.get("version"));
            String lock = str(pm.get("lock"));
            String index = str(pm.get("index"));
            if (version.isEmpty() || !version.matches("3\\.\\d{1,2}")) {
                throw new IllegalArgumentException(where + ".requires.python.version is the interpreter's major.minor, such as \"3.12\", not '" + version + "'");
            }
            if (lock.isEmpty()) {
                throw new IllegalArgumentException(where + ".requires.python.lock names the lock file");
            }
            py = new Python(version, lock, index.isEmpty() ? "shipped" : index);
        }
        List<JavaLib> java = new ArrayList<>();
        Object j = m.get("java");
        if (j != null) {
            if (!(j instanceof List<?> jl)) {
                throw new IllegalArgumentException(where + ".requires.java is a list");
            }
            int i = 0;
            for (Object o : jl) {
                String at = where + ".requires.java[" + i++ + "]";
                if (!(o instanceof Map<?, ?> jm)) {
                    throw new IllegalArgumentException(at + " is a mapping (coords, sha256, ...)");
                }
                String coords = str(jm.get("coords"));
                String sha = str(jm.get("sha256")).toLowerCase(Locale.ROOT);
                String source = str(jm.get("source"));
                if (!coords.matches("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+")) {
                    throw new IllegalArgumentException(at + ".coords is group:artifact:version, not '" + coords + "'");
                }
                if (!sha.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException(at + ".sha256 is the jar's 64-hex sha256");
                }
                source = source.isEmpty() ? "shipped" : source;
                if (!source.equals("shipped") && !source.equals("bank-maven")) {
                    throw new IllegalArgumentException(at + ".source is shipped or bank-maven, not '" + source + "'");
                }
                java.add(new JavaLib(coords, sha, str(jm.get("classifier")), source, Boolean.parseBoolean(str(jm.get("native")))));
            }
        }
        List<String> plugins = new ArrayList<>();
        Object pl = m.get("plugins");
        if (pl instanceof List<?> l) {
            for (Object o : l) {
                String name = o instanceof Map<?, ?> om ? str(om.get("name")) : str(o);
                if (!name.isEmpty()) {
                    plugins.add(name);
                }
            }
        } else if (pl != null) {
            throw new IllegalArgumentException(where + ".requires.plugins is a list");
        }
        return new Requires(py, java, plugins);
    }

    /** {@code requires.requested-by}: the person who asked for these requirements (for four eyes), or empty. */
    public static String requestedBy(Object raw) {
        return raw instanceof Map<?, ?> m ? str(m.get("requested-by")) : "";
    }

    /**
     * The items an administrator approves: every pinned wheel of the lock (read from {@code base}, the folder the lock path is relative
     * to), every Java library, every plugin.
     */
    public List<ApprovalItem> items(Path base) {
        List<ApprovalItem> out = new ArrayList<>();
        if (python != null) {
            LockFile lock = lock(base);
            for (LockFile.Pin pin : lock.pins()) {
                out.add(new ApprovalItem("python", pin.name(), pin.version(), String.join(",", pin.hashes()), python.index(), false));
            }
        }
        for (JavaLib j : java) {
            out.add(new ApprovalItem("java", j.group() + ":" + j.artifact() + (j.classifier().isEmpty() ? "" : ":" + j.classifier()), j.version(),
                    j.sha256(), j.source(), j.nativeCode()));
        }
        for (String p : plugins) {
            out.add(new ApprovalItem("plugin", p, "", "", "installed", false));
        }
        return out;
    }

    /** The lock file, read and checked. */
    public LockFile lock(Path base) {
        if (python == null) {
            throw new IllegalStateException("no python requirement");
        }
        Path f = base.resolve(python.lock()).normalize();
        try {
            return LockFile.parse(Files.readString(f), python.lock());
        } catch (IOException e) {
            throw new UncheckedIOException("the lock file " + f + " cannot be read", e);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
