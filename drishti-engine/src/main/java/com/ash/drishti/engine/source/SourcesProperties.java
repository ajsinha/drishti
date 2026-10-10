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
 * @param connectors connectors defined in the server's own configuration (deprecated: use connector files)
 * @param connectorsDir the folder of connector files, one {@code <name>.yaml} per connector (default {@code ./config/connectors})
 * @param connectorsWatch {@code auto} (file events, polling when unavailable), {@code poll}, or {@code off}
 * @param connectorsPoll how often files are looked at when polling (and as a safety net beside file events)
 * @param connectorsDrain how long a replaced or stopped connector stays open so reads in flight finish
 * @param connectorsGenerate write a connector file from a pack's template when none of that name exists
 */
@ConfigurationProperties("drishti.sources")
public record SourcesProperties(
        Map<String, String> routes, String defaultRoute, Map<String, PluginSettings> plugins, Duration fetchTimeout, String pluginDir,
        Map<String, ConnectorSettings> connectors, String connectorsDir, String connectorsWatch, Duration connectorsPoll,
        Duration connectorsDrain, Boolean connectorsGenerate) {

    /** The shape before connector files: the new settings take their defaults. */
    public SourcesProperties(Map<String, String> routes, String defaultRoute, Map<String, PluginSettings> plugins, Duration fetchTimeout,
            String pluginDir, Map<String, ConnectorSettings> connectors) {
        this(routes, defaultRoute, plugins, fetchTimeout, pluginDir, connectors, null, null, null, null, null);
    }

    public SourcesProperties {
        routes = routes == null ? Map.of() : Map.copyOf(routes);
        plugins = plugins == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(plugins));
        connectors = connectors == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(connectors));   // config order is the read order
        fetchTimeout = fetchTimeout == null ? Duration.ofSeconds(2) : fetchTimeout;
        connectorsDir = connectorsDir == null || connectorsDir.isBlank() ? "./config/connectors" : connectorsDir;
        connectorsWatch = connectorsWatch == null || connectorsWatch.isBlank() ? "auto" : connectorsWatch;
        connectorsPoll = connectorsPoll == null ? Duration.ofSeconds(5) : connectorsPoll;
        connectorsDrain = connectorsDrain == null ? Duration.ofSeconds(5) : connectorsDrain;
        connectorsGenerate = connectorsGenerate == null ? Boolean.TRUE : connectorsGenerate;
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
