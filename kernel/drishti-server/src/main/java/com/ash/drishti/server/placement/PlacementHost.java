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
package com.ash.drishti.server.placement;

import com.ash.drishti.module.runtime.Contributions;
import com.ash.drishti.module.runtime.DmodManifest;
import com.ash.drishti.module.runtime.LoadedModule;
import com.ash.drishti.module.runtime.ModuleHost;
import com.ash.drishti.module.runtime.placement.ModuleParts;

/**
 * The kernel's side of the module runtime for placement (KERNEL_AND_MODULES.md sections 4.11 and 10.4): the runtime asks it whether a
 * module is placed on this node before loading it, and tells it what it loaded and removed. Placement keeps no registry of its own: the
 * modules it resolves are the runtime's (and today's in-process ones).
 */
public final class PlacementHost implements ModuleHost {

    private final PlacementService placement;

    public PlacementHost(PlacementService placement) {
        this.placement = placement;
    }

    /** A module whose parts are all placed elsewhere is not loaded here; it stays known, so Health and Admin → Cluster show where it runs. */
    @Override
    public boolean placedHere(DmodManifest manifest) {
        placement.declare(ModuleParts.of(manifest));
        return placement.placedHere(manifest.id());
    }

    @Override
    public void started(LoadedModule module, Contributions contributions) {
        placement.declare(ModuleParts.of(module.manifest()));
    }

    @Override
    public void event(String name, String module) {
        if ("module.removed".equals(name)) {
            placement.forget(module);
        }
    }
}
