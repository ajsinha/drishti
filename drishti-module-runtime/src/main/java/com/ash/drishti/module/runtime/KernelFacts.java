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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What the host (the kernel, later the console) tells the runtime about itself, for verification and loading.
 *
 * @param versions        the API versions this host has ({@code kernel}, and those of {@code rachana}, {@code contracts},
 *                        {@code plugin-api}, {@code elements} it knows); a range the host does not know is not checked here (V3)
 * @param reservedPaths   the kernel's paths and aliases ({@code /t}, {@code /pivot}, {@code /api}, ...) no module may claim (V6)
 * @param reservedWords   the kernel's command words ({@code PIVOT}, {@code BI}, ...) (V6)
 * @param knownKinds      kinds the kernel provides or binds; null when the host does not know them yet (V7 then only parses)
 * @param licensed        whether the licence names a feature and is valid today (V5)
 * @param budgetMb        the node's module memory budget, 0 for no budget (V8)
 * @param strictResources true when an over-budget module is refused rather than warned (V8)
 * @param allowNative     false on a site that forbids native code bundled in jars
 * @param shared          the class-name prefixes every module loader asks the kernel for (parent-first): the kernel API and the shared
 *                        libraries (section 4.4)
 * @param kernelBuild     this kernel's exact build ({@code <version>+<build time>}), the one a module with a kernel-internals allowance must
 *                        have been built for (section 3.3); null when unknown
 * @param firstParty      the product's list of its own modules ({@link FirstParty}), never null
 */
public record KernelFacts(Map<String, String> versions, Set<String> reservedPaths, Set<String> reservedWords, Set<String> knownKinds,
        Predicate<String> licensed, long budgetMb, boolean strictResources, boolean allowNative, List<String> shared, String kernelBuild,
        FirstParty firstParty) {

    /** The shared libraries of section 4.4, parent-first in every module loader; part of the kernel API (a MAJOR removes, a MINOR adds). */
    public static final List<String> SHARED = List.of("com.ash.drishti.kernel.api.", "com.ash.drishti.api.", "org.springframework.", "com.fasterxml.jackson.",
            "org.slf4j.", "org.apache.arrow.", "io.micrometer.", "jakarta.servlet.", "jakarta.validation.", "jakarta.annotation.",
            "jakarta.inject.", "org.aopalliance.");

    /** The kernel's reserved paths (section 9.9). */
    public static final Set<String> RESERVED_PATHS = Set.of("/t", "/pivot", "/bi", "/build", "/admin", "/help", "/data", "/static", "/m",
            "/api", "/embed", "/public", "/healthz", "/readyz", "/login", "/logout");

    /** The kernel's reserved words (section 9.9). */
    public static final Set<String> RESERVED_WORDS = Set.of("PIVOT", "BI", "FUNC", "FORM", "HELP", "WHAT", "DATAMAP", "EXPORT");

    public KernelFacts {
        versions = Map.copyOf(versions);
        reservedPaths = Set.copyOf(reservedPaths);
        reservedWords = Set.copyOf(reservedWords);
        knownKinds = knownKinds == null ? null : Set.copyOf(knownKinds);
        licensed = licensed == null ? f -> true : licensed;
        shared = List.copyOf(shared);
        firstParty = firstParty == null ? FirstParty.none() : firstParty;
    }

    /** The facts of this kernel: its API version (from {@code drishti-kernel-api}), the reserved names, every feature licensed, no budget. */
    public static KernelFacts ofKernel() {
        return new KernelFacts(Map.of("kernel", kernelApiVersion()), RESERVED_PATHS, RESERVED_WORDS, null, f -> true, 0, false, true, SHARED, thisBuild(),
                FirstParty.shipped(KernelFacts.class.getClassLoader()));
    }

    /** A copy with more versions known (the host adds rachana, contracts, plugin-api, elements as it knows them). */
    public KernelFacts withVersions(Map<String, String> more) {
        Map<String, String> v = new LinkedHashMap<>(versions);
        v.putAll(more);
        return new KernelFacts(v, reservedPaths, reservedWords, knownKinds, licensed, budgetMb, strictResources, allowNative, shared, kernelBuild, firstParty);
    }

    /** A copy with a licence check (V5). */
    public KernelFacts withLicence(Predicate<String> check) {
        return new KernelFacts(versions, reservedPaths, reservedWords, knownKinds, check, budgetMb, strictResources, allowNative, shared, kernelBuild, firstParty);
    }

    /** A copy with a memory budget (V8). */
    public KernelFacts withBudget(long mb, boolean strict) {
        return new KernelFacts(versions, reservedPaths, reservedWords, knownKinds, licensed, mb, strict, allowNative, shared, kernelBuild, firstParty);
    }

    /** A copy with the kinds the kernel knows (V7). */
    public KernelFacts withKnownKinds(Set<String> kinds) {
        return new KernelFacts(versions, reservedPaths, reservedWords, kinds, licensed, budgetMb, strictResources, allowNative, shared, kernelBuild, firstParty);
    }

    /** A copy with another build and first-party list (the module tool's --kernel-build and --first-party, and tests). */
    public KernelFacts withBuild(String build, FirstParty list) {
        return new KernelFacts(versions, reservedPaths, reservedWords, knownKinds, licensed, budgetMb, strictResources, allowNative, shared, build,
                list);
    }

    /**
     * This kernel's exact build: the server's version and build time from its {@code META-INF/build-info.properties}
     * ({@code <version>+<time>}), or null when the class path has no server build (a unit test of the runtime alone).
     */
    public static String thisBuild() {
        try {
            java.util.Enumeration<java.net.URL> all = KernelFacts.class.getClassLoader().getResources("META-INF/build-info.properties");
            while (all.hasMoreElements()) {
                Properties p = new Properties();
                try (InputStream in = all.nextElement().openStream()) {
                    p.load(in);
                }
                if ("drishti-server".equals(p.getProperty("build.artifact"))) {
                    return p.getProperty("build.version") + "+" + p.getProperty("build.time");
                }
            }
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The kernel API version, as {@code drishti-kernel-api} ships it ({@code META-INF/drishti/kernel-api.properties}). */
    public static String kernelApiVersion() {
        try (InputStream in = KernelFacts.class.getClassLoader().getResourceAsStream("META-INF/drishti/kernel-api.properties")) {
            Properties p = new Properties();
            if (in != null) {
                p.load(in);
            }
            return p.getProperty("kernelApi", "0.0");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
