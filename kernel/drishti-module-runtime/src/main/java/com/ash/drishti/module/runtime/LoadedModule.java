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

import java.util.List;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * A module the runtime loaded: what it is, its class loader and child Spring context (both null for a module without Java), its routes and
 * its Health notes.
 */
public record LoadedModule(ModuleCandidate candidate, ModuleClassLoader loader, ConfigurableApplicationContext context,
        List<RouteTable.Route> routes, List<String> notes) {

    public String id() {
        return candidate.id();
    }

    public DmodManifest manifest() {
        return candidate.manifest();
    }
}
