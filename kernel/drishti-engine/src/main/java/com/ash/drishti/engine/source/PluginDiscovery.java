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
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds source plugins: those on the application class path (the shipped connectors), the computed kinds' providers, and the connectors
 * of the shared lib folder ({@code drishti.lib.path}, one class loader under the kernel's). The lib folder is the one way to add a
 * connector or a JDBC driver: the older plugin folder ({@code drishti.sources.plugin-dir}, each jar in its own loader) is gone; its
 * jars are moved into lib once, at start ({@link com.ash.drishti.common.SharedLib#migrate}).
 */
public final class PluginDiscovery {

    private static final Logger LOG = LoggerFactory.getLogger(PluginDiscovery.class);

    public List<SourcePlugin> discover() {
        List<SourcePlugin> found = new ArrayList<>();
        ServiceLoader.load(SourcePlugin.class, SourcePlugin.class.getClassLoader()).forEach(found::add);
        // modules bring computed kinds as KindProviders (KERNEL_AND_MODULES.md section 5.7); the kernel routes them as sources of their kinds
        ServiceLoader.load(com.ash.drishti.kernel.api.data.KindProvider.class, SourcePlugin.class.getClassLoader()).forEach(k -> found.add(new ComputedKindSource(k)));
        // the shared lib folder (drishti.lib.path): connector plugins the site added, in the one lib class loader under the kernel's
        com.ash.drishti.common.SharedLib lib = com.ash.drishti.common.SharedLib.get();
        found.addAll(lib.services(SourcePlugin.class));
        lib.services(com.ash.drishti.kernel.api.data.KindProvider.class).forEach(k -> found.add(new ComputedKindSource(k)));
        found.forEach(p -> LOG.info("source plugin found: {} {}", p.manifest().name(), p.manifest().version()));
        return found;
    }
}
