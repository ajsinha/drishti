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

import java.util.Arrays;
import java.util.List;
import org.springframework.core.env.Environment;

/**
 * The packs this server runs with, for the API and the About page. The set can change while the server runs (a pack
 * folder added, edited or removed; Admin &rarr; Packs Load and Unload): readers always see one complete, consistent
 * snapshot, and {@link #version()} says when it changed.
 */
public final class PackRegistry {

    private record State(List<Pack> packs, java.util.Map<String, PackPython> python) {}

    private volatile State state;
    private final java.util.concurrent.atomic.AtomicLong version = new java.util.concurrent.atomic.AtomicLong();
    /** What is wrong with a pack on disk now (the last good version keeps running), by pack name. */
    private final java.util.Map<String, String> problems = new java.util.concurrent.ConcurrentHashMap<>();
    /** The kinds of packs unloaded while running, by kind: an open view of one says the pack was removed. */
    private final java.util.Map<String, String> removedKinds = new java.util.concurrent.ConcurrentHashMap<>();

    public PackRegistry(Environment env) {
        String loaded = env.getProperty("drishti.packs.loaded", "");
        List<String> names = Arrays.stream(loaded.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.state = state(new PackLoader().load(PackLoader.dirs(env.getProperty("drishti.packs.dir", "./packs"),
                env.getProperty("drishti.packs.installed-dir", "./data/packs/installed")), names));
    }

    private static State state(List<Pack> packs) {
        java.util.Map<String, PackPython> py = new java.util.LinkedHashMap<>();
        packs.forEach(p -> py.put(p.name(), PackPython.of(p)));
        return new State(List.copyOf(packs), java.util.Collections.unmodifiableMap(py));
    }

    /** What a pack offers Calc. */
    public PackPython python(String pack) {
        return state.python().getOrDefault(pack, PackPython.NONE);
    }

    public List<Pack> packs() {
        return state.packs();
    }

    /** Swaps in a new set of packs in one step; the kinds of packs that left are remembered as removed. */
    public synchronized void replace(List<Pack> packs) {
        State old = state;
        State next = state(packs);
        java.util.Set<String> now = new java.util.HashSet<>();
        next.packs().forEach(p -> now.add(p.name()));
        old.packs().stream().filter(p -> !now.contains(p.name())).forEach(p -> p.kinds().forEach(k -> removedKinds.put(k, p.name())));
        next.packs().forEach(p -> p.kinds().forEach(removedKinds::remove));
        state = next;
        version.incrementAndGet();
    }

    /** Changes with every {@link #replace}. */
    public long version() {
        return version.get();
    }

    /** What is wrong with each pack on disk (empty when all is well); the last good version of such a pack keeps running. */
    public java.util.Map<String, String> problems() {
        return problems;
    }

    /** The pack that owned the kind until it was unloaded while running, or null. */
    public String removedOwner(String kind) {
        return removedKinds.get(kind);
    }
}
