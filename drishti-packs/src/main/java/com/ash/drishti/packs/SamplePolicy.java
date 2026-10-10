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
package com.ash.drishti.packs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Who sees the sample packs ({@code sample: true} in {@code pack.yaml}). The mode is {@code visible} (everyone; the
 * default), {@code developers} (only users with the author or admin power) or {@code hidden} (nobody; the sample packs are
 * not loaded and their connectors do not start). It is the property {@code drishti.packs.samples}, overridden by the
 * setting an administrator saves from Admin → Packs ("Hide samples from business users"), kept in a one-line file.
 */
public final class SamplePolicy {

    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    public static final String VISIBLE = "visible";
    public static final String DEVELOPERS = "developers";
    public static final String HIDDEN = "hidden";
    public static final List<String> MODES = List.of(VISIBLE, DEVELOPERS, HIDDEN);

    private final Path file;
    private final String configured;
    private volatile String saved;

    public SamplePolicy(String configured, Path file) {
        this.file = file == null ? null : file.toAbsolutePath().normalize();
        this.configured = normalise(configured, VISIBLE);
        this.saved = readSaved(this.file);
    }

    /** The policy the environment describes: the property and the saved override file. */
    public static SamplePolicy of(org.springframework.core.env.Environment env) {
        return new SamplePolicy(env.getProperty("drishti.packs.samples", VISIBLE),
                Path.of(env.getProperty("drishti.packs.samples-file", "./data/packs/samples-mode")));
    }

    /** The mode in force: the administrator's saved setting if there is one, else the property. */
    public String mode() {
        String s = saved;
        return s != null ? s : configured;
    }

    /** True when an administrator's saved setting (not the property) decides the mode. */
    public boolean overridden() {
        return saved != null;
    }

    public String configured() {
        return configured;
    }

    /** Saves the mode as the administrator's setting; it overrides the property until {@link #clear()}. */
    public void set(String mode) {
        lock.lock();                   // writes a file: a ReentrantLock, not synchronized (Java 21 pins virtual threads)
        try {
            String m = normalise(mode, null);
            if (m == null) {
                throw new IllegalArgumentException("samples is one of " + MODES + ", not '" + mode + "'");
            }
            try {
                Files.createDirectories(file.getParent());
                Path tmp = Files.createTempFile(file.getParent(), ".samples-", ".tmp");
                Files.writeString(tmp, m + "\n", StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            saved = m;
        } finally {
            lock.unlock();
        }
    }

    /** Removes the saved setting: the property decides again. */
    public void clear() {
        lock.lock();                   // writes a file: a ReentrantLock, not synchronized (Java 21 pins virtual threads)
        try {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            saved = null;
        } finally {
            lock.unlock();
        }
    }

    private static String readSaved(Path f) {
        try {
            return f != null && Files.isRegularFile(f) ? normalise(Files.readString(f, StandardCharsets.UTF_8), null) : null;
        } catch (IOException e) {
            return null;
        }
    }

    static String normalise(String v, String fallback) {
        String m = v == null ? "" : v.strip().toLowerCase(Locale.ROOT);
        return MODES.contains(m) ? m : fallback;
    }

    /** True when sample packs are not loaded at all in this mode. */
    public boolean hidden() {
        return HIDDEN.equals(mode());
    }

    /** The packs to load: {@code names} without the sample packs when the mode is {@code hidden}. */
    public List<String> select(List<Path> dirs, List<String> names, PackLoader loader) {
        if (!hidden()) {
            return names;
        }
        List<String> out = new ArrayList<>();
        for (String n : names) {
            if (!sampleOf(dirs, n, loader)) {
                out.add(n);
            }
        }
        return out;
    }

    private static boolean sampleOf(List<Path> dirs, String name, PackLoader loader) {
        try {
            return loader.readOne(dirs, name).sample();
        } catch (RuntimeException e) {
            return false;                          // a pack that cannot be read is reported by the load, not hidden here
        }
    }

    /**
     * The connectors only sample packs use: named by a sample pack on disk and by no other pack on disk. In
     * {@code hidden} mode they do not start.
     */
    public static Set<String> sampleOnlyConnectors(List<Path> dirs, PackLoader loader) {
        Map<String, Set<String>> users = new LinkedHashMap<>();
        Set<String> other = new LinkedHashSet<>();
        for (String n : PackLoader.folders(dirs)) {
            Pack p;
            try {
                p = loader.readOne(dirs, n);
            } catch (RuntimeException e) {
                continue;
            }
            for (String c : p.connectorRefs()) {
                if (p.sample()) {
                    users.computeIfAbsent(c, k -> new LinkedHashSet<>()).add(n);
                } else {
                    other.add(c);
                }
            }
        }
        Set<String> out = new LinkedHashSet<>(users.keySet());
        out.removeAll(other);
        return out;
    }
}
