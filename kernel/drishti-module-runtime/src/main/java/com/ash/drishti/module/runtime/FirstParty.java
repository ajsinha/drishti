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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * First-party modules and the kernel-internals allowance (docs/architecture/KERNEL_AND_MODULES.md section 3.3, "First-party modules" and
 * "Exact kernel version for an allowance"): the one implementation the kernel's loader (verification and the class loader) and the module
 * tool's conformance check C1 both use.
 *
 * <p>A manifest's {@code origin: first-party} is a claim, verified, never trusted: until signing (2.9.11.j) a module is first-party only
 * when its id and the SHA-256 of its hash list ({@code META-INF/DMOD.MF}) are in the {@code first-party.yaml} the product ships with the
 * kernel. Only a verified first-party module may declare {@code kernel-internals:} (the kernel packages it may still reach), and a module
 * that declares them names the one kernel build it was built for ({@code kernel-build:}) and loads on no other.
 */
public final class FirstParty {

    /** Where the product ships its list, inside the server's jar. */
    public static final String RESOURCE = "META-INF/drishti/first-party.yaml";

    /** A module's origin as verified, with the words saying why. */
    public record Origin(boolean firstParty, String why) {

        /** {@code first-party} or {@code third-party}. */
        public String word() {
            return firstParty ? "first-party" : "third-party";
        }

        /** The line Health and {@code module inspect} show. */
        public String line() {
            return "origin: " + word() + " (" + why + ")";
        }
    }

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final FirstParty NONE = new FirstParty(Map.of(), "first-party list (it ships none)");

    private final Map<String, Set<String>> hashes;
    private final String source;

    private FirstParty(Map<String, Set<String>> hashes, String source) {
        this.hashes = hashes;
        this.source = source;
    }

    /** No list: every module is third-party. */
    public static FirstParty none() {
        return NONE;
    }

    /** The list the product ships ({@link #RESOURCE} on the loader's class path), or none. */
    public static FirstParty shipped(ClassLoader loader) {
        ClassLoader l = loader == null ? FirstParty.class.getClassLoader() : loader;
        try (InputStream in = l.getResourceAsStream(RESOURCE)) {
            return in == null ? NONE : parse(in, "first-party.yaml");
        } catch (IOException e) {
            throw new UncheckedIOException(RESOURCE + " does not read", e);
        }
    }

    /** A list from a file (the module tool's {@code --first-party}). */
    public static FirstParty read(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(in, file.getFileName().toString());
        } catch (IOException e) {
            throw new UncheckedIOException(file + " does not read", e);
        }
    }

    private static FirstParty parse(InputStream in, String where) throws IOException {
        Object raw = YAML.readValue(in, Object.class);
        Map<String, Set<String>> out = new HashMap<>();
        if (raw instanceof Map<?, ?> m && m.get("modules") instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> e = DmodManifest.map(o);
                String id = DmodManifest.str(e.get("id"));
                String sha = DmodManifest.str(e.get("sha256"));
                if (id != null && sha != null) {
                    out.computeIfAbsent(id, k -> new HashSet<>()).add(sha.toLowerCase(Locale.ROOT));
                }
            }
        }
        return new FirstParty(Map.copyOf(out), where);
    }

    /** The text of a list: one row per unpacked module folder, its id, version and the SHA-256 of its hash list. */
    public static String write(Collection<Path> moduleFolders) {
        Map<String, String> rows = new TreeMap<>();
        for (Path dir : moduleFolders) {
            DmodManifest m;
            try (InputStream in = Files.newInputStream(dir.resolve(DmodManifest.FILE))) {
                m = DmodManifest.read(in, dir.toString());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            rows.put(m.id(), "  - {id: " + m.id() + ", version: " + m.version() + ", sha256: " + listHash(dir) + "}\n");
        }
        StringBuilder y = new StringBuilder("# The product's own modules (KERNEL_AND_MODULES.md section 3.3): a .dmod is first-party only when its id and\n"
                + "# the SHA-256 of its META-INF/DMOD.MF are listed here. Written by the build, never by hand.\nmodules:\n");
        rows.values().forEach(y::append);
        return y.toString();
    }

    /** The SHA-256 of a module folder's hash list ({@code META-INF/DMOD.MF}), or null when it has none. */
    public static String listHash(Path dir) {
        Path mf = dir.resolve(Dmod.HASHES);
        if (!Files.isRegularFile(mf)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(mf)) {
            return Dmod.sha256(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The ids the list holds. */
    public Set<String> ids() {
        return hashes.keySet();
    }

    /** A module's origin: first-party only when it says so and its id and hash list are in this list. */
    public Origin origin(DmodManifest m, Path dir) {
        if (!m.firstPartyClaim()) {
            return new Origin(false, "declared third-party");
        }
        String sha = dir == null ? null : listHash(dir);
        if (sha == null) {
            return new Origin(false, "claims first-party, but has no hash list to verify (an exploded folder)");
        }
        Set<String> known = hashes.get(m.id());
        if (known == null) {
            return new Origin(false, "claims first-party, but " + m.id() + " is not in this kernel's " + source);
        }
        if (!known.contains(sha.toLowerCase(Locale.ROOT))) {
            return new Origin(false, "claims first-party, but its hash list is not the one the product shipped in " + source);
        }
        return new Origin(true, "verified: its id and hash list are in " + source);
    }

    /** The words of a module built for another kernel build. */
    public static String otherBuild(String label, String built, String here) {
        return label + ": built for kernel " + built + ", this is " + (here == null || here.isBlank() ? "an unknown build" : here)
                + ": install the matching module";
    }

    /**
     * The allowance rule, for the kernel and the tool alike: the kernel packages a module may reach beyond the kernel API, empty for a
     * module without {@code kernel-internals:}. Refused when the module declares an allowance but is not a verified first-party module
     * ({@code C1}), names no build ({@code V1}), or was built for another kernel build ({@code V3}).
     */
    public static List<String> allowance(DmodManifest m, Origin origin, String kernelBuild) {
        List<String> pkgs = m.kernelInternals();
        if (pkgs.isEmpty()) {
            return List.of();
        }
        if (!origin.firstParty()) {
            throw new ModuleRefusedException("C1", m.label() + ": declares a kernel-internals allowance (" + pkgs.size() + " packages), which only a "
                    + "verified first-party module may; this one loads as third-party (" + origin.why() + ") and may use only drishti-kernel-api");
        }
        String built = m.kernelBuild();
        if (built == null) {
            throw new ModuleRefusedException("V1", m.label() + ": declares a kernel-internals allowance but no `kernel-build` (the one kernel "
                    + "build it was built for)");
        }
        if (!built.equals(kernelBuild)) {
            throw new ModuleRefusedException("V3", otherBuild(m.label(), built, kernelBuild));
        }
        return pkgs;
    }

    /** True when a class is in one of an allowance's packages (exact packages, not their sub-packages). */
    public static boolean allowed(List<String> allowance, String className) {
        int i = className.lastIndexOf('.');
        return i > 0 && allowance.contains(className.substring(0, i));
    }

    /** A list of id to hash-list SHA-256 (tests, and the tool's freshly packed modules). */
    public static FirstParty of(Map<String, String> idToSha) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        idToSha.forEach((k, v) -> out.put(k, Set.of(v.toLowerCase(Locale.ROOT))));
        return new FirstParty(out, "the given list");
    }

    @Override
    public String toString() {
        return "first-party " + new ArrayList<>(hashes.keySet()) + " from " + source;
    }
}
