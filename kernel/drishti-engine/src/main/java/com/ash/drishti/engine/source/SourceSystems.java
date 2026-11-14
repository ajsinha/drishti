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
package com.ash.drishti.engine.source;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The trading systems the installed source modules feed and which of them are switched off (docs/guides/DATA_LAKE_GUIDE.md, "Several source
 * systems: shared or separate tables"). One per server, held by the {@link SourceRegistry}; the module switch sets it at runtime. A system that
 * is off: its connectors leave every read order (lists, blotters, search, BI), its rows leave a shared table's reads, its live feed stops
 * passing messages; a page computed from a kind it feeds names it ({@link #excluded}). Switched on, all come back. The allowed values of
 * {@code SOURCE_SYSTEM} are the systems installed here, never a fixed list. Thread-safe.
 */
public final class SourceSystems {

    /** One installed system: the module that brings it and the kinds it feeds. */
    public record System(String id, String module, Set<String> kinds, boolean on) {}

    private volatile Map<String, System> installed = Map.of();
    private volatile Set<String> off = Set.of();
    private volatile long version;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** The systems the installed source modules declare ({@code source: {system, kinds}} of their manifest), and whether each is on. */
    public synchronized void installed(java.util.Collection<System> systems) {
        Map<String, System> m = new TreeMap<>();
        Set<String> o = new TreeSet<>();
        for (System s : systems) {
            m.put(s.id(), s);
            if (!s.on()) {
                o.add(s.id());
            }
        }
        boolean changed = !o.equals(off) || !m.keySet().equals(installed.keySet());
        installed = java.util.Collections.unmodifiableMap(m);
        off = java.util.Collections.unmodifiableSet(o);
        if (changed) {
            version++;
            listeners.forEach(Runnable::run);
        }
    }

    /** Switches one installed system on or off at runtime (the module switch). False when no installed module declares it. */
    public boolean switchTo(String id, boolean on) {
        System s = installed.get(id);
        if (s == null) {
            return false;
        }
        java.util.List<System> next = new java.util.ArrayList<>(installed.values());
        next.replaceAll(x -> x.id().equals(id) ? new System(x.id(), x.module(), x.kinds(), on) : x);
        installed(next);
        return true;
    }

    /** Whether the system is switched off (an unknown or blank system is never off). */
    public boolean off(String system) {
        return system != null && off.contains(system);
    }

    /** The systems switched off. */
    public Set<String> off() {
        return off;
    }

    /** Every installed system. */
    public Map<String, System> installed() {
        return installed;
    }

    /** The allowed values of {@code SOURCE_SYSTEM}: the systems installed here (on or off), sorted. */
    public Set<String> allowed() {
        return new TreeSet<>(installed.keySet());
    }

    /** The systems switched off that feed {@code kind}: what a page computed from the kind says it excludes. */
    public Set<String> excluded(String kind) {
        Set<String> out = new TreeSet<>();
        for (String id : off) {
            System s = installed.get(id);
            if (s != null && s.kinds().contains(kind)) {
                out.add(id);
            }
        }
        return out;
    }

    /** Moves whenever a system is switched (a cached read of a kind it feeds is stale). */
    public long version() {
        return version;
    }

    /** Told after every switch. */
    public void listen(Runnable r) {
        listeners.add(r);
    }
}
