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
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The checks of one module on its own (KERNEL_AND_MODULES.md section 4.3): V1 the manifest (read by {@link DmodManifest}, plus id and
 * version equal to the folders), V2 the hash list, the package rules of section 3.1 (no executables, no kernel-API classes bundled, native
 * code only when declared and allowed), V3 the API ranges, V5 the licence, V7 the data needs, V8 the resources. V4 (dependencies) needs
 * every module and is {@link DependencyResolver}'s; V6 (claims) needs what is loaded and is {@link Claims}'. Each refuses with
 * {@link ModuleRefusedException}; what passes may leave Health notes.
 */
public final class ModuleVerifier {

    private static final Set<String> EXECUTABLES = Set.of(".exe", ".so", ".dll", ".dylib", ".bat", ".cmd", ".com", ".msi");
    private static final String KERNEL_API_PATH = "com/ash/drishti/kernel/api/";

    private final KernelFacts facts;

    public ModuleVerifier(KernelFacts facts) {
        this.facts = facts;
    }

    /** Runs V1 (folders), V2, the package rules, V3, V5, V7 and V8; returns the Health notes. */
    public List<String> verify(ModuleCandidate c) {
        DmodManifest m = c.manifest();
        String label = m.label();
        List<String> notes = new ArrayList<>();
        // V1: id = folder, version = folder
        if (c.dir().getParent() == null || !m.id().equals(c.dir().getParent().getFileName().toString())
                || !m.version().equals(c.dir().getFileName().toString())) {
            throw new ModuleRefusedException("V1", label + ": module.yaml's id and version must name its folder <id>/<version> (found "
                    + c.dir().getParent().getFileName() + "/" + c.dir().getFileName() + ")");
        }
        // V2: hashes (signature from release j)
        notes.addAll(Dmod.verify(c.dir(), c.dmod(), label));
        packageRules(c, notes);
        // V3: the API ranges this host knows
        for (String r : DmodManifest.RANGES) {
            String have = facts.versions().get(r);
            if (have != null && !VersionRange.parse(m.ranges().get(r)).contains(have)) {
                throw new ModuleRefusedException("V3", label + ": built for " + name(r) + " " + m.ranges().get(r) + "; this "
                        + ("plugin-api".equals(r) || "elements".equals(r) ? "console" : "kernel") + " has " + have);
            }
        }
        // V5: licence
        if (!facts.licensed().test(m.licence().feature())) {
            throw new ModuleRefusedException("V5", label + ": the licence does not include " + m.licence().feature());
        }
        dataNeeds(c);
        // V8: resources
        if (facts.budgetMb() > 0 && m.memoryMb() > facts.budgetMb()) {
            String words = label + ": needs " + m.memoryMb() + " MB; " + facts.budgetMb() + " MB in the module budget";
            if (facts.strictResources()) {
                throw new ModuleRefusedException("V8", words);
            }
            notes.add("warning: " + words);
        }
        return notes;
    }

    private static String name(String range) {
        return switch (range) {
            case "kernel" -> "kernel API";
            case "plugin-api" -> "console plugin API";
            case "elements" -> "Elements API";
            case "contracts" -> "contract format";
            default -> range;
        };
    }

    /** Section 3.1: no executables; jars only under java/; no kernel-API classes in them; native code only when declared and allowed. */
    private void packageRules(ModuleCandidate c, List<String> notes) {
        DmodManifest m = c.manifest();
        String label = m.label();
        List<String> files;
        try {
            files = Dmod.files(c.dir());
        } catch (IOException e) {
            throw new ModuleRefusedException("V2", label + ": the module folder does not read", e);
        }
        for (String f : files) {
            String lower = f.toLowerCase(Locale.ROOT);
            for (String x : EXECUTABLES) {
                if (lower.endsWith(x)) {
                    throw new ModuleRefusedException("V2", label + ": " + f + " is an executable; a module runs on Java and the kernel's Python only");
                }
            }
        }
        List<String> jars = m.java() == null ? List.of() : m.java().jars();
        for (String j : jars) {
            Path p = c.dir().resolve("java").resolve(j).normalize();
            if (!p.startsWith(c.dir().resolve("java")) || !Files.isRegularFile(p)) {
                throw new ModuleRefusedException("V1", label + ": java.jars names " + j + ", which is not in java/");
            }
            boolean nativeCode = false;
            try (ZipFile z = new ZipFile(p.toFile())) {
                for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                    String n = en.nextElement().getName();
                    if (n.startsWith(KERNEL_API_PATH)) {
                        throw new ModuleRefusedException("V2", label + ": java/" + j + " bundles kernel API classes (" + n
                                + "); the kernel provides them");
                    }
                    String ln = n.toLowerCase(Locale.ROOT);
                    nativeCode |= ln.endsWith(".so") || ln.endsWith(".dll") || ln.endsWith(".dylib") || ln.endsWith(".jnilib");
                }
            } catch (IOException e) {
                throw new ModuleRefusedException("V2", label + ": java/" + j + " is not a jar", e);
            }
            if (nativeCode && !m.java().nativeCode()) {
                throw new ModuleRefusedException("V2", label + ": java/" + j + " bundles native code but module.yaml does not declare java.native");
            }
            if (nativeCode && !facts.allowNative()) {
                throw new ModuleRefusedException("V2", label + ": java/" + j + " bundles native code, which this site forbids");
            }
        }
    }

    /** V7: the needs file parses; every need names a kind and a readable contract range; with known kinds, a required need is satisfiable. */
    private void dataNeeds(ModuleCandidate c) {
        DmodManifest m = c.manifest();
        Map<String, Object> data = DmodManifest.map(m.section("data"));
        String file = DmodManifest.str(data.get("needs"));
        if (file == null) {
            return;
        }
        Object needs = c.yaml(file, "V7").get("needs");
        if (!(needs instanceof List<?> list)) {
            throw new ModuleRefusedException("V7", m.label() + ": " + file + " has no `needs:` list");
        }
        Set<String> provided = new java.util.HashSet<>();
        m.kinds().values().forEach(provided::addAll);
        for (Object o : list) {
            Map<String, Object> n = DmodManifest.map(o);
            String kind = DmodManifest.str(n.get("kind"));
            if (kind == null) {
                throw new ModuleRefusedException("V7", m.label() + ": " + file + ": a need without a kind");
            }
            String contract = DmodManifest.str(n.get("contract"));
            try {
                VersionRange.parse(contract);
            } catch (IllegalArgumentException e) {
                throw new ModuleRefusedException("V7", m.label() + ": " + file + ": the contract range of " + kind + " does not read (" + contract + ")");
            }
            boolean optional = Boolean.TRUE.equals(n.get("optional")) || "false".equals(DmodManifest.str(n.get("required")));
            if (facts.knownKinds() != null && !optional && !provided.contains(kind) && !facts.knownKinds().contains(kind)) {
                throw new ModuleRefusedException("V7", m.label() + ": needs kind " + kind + ", which no module or the kernel provides");
            }
        }
    }
}
