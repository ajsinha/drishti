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

/**
 * What a host adds to the runtime's generic machinery (KERNEL_AND_MODULES.md section 10.4): the kernel starts and stops a module's entry
 * ({@code DrishtiModule}) and records the SPI registrations it collects from the module's context; the console (from i4) adds its own kind
 * of contribution. Every method has a do-nothing default, so a host overrides only what it hosts.
 */
public interface ModuleHost {

    /**
     * Whether this node holds the module at all (KERNEL_AND_MODULES.md section 4.11): true when at least one of its parts is placed here.
     * A module that is not is never loaded on this node ({@code NOT_PLACED}: the node forwards its requests). The default, for a host
     * without a cluster (a test, the SDK's harness), is true; the kernel's host answers from {@code config/placement.yaml}.
     */
    default boolean placedHere(DmodManifest manifest) {
        return true;
    }

    /**
     * Before the module's child context is refreshed: the host's part of making it (the kernel adds the module's own engine defaults to
     * its environment; the conformance harness, which has no kernel beans, makes a module with a kernel-internals allowance lazy).
     */
    default void preparing(ModuleCandidate candidate, org.springframework.context.support.GenericApplicationContext context) {
    }

    /** After the module's child context is refreshed and its routes are in the table: start it and record its contributions. */
    default void started(LoadedModule module, Contributions contributions) {
    }

    /** Unload step 3: let in-flight requests and runs of the module finish, then cancel them (bounded by the host's limits). */
    default void drain(String module) {
    }

    /** Unload step 7, before the child context closes: stop the module's entry. */
    default void stopping(LoadedModule module) {
    }

    /** A lifecycle event: {@code module.started}, {@code module.leaving}, {@code module.stopped}, {@code module.removed}. */
    default void event(String name, String module) {
    }
}
