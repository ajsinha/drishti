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

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.sources.*}: which plugins run, their settings, and which plugin serves each kind.
 *
 * @param routes kind to plugin name
 * @param defaultRoute plugin tried first for kinds without a route
 * @param plugins per-plugin switch and settings, keyed by plugin name
 * @param fetchTimeout the longest a single fetch may take
 * @param pluginDir optional directory of extra plugin jars, each loaded in its own class loader
 */
@ConfigurationProperties("drishti.sources")
public record SourcesProperties(
        Map<String, String> routes, String defaultRoute, Map<String, PluginSettings> plugins, Duration fetchTimeout, String pluginDir,
        Map<String, ConnectorSettings> connectors) {

    public SourcesProperties {
        routes = routes == null ? Map.of() : Map.copyOf(routes);
        plugins = plugins == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(plugins));
        connectors = connectors == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(connectors));   // config order is the read order
        fetchTimeout = fetchTimeout == null ? Duration.ofSeconds(2) : fetchTimeout;
    }

    /**
     * @param enabled whether the plugin starts
     * @param settings free-form settings handed to the plugin
     */
    public record PluginSettings(Boolean enabled, Map<String, String> settings) {
        public PluginSettings {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            settings = settings == null ? Map.of() : Map.copyOf(settings);
        }
    }

    /**
     * A named instance of a plugin, e.g. {@code finance-lake: {plugin: delta, settings: {domain: finance}}}.
     *
     * @param plugin the plugin's name ({@code delta}, {@code jdbc}, {@code file}, …)
     * @param enabled whether it starts (default true)
     * @param kinds kinds it serves (default: what the plugin reports)
     * @param settings settings handed to this instance
     */
    public record ConnectorSettings(String plugin, Boolean enabled, java.util.List<String> kinds, Map<String, String> settings) {
        public ConnectorSettings {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            kinds = kinds == null ? java.util.List.of() : java.util.List.copyOf(kinds);
            settings = settings == null ? Map.of() : Map.copyOf(settings);
        }
    }

    public PluginSettings settingsFor(String plugin) {
        return plugins.getOrDefault(plugin, new PluginSettings(true, Map.of()));
    }
}
