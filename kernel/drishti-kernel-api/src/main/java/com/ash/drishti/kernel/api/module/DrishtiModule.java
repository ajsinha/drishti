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
package com.ash.drishti.kernel.api.module;

import java.util.Map;

/**
 * A module's entry point (docs/architecture/KERNEL_AND_MODULES.md section 2.3): the kernel calls {@link #start} once the module's data
 * needs are bound, and {@link #stop} when it unloads. Found with the {@link java.util.ServiceLoader} inside the module's class loader.
 */
public interface DrishtiModule {

    /** The module's id, as its manifest names it ({@code banking}). */
    String id();

    /** Started with the kernel's services: kinds as the person, settings, folders, audit, the business date. */
    void start(ModuleContext context);

    /** Stops what {@link #start} began; every surface the module added is removed by the kernel. */
    default void stop() {
    }

    /** The module's own figures for Health (state, dates open, problems); empty when it has none. */
    default Map<String, Object> health() {
        return Map.of();
    }
}
