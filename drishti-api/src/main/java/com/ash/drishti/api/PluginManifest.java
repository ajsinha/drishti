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
package com.ash.drishti.api;

import java.util.Set;

/**
 * Identity and scope of a source plugin.
 *
 * @param name unique plugin name, referenced from configuration
 * @param version plugin version
 * @param kinds entity kinds this plugin can serve; empty means any kind
 * @param capabilities optional behaviour
 */
public record PluginManifest(String name, String version, Set<String> kinds, SourceCapabilities capabilities) {

    public PluginManifest {
        kinds = Set.copyOf(kinds);
    }

    public boolean serves(String kind) {
        return kinds.isEmpty() || kinds.contains(kind);
    }
}
