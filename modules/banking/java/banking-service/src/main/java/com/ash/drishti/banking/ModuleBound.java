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
package com.ash.drishti.banking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.Environment;

/**
 * Settings the banking module owns, read from their one name {@code drishti.module.banking.<key>} (the manifest's {@code settings:}). An engine's
 * settings record binds the rest of its settings from the engine's own prefix ({@code drishti.var.*}) as always; the few the module owns come from
 * the module's key, and from nowhere else: the server key they had before is retired and a site that sets it stops the start
 * ({@code com.ash.drishti.packs.module.RetiredNames}).
 */
public final class ModuleBound {

    /** The prefix of the module's settings. */
    public static final String PREFIX = "drishti.module.banking.";

    private ModuleBound() {
    }

    /**
     * Binds {@code type} from {@code prefix}, taking each setting in {@code owned} (the record's property under {@code prefix} to the module's key
     * below {@link #PREFIX}) from the module's key when it is set.
     */
    public static <T> T bind(Environment env, String prefix, Class<T> type, Map<String, String> owned) {
        Map<String, Object> given = new LinkedHashMap<>();
        owned.forEach((property, key) -> {
            String v = env.getProperty(PREFIX + key);
            if (v != null && !v.isBlank()) {
                given.put(prefix + "." + property, v);
            }
        });
        List<ConfigurationPropertySource> sources = new ArrayList<>();
        sources.add(new MapConfigurationPropertySource(given));
        ConfigurationPropertySources.get(env).forEach(sources::add);
        return new Binder(sources, new PropertySourcesPlaceholdersResolver(env)).bindOrCreate(prefix, Bindable.of(type));
    }
}
