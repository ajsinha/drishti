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

import com.ash.drishti.api.SettingSpec;
import com.ash.drishti.api.SourcePlugin;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The settings each source plugin reads, so Admin &rarr; Connectors can generate a form and check a file. A plugin that
 * declares its own ({@link SourcePlugin#settingSpecs()}) wins; the shipped plugins are described in
 * {@code drishti/plugin-settings.yaml}. A plugin with {@code tls: true} also carries the shared {@code tls.*} section.
 */
public final class SettingCatalogue {

    /** What the form and validator know about one plugin. */
    public record PluginSpec(String plugin, boolean tls, List<SettingSpec> settings, boolean declared) {

        /** The spec that covers {@code key}, or null. */
        public SettingSpec find(String key) {
            for (SettingSpec s : settings) {
                if (s.covers(key)) {
                    return s;
                }
            }
            return null;
        }
    }

    private final Map<String, PluginSpec> byPlugin = new LinkedHashMap<>();

    public SettingCatalogue(List<SourcePlugin> discovered) {
        Map<String, Object> doc = load();
        List<SettingSpec> common = specs(doc.get("common"));
        List<SettingSpec> tls = specs(doc.get("tls"));
        @SuppressWarnings("unchecked")
        Map<String, Object> plugins = doc.get("plugins") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        for (SourcePlugin p : discovered) {
            String name = p.manifest().name();
            List<SettingSpec> own = new ArrayList<>(p.settingSpecs());
            boolean declared = !own.isEmpty();
            boolean supportsTls = false;
            if (!declared && plugins.get(name) instanceof Map<?, ?> entry) {
                own.addAll(specs(entry.get("settings")));
                supportsTls = Boolean.TRUE.equals(entry.get("tls"));
                declared = !own.isEmpty();
            }
            List<SettingSpec> all = new ArrayList<>(own);
            if (supportsTls) {
                tls.stream().filter(t -> all.stream().noneMatch(a -> a.name().equals(t.name()))).forEach(all::add);
            }
            if (declared) {
                common.stream().filter(c -> all.stream().noneMatch(a -> a.name().equals(c.name()))).forEach(all::add);
            }
            byPlugin.put(name, new PluginSpec(name, supportsTls, List.copyOf(all), declared));
        }
    }

    public Map<String, PluginSpec> all() {
        return java.util.Collections.unmodifiableMap(byPlugin);
    }

    public PluginSpec of(String plugin) {
        return byPlugin.get(plugin);
    }

    private static Map<String, Object> load() {
        try (InputStream in = SettingCatalogue.class.getResourceAsStream("/drishti/plugin-settings.yaml")) {
            if (in == null) {
                return Map.of();
            }
            return new ObjectMapper(new YAMLFactory()).readValue(in, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<SettingSpec> specs(Object list) {
        List<SettingSpec> out = new ArrayList<>();
        if (list instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    out.add(new SettingSpec(String.valueOf(m.get("name")), m.get("type") == null ? null : String.valueOf(m.get("type")),
                            Boolean.TRUE.equals(m.get("required")), m.get("default") == null ? null : String.valueOf(m.get("default")),
                            m.get("desc") == null ? null : String.valueOf(m.get("desc")), Boolean.TRUE.equals(m.get("secret")),
                            m.get("group") == null ? null : String.valueOf(m.get("group"))));
                }
            }
        }
        return out;
    }
}
