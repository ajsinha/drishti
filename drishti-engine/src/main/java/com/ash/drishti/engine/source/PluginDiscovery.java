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
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds source plugins: those on the application class path, and each jar in an optional plugin
 * directory, loaded in its own class loader whose parent exposes only the application (and so the API).
 */
public final class PluginDiscovery {

    private static final Logger LOG = LoggerFactory.getLogger(PluginDiscovery.class);

    public List<SourcePlugin> discover(String pluginDir) {
        List<SourcePlugin> found = new ArrayList<>();
        ServiceLoader.load(SourcePlugin.class, SourcePlugin.class.getClassLoader()).forEach(found::add);
        if (pluginDir != null && !pluginDir.isBlank() && Files.isDirectory(Path.of(pluginDir))) {
            try (Stream<Path> jars = Files.list(Path.of(pluginDir))) {
                for (Path jar : jars.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                    found.addAll(loadIsolated(jar));
                }
            } catch (IOException e) {
                throw new DrishtiException(ErrorCode.PLUGIN_LOAD_FAILED, "cannot list " + pluginDir, e);
            }
        }
        found.forEach(p -> LOG.info("source plugin found: {} {}", p.manifest().name(), p.manifest().version()));
        return found;
    }

    private List<SourcePlugin> loadIsolated(Path jar) {
        try {
            URLClassLoader loader = new URLClassLoader("plugin:" + jar.getFileName(),
                    new URL[] {jar.toUri().toURL()}, SourcePlugin.class.getClassLoader());
            List<SourcePlugin> out = new ArrayList<>();
            ServiceLoader.load(SourcePlugin.class, loader).forEach(out::add);
            return out;
        } catch (MalformedURLException e) {
            throw new DrishtiException(ErrorCode.PLUGIN_LOAD_FAILED, "bad plugin jar " + jar, e);
        }
    }
}
