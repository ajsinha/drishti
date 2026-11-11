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
package com.ash.drishti.kernel.api.engines;

import java.util.List;

/**
 * One compute engine's lifecycle (docs/guides/ADMIN_GUIDE.md section 5.1), registered with the {@link EngineRegistry} as a Spring
 * bean: an engine's build adds one bean of this type and one catalogue entry, nothing else. The engine's beans exist either way and are
 * thin; what holds memory or threads (open dates, caches, pools, schedulers, its connectors) is created on first use while it runs and
 * released by {@link #stop()}. While it does not run, its service refuses with {@link EngineOffException} (404 DRS-1020) and the API filter
 * answers before it is reached.
 */
public interface ComputeEngine {

    /** The engine's catalogue id ({@code var}, {@code frtb-sa}). */
    String id();

    /** Starts the engine: parameter files read and checked, its connectors and computed kinds registered. Throws to refuse (the reason shown). */
    default void start() {
    }

    /** Told who changes the engine's switch, when and why, just before the change takes effect (an engine that records it in its own state). */
    default void changing(String actor, String at, String reason) {
    }

    /** Releases what it holds: open dates, caches and buffers, threads, its connectors and computed kinds. Requests have drained. */
    default void stop() {
    }

    /** The memory it holds now, in bytes (the heap of open dates and caches, direct buffers). */
    default long memoryBytes() {
        return 0;
    }

    /** Business dates it holds open now. */
    default int datesOpen() {
        return 0;
    }

    /** The name prefixes of its own threads ({@code drishti-var-}), counted for Admin, Engines and checked by the tests. */
    default List<String> threadPrefixes() {
        return List.of();
    }

    /** Told whether the registry has the engine running (the service's own check reads it). */
    void running(boolean on);

    /** Whether the engine runs, as the registry last said; null before the registry said anything (a test that builds the service alone). */
    Boolean running();

    /** The live threads whose names start with one of the prefixes. */
    static int threadsNamed(List<String> prefixes) {
        if (prefixes.isEmpty()) {
            return 0;
        }
        return (int) Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive)
                .filter(t -> prefixes.stream().anyMatch(p -> t.getName().startsWith(p))).count();
    }
}
