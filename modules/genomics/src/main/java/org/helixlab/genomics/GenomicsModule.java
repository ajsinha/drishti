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
package org.helixlab.genomics;

import com.ash.drishti.kernel.api.module.DrishtiModule;
import com.ash.drishti.kernel.api.module.ModuleContext;

/** The module's entry point: the kernel starts and stops it; everything it needs comes through the kernel API. */
public class GenomicsModule implements DrishtiModule {

    @Override
    public String id() {
        return "genomics";
    }

    @Override
    public void start(ModuleContext context) {
        context.audit("genomics.started", java.util.Map.of("data", context.dataDir().toString()));
    }
}
