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
package com.ash.drishti.lakeconfig;

import com.ash.drishti.api.resolution.ConfigResolution;
import com.ash.drishti.api.resolution.ModuleTemplates;
import java.util.Collection;
import java.util.Map;

/**
 * The connector templates the modules that are on bring to a pack (docs/architecture/KERNEL_AND_MODULES.md section 18.5): those whose
 * first kind the pack defines, from the process's {@link ConfigResolution} (the server's, or the loader's own built from the switch files).
 * A template is a file directly in a module's {@code connectors/} folder, named by its {@code name:} key; {@code connectors/examples/} holds
 * examples to copy. Never a folder of its own, never a walk up the tree: the tools read the resolver's answer ({@code resolve --json}, {@code tools/resolved.py}).
 */
public final class ModuleConnectors {

    /** The template key that names the module a template came from (never a connector setting). */
    public static final String FROM_MODULE = ModuleTemplates.FROM_MODULE;

    private ModuleConnectors() {}

    /** The templates of the modules that are on for the kinds {@code kinds} (a pack's), by connector name, each marked with its module. */
    public static Map<String, Map<String, Object>> forKinds(Collection<String> kinds) {
        return ConfigResolution.currentOrOffline().templatesForKinds(kinds);
    }
}
