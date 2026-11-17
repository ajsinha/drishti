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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * The setting that carries the whole value ({@link #toProperty()}) to every reader with a property lookup: the server's environment
     * (the launch puts it first), a child process ({@code -D}), a command line that booted. Absent: no package was booted, and the readers
     * have no module.
     */
    public static final String PROPERTY = "drishti.module.homes";

    /** {@code none} as the modules folder's setting: no module folder (a kernel-only server; the unit tests). */
    public static final String NONE = "none";

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

    /**
     * The value as one setting ({@link #PROPERTY}): one entry per module in id order, separated by {@code ,}; each entry
     * {@code <id>:<version>:<content hash or development>:<origin>:<folder>} with the folder URL-encoded (UTF-8), so no character of a path
     * can break it. {@link #fromProperty} reads it back to an equal value.
     */
    public String toProperty() {
        StringBuilder b = new StringBuilder();
        for (Home h : homes.values()) {
            if (!b.isEmpty()) {
                b.append(',');
            }
            b.append(h.id()).append(':').append(h.version()).append(':').append(h.contentHash() == null ? NO_CONTENT_HASH : h.contentHash())
                    .append(':').append(h.origin().name()).append(':')
                    .append(java.net.URLEncoder.encode(h.dir().toAbsolutePath().normalize().toString(), StandardCharsets.UTF_8));
        }
        return b.toString();
    }

    /** The value {@link #toProperty()} wrote; an empty text is no module. A malformed entry throws, naming it. */
    public static ModuleHomes fromProperty(String text) {
        List<Home> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return none();
        }
        for (String entry : text.strip().split(",")) {
            String[] f = entry.split(":", 5);
            if (f.length != 5) {
                throw new IllegalArgumentException(PROPERTY + ": '" + entry + "' is not <id>:<version>:<hash>:<origin>:<folder>");
            }
            out.add(new Home(f[0], f[1], Path.of(java.net.URLDecoder.decode(f[4], StandardCharsets.UTF_8)),
                    NO_CONTENT_HASH.equals(f[2]) ? null : f[2], Origin.valueOf(f[3])));
        }
        return of(out);
    }

    /**
     * The modules every reader with a property lookup takes: the published value ({@link #PROPERTY}: the module boot ran on packages),
     * else none.
     */
    public static ModuleHomes resolve(java.util.function.Function<String, String> property) {
        return of(property).orElseGet(ModuleHomes::none);
    }

    /** The value a property lookup carries ({@link #PROPERTY}), if any: what the launch or the parent process published. */
    public static Optional<ModuleHomes> of(java.util.function.Function<String, String> property) {
        String v = property.apply(PROPERTY);
        return v == null ? Optional.empty() : Optional.of(fromProperty(v));
    }

    /**
     * The module source folders of a folder ({@code <folder>/<name>/} holding a {@code module.yaml}), sorted, absolute: the one listing of
     * source folders, for source mode ({@link #source}, {@code ModuleBoot.source}) and the development switch.
     */
    public static List<Path> folders(Path folder) {
        if (folder == null || !java.nio.file.Files.isDirectory(folder)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> s = java.nio.file.Files.list(folder)) {
            return s.filter(p -> java.nio.file.Files.isRegularFile(p.resolve(MANIFEST))).map(p -> p.toAbsolutePath().normalize()).sorted().toList();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(folder + " cannot be listed: " + e.getMessage(), e);
        }
    }

    /** A module's manifest file. */
    public static final String MANIFEST = "module.yaml";

    /** The folder of a checkout that holds its module source folders. */
    public static final String SOURCE_MODULES = "modules";

    /**
     * Source mode (KERNEL_AND_MODULES.md section 4.2.1, the i7a decision on {@code --root}): every module folder of a checkout,
     * {@code <checkout>/modules/<name>/}, read where it lies: origin development, no content hash, nothing unpacked. What the command-line
     * tools and the tests of the shipped files mean ({@code resolve --json --root}); its id and version are its {@code module.yaml}'s
     * (the id lower case; the folder's name without one; {@code 0.0.0} without a version). Two folders of one id: the first is kept.
     */
    public static ModuleHomes source(Path checkout) {
        Map<String, Home> out = new TreeMap<>();
        for (Path dir : folders(checkout.toAbsolutePath().normalize().resolve(SOURCE_MODULES))) {
            Home h = development(dir);
            out.putIfAbsent(h.id(), h);
        }
        return new ModuleHomes(out);
    }

    /** A source folder's home (origin development): the id and version of its {@code module.yaml}. */
    public static Home development(Path dir) {
        Map<?, ?> m;
        try {
            Object v = YAML.readValue(dir.resolve(MANIFEST).toFile(), Object.class);
            m = v instanceof Map<?, ?> x ? x : Map.of();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(dir.resolve(MANIFEST) + " cannot be read: " + e.getMessage(), e);
        }
        Object id = m.get("id");
        String name = id == null || String.valueOf(id).isBlank() ? String.valueOf(dir.getFileName()) : String.valueOf(id);
        Object version = m.get("version");
        return new Home(name.strip().toLowerCase(java.util.Locale.ROOT), version == null ? "0.0.0" : String.valueOf(version).strip(),
                dir.toAbsolutePath().normalize(), null, Origin.DEVELOPMENT);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper YAML =
            new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());

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
