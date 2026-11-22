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

import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.lifecycle.ComponentSuspended;
import com.ash.drishti.api.lifecycle.LifecycleMachine;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;

/**
 * What stands in the registry for a suspended connector (docs/architecture/LIFECYCLE.md section 4): the same name and manifest, but every
 * read, search, scan, subscription of new data and write answers {@link ComponentSuspended} ({@code 503 DRS-1037}) naming the connector. What
 * describes it (manifest, health, freshness, caches, pool, settings) and the housekeeping that keeps its kept state right (cache purges,
 * version refresh) reach the real plugin, which is held unchanged until it is resumed. Readers that took the real plugin before the swap
 * finish on it (the registry's drain waits for them).
 */
final class SuspendedSource implements InvocationHandler {

    /** The methods that still reach the plugin: they describe it or keep its kept state right, and read no data for anyone. */
    static final Set<String> PASS = Set.of("manifest", "health", "close", "lastUpdate", "cacheStats", "poolStats", "settingSpecs", "memoryOnly",
            "timeTravel", "writeKey", "dependsOn", "purgeCaches", "purgeDate", "refreshVersions", "listingProblem", "columnar", "vectorColumns",
            "tableIdentity", "suspend", "resume", "backlog", "checkWrite", "toString", "hashCode", "equals", "pushes");

    private final SourcePlugin real;
    private final LifecycleMachine machine;

    private SourcePlugin proxy;

    private SuspendedSource(SourcePlugin real, LifecycleMachine machine) {
        this.real = real;
        this.machine = machine;
    }

    /** The stand-in for {@code real} while {@code machine} is suspended. */
    static SourcePlugin of(SourcePlugin real, LifecycleMachine machine) {
        SuspendedSource h = new SuspendedSource(real, machine);
        h.proxy = (SourcePlugin) Proxy.newProxyInstance(SourcePlugin.class.getClassLoader(), new Class<?>[] {SourcePlugin.class}, h);
        return h.proxy;
    }

    /** The real plugin behind a stand-in, or {@code p} itself. */
    static SourcePlugin unwrap(SourcePlugin p) {
        if (p != null && Proxy.isProxyClass(p.getClass()) && Proxy.getInvocationHandler(p) instanceof SuspendedSource s) {
            return s.real;
        }
        return p;
    }

    /** True when {@code p} is a stand-in. */
    static boolean is(SourcePlugin p) {
        return p != null && Proxy.isProxyClass(p.getClass()) && Proxy.getInvocationHandler(p) instanceof SuspendedSource;
    }

    @Override
    public Object invoke(Object self, Method m, Object[] args) throws Throwable {
        String n = m.getName();
        if (n.equals("equals") && args != null && args.length == 1) {
            return self == args[0];
        }
        if (n.equals("hashCode") && (args == null || args.length == 0)) {
            return System.identityHashCode(self);
        }
        if (n.equals("close") && (args == null || args.length == 0)) {
            return null;                                   // the registry closes the real plugin itself, at shutdown
        }
        if (n.equals("health") && (args == null || args.length == 0)) {
            return "SUSPENDED: " + real.health();
        }
        if (!PASS.contains(n)) {
            machine.recorder().refused();
            throw new ComponentSuspended(machine.type(), machine.name(), machine.by(), machine.since());
        }
        try {
            return m.invoke(real, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
