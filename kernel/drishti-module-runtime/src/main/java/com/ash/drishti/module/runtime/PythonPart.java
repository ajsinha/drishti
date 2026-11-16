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
package com.ash.drishti.module.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The Python compute a module ships (the manifest's {@code python:} section, KERNEL_AND_MODULES.md section 3.3): its packages, the engines
 * (items) the kernel's worker pool loads from them, and the pure-Python wheels it needs, vendored inside the package. Nothing is installed
 * from a network and nothing native is allowed: a wheel is {@code py3-none-any} and holds no compiled code.
 *
 * <pre>
 * python:
 *   version: "3.10"                       # the least Python it runs on
 *   path: [python]                        # folders of the package put on the module's worker path (default python)
 *   wheels: python/wheels                 # the vendored wheels folder (default python/wheels; absent: none)
 *   requires: ["tabulate==0.9.0"]         # each needs a wheel in the folder
 *   engines: [helix_py.variants:VariantEngine]
 *   pool: {workers: {min: 0, max: 2}, mode: auto, timeout: 30s}    # the pool's own settings (compute.yaml's keys)
 * </pre>
 *
 * @param version  the least Python version ({@code 3.10})
 * @param path     package folders, relative to the module
 * @param wheels   the wheels folder, relative to the module
 * @param requires the declared requirements ({@code name} or {@code name==version})
 * @param engines  the engine entry points ({@code module:Class})
 * @param pool     the pool's settings as compute.yaml writes them (empty: the defaults)
 */
public record PythonPart(String version, List<String> path, String wheels, List<String> requires, List<String> engines, Map<String, Object> pool) {

    private static final Pattern ENGINE = Pattern.compile("([A-Za-z_][A-Za-z0-9_.]*):([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern REQUIRE = Pattern.compile("([A-Za-z0-9][A-Za-z0-9._-]*)\\s*(?:==\\s*([A-Za-z0-9._+!-]+))?");
    private static final List<String> NATIVE = List.of(".so", ".pyd", ".dll", ".dylib", ".exe", ".bat", ".cmd", ".msi", ".com", ".jnilib");

    /** The Python part of a manifest, or empty when it declares none. Refuses a section that does not read. */
    public static Optional<PythonPart> of(DmodManifest m) {
        if (!(m.section("python") instanceof Map<?, ?>)) {
            return Optional.empty();
        }
        Map<String, Object> p = DmodManifest.map(m.section("python"));
        String version = DmodManifest.str(p.get("version"));
        if (version == null || !version.matches("3\\.\\d{1,2}")) {
            throw new ModuleRefusedException("V1", m.label() + ": `python.version` is the least Python version, in quotes, such as \"3.10\" (found " + version + ")");
        }
        List<String> path = DmodManifest.strings(p.get("path"));
        List<String> engines = DmodManifest.strings(p.get("engines"));
        if (engines.isEmpty()) {
            throw new ModuleRefusedException("V1", m.label() + ": `python.engines` names at least one entry point (module:Class)");
        }
        String wheels = DmodManifest.str(p.get("wheels"));
        return Optional.of(new PythonPart(version, path.isEmpty() ? List.of("python") : path, wheels == null ? "python/wheels" : wheels,
                DmodManifest.strings(p.get("requires")), engines, DmodManifest.map(p.get("pool"))));
    }

    /** The worker pool's name for a module: {@code m-<id>}, lower case. */
    public static String poolName(String moduleId) {
        return "m-" + moduleId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    /** The normalised distribution name (PEP 503): lower case, runs of {@code - _ .} as one hyphen. */
    static String normalise(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }

    /**
     * What is wrong with the Python the package ships, in words (empty: nothing): the folders exist, every entry point's module file is in
     * a package folder and defines the class, every declared requirement has a pure-Python wheel of that version in the wheels folder, no
     * wheel is native or carries compiled code, and no file of the Python folders is an executable.
     */
    public List<String> problems(Path dir) {
        List<String> out = new ArrayList<>();
        List<Path> roots = new ArrayList<>();
        for (String f : path) {
            Path r = dir.resolve(f).normalize();
            if (!r.startsWith(dir) || !Files.isDirectory(r)) {
                out.add("python.path names " + f + ", which is not a folder of the package");
            } else {
                roots.add(r);
            }
        }
        for (String e : engines) {
            engineProblem(e, roots, out);
        }
        for (Path r : roots) {
            try (Stream<Path> s = Files.walk(r)) {
                for (Path f : s.filter(Files::isRegularFile).toList()) {
                    String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (NATIVE.stream().anyMatch(n::endsWith)) {
                        out.add(dir.relativize(f).toString().replace('\\', '/') + " is compiled or executable code; a module's Python is pure Python");
                    }
                }
            } catch (IOException x) {
                out.add("the python folder " + dir.relativize(r) + " does not read");
            }
        }
        wheelProblems(dir, out);
        return out;
    }

    private static void engineProblem(String e, List<Path> roots, List<String> out) {
        Matcher mm = ENGINE.matcher(e);
        if (!mm.matches()) {
            out.add("python.engines: '" + e + "' is not module:Class");
            return;
        }
        String rel = mm.group(1).replace('.', '/');
        Path file = null;
        for (Path r : roots) {
            for (Path c : List.of(r.resolve(rel + ".py"), r.resolve(rel).resolve("__init__.py"))) {
                if (Files.isRegularFile(c) && file == null) {
                    file = c;
                }
            }
        }
        if (file == null) {
            out.add("python.engines: " + e + " has no module " + mm.group(1) + " (a .py file or a package) in the python folders");
            return;
        }
        try {
            String text = Files.readString(file);
            if (!Pattern.compile("(?m)^class\\s+" + Pattern.quote(mm.group(2)) + "\\b").matcher(text).find()) {
                out.add("python.engines: " + file.getFileName() + " defines no class " + mm.group(2));
            }
        } catch (IOException | RuntimeException x) {
            out.add("python.engines: " + file.getFileName() + " does not read");
        }
    }

    /** The wheels folder's files (empty when there is none). */
    public List<Path> wheelFiles(Path dir) {
        Path w = dir.resolve(wheels).normalize();
        if (!w.startsWith(dir) || !Files.isDirectory(w)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(w)) {
            return s.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private void wheelProblems(Path dir, List<String> out) {
        List<String[]> have = new ArrayList<>();                    // normalised name, version
        for (Path f : wheelFiles(dir)) {
            String n = f.getFileName().toString();
            String rel = dir.relativize(f).toString().replace('\\', '/');
            if (!n.toLowerCase(Locale.ROOT).endsWith(".whl")) {
                out.add(rel + " is in the wheels folder but is not a wheel");
                continue;
            }
            String[] parts = n.substring(0, n.length() - 4).split("-");
            if (parts.length < 5) {
                out.add(rel + " is not a wheel file name (name-version-python-abi-platform.whl)");
                continue;
            }
            if (!parts[parts.length - 1].equals("any") || !parts[parts.length - 2].equals("none")) {
                out.add(rel + " is not a pure-Python wheel (py3-none-any); a module carries no native code");
                continue;
            }
            try (ZipFile z = new ZipFile(f.toFile())) {
                for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                    String e = en.nextElement().getName().toLowerCase(Locale.ROOT);
                    if (NATIVE.stream().anyMatch(e::endsWith)) {
                        out.add(rel + " holds " + e + ", which is compiled or executable code");
                        break;
                    }
                }
            } catch (IOException e) {
                out.add(rel + " is not a readable wheel");
                continue;
            }
            have.add(new String[] {normalise(parts[0]), parts[1]});
        }
        for (String r : requires) {
            Matcher mm = REQUIRE.matcher(r.strip());
            if (!mm.matches()) {
                out.add("python.requires: '" + r + "' is not name or name==version (no network install happens: each needs a wheel in " + wheels + ")");
                continue;
            }
            String name = normalise(mm.group(1));
            String ver = mm.group(2);
            if (have.stream().noneMatch(h -> h[0].equals(name) && (ver == null || h[1].equals(ver)))) {
                out.add("python.requires: " + r.strip() + " has no wheel in " + wheels + " (a module carries its dependencies; nothing is installed from a network)");
            }
        }
    }
}
