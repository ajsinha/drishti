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
package com.ash.drishti.api.resolution;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The boot result as a plain value (KERNEL_AND_MODULES.md section 4.2): for each module the boot accepted, its id, version, the one folder
 * every reader takes its files from, its content hash and its origin. The module boot ({@code ModuleBoot}, in drishti-module-runtime)
 * fills it and publishes it once per process; the readers of this module and of the modules above it (the resolver, the registry, the lake
 * and compute layers, the packs) ask {@link #current()} and never list a modules folder or a cache themselves.
 *
 * <p><b>Content hash.</b> The SHA-256 of the module's hash list ({@code META-INF/DMOD.MF}: one {@code <sha-256>  <path>} line per entry,
 * sorted, no time). Two packages of the same files have the same content hash whatever their zip bytes, file times or build machine; the
 * hash of the {@code .dmod} file itself only names its cache folder and is not kept here. A source folder read under the development switch
 * (or in source mode, a checkout's module folders) has no hash list and no content hash: {@code null}, shown as {@code development}.
 *
 * <p>The value is immutable; {@link #publish} replaces the process's value as a whole.
 */
public final class ModuleHomes {

    /** The system property that carries a module's folder to a child process: {@code drishti.module.dir.<id>}. */
    public static final String DIR_PROPERTY = "drishti.module.dir.";

    /** The word {@link Home#identity()} and {@link #fingerprint()} use for a module without a content hash. */
    public static final String NO_CONTENT_HASH = "development";

    /** Where a module came from. */
    public enum Origin {
        /** A {@code .dmod} whose id and hash list are in {@code first-party.yaml}. */
        FIRST_PARTY("first-party"),
        /** Any other {@code .dmod}. */
        THIRD_PARTY("third-party"),
        /** A source folder read under the development switch or in source mode: no package, no hash list, not verified. */
        DEVELOPMENT("development (unverified)");

        private final String word;

        Origin(String word) {
            this.word = word;
        }

        /** The word Health and {@code module list} show. */
        public String word() {
            return word;
        }
    }

    /**
     * One module's home.
     *
     * @param id          the module's id
     * @param version     its version
     * @param dir         the folder every reader uses: the cache folder of its package, or its source folder (development)
     * @param contentHash the SHA-256 of its {@code META-INF/DMOD.MF}, lower-case hex; null for a development folder
     * @param origin      first-party, third-party or development
     */
    public record Home(String id, String version, Path dir, String contentHash, Origin origin) {

        /** Refuses a home without its id, version, folder or origin, and a content hash that is not 64 lower-case hex digits. */
        public Home {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(dir, "dir");
            Objects.requireNonNull(origin, "origin");
            if (contentHash != null && !contentHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(id + ": the content hash must be a SHA-256 in lower-case hex, not " + contentHash);
            }
            if (contentHash == null && origin != Origin.DEVELOPMENT) {
                throw new IllegalArgumentException(id + ": a packaged module (" + origin.word() + ") has a content hash");
            }
        }

        /** {@code <id> <version> <content hash>} ({@link #NO_CONTENT_HASH} without one): what two nodes compare. */
        public String identity() {
            return id + " " + version + " " + (contentHash == null ? NO_CONTENT_HASH : contentHash);
        }

        /** The first 16 hex digits of the content hash (for display), or null. */
        public String contentHash16() {
            return contentHash == null ? null : contentHash.substring(0, 16);
        }
    }

    private static final AtomicReference<ModuleHomes> CURRENT = new AtomicReference<>();

    private final Map<String, Home> homes;

    private ModuleHomes(Map<String, Home> homes) {
        this.homes = Collections.unmodifiableMap(homes);
    }

    /** The homes of these modules, keyed and ordered by id; refuses two homes of one id. */
    public static ModuleHomes of(Collection<Home> homes) {
        Map<String, Home> out = new TreeMap<>();
        for (Home h : homes) {
            if (out.putIfAbsent(h.id(), h) != null) {
                throw new IllegalArgumentException(h.id() + ": two homes (" + out.get(h.id()).dir() + ", " + h.dir() + ")");
            }
        }
        return new ModuleHomes(out);
    }

    /** No module. */
    public static ModuleHomes none() {
        return new ModuleHomes(new TreeMap<>());
    }

    /** The value the module boot published for this process; empty before the boot ran. */
    public static Optional<ModuleHomes> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Publishes the boot's value for this process (the module boot calls it once, after it ran); returns it. */
    public static ModuleHomes publish(ModuleHomes homes) {
        CURRENT.set(Objects.requireNonNull(homes, "homes"));
        return homes;
    }

    /** id → home, ordered by id, unmodifiable. */
    public Map<String, Home> homes() {
        return homes;
    }

    /** The home of a module, if it booted. */
    public Optional<Home> home(String id) {
        return Optional.ofNullable(homes.get(id));
    }

    /** The folder of a module, if it booted. */
    public Optional<Path> dir(String id) {
        return home(id).map(Home::dir);
    }

    /**
     * The SHA-256 (lower-case hex) of the {@link Home#identity()} lines, one per module in id order, each ending in a newline: equal on two
     * processes exactly when they booted the same modules at the same versions with the same content.
     */
    public String fingerprint() {
        StringBuilder b = new StringBuilder();
        homes.values().forEach(h -> b.append(h.identity()).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code drishti.module.dir.<id>} → the module's absolute folder, in id order: what a child process is given. */
    public Map<String, String> dirProperties() {
        Map<String, String> out = new LinkedHashMap<>();
        homes.values().forEach(h -> out.put(DIR_PROPERTY + h.id(), h.dir().toAbsolutePath().normalize().toString()));
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ModuleHomes other && homes.equals(other.homes);
    }

    @Override
    public int hashCode() {
        return homes.hashCode();
    }

    @Override
    public String toString() {
        return "ModuleHomes" + homes.keySet();
    }
}
