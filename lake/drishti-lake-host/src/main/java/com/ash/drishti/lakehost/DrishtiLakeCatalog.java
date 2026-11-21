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
package com.ash.drishti.lakehost;

import com.ash.drishti.api.PackFolders;
import com.ash.drishti.api.resolution.ConfigResolution;
import com.ash.drishti.api.resolution.ModuleHomes;
import com.ash.drishti.common.Extensions;
import com.ash.drishti.common.ModuleLoaders;
import com.ash.drishti.kernel.api.vocabulary.DomainVocabulary;
import com.ash.lake.config.Lakes;
import com.ash.lake.spi.LakeCatalog;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;

/**
 * Drishti's {@link LakeCatalog}: what Drishti adds to Drishti Lake's lake configuration. The lake files its jars ship (the kernel's
 * {@code drishti/lakes/shipped.txt} and each live module's, a module's leaving with it), the packs the one resolution finds, the
 * connector templates of the packs' kinds, the lake writers its domains name, the extensions its loaded modules bring (a
 * {@code ReadyRule}) and the module boot's settings a loader's child process must see.
 */
public final class DrishtiLakeCatalog implements LakeCatalog {

    /** Where every jar lists the lake files it ships. */
    public static final String SHIPPED_INDEX = "drishti/lakes/shipped.txt";

    private static final List<String> CORE = Lakes.shippedIn(DrishtiLakeCatalog.class.getClassLoader(), SHIPPED_INDEX);
    private static final Map<ClassLoader, List<String>> MODULES = java.util.Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public List<String> shippedLakes() {
        Set<String> out = new LinkedHashSet<>(CORE);
        for (ClassLoader l : ModuleLoaders.live()) {
            out.addAll(MODULES.computeIfAbsent(l, x -> Lakes.shippedIn(x, SHIPPED_INDEX)));
        }
        return List.copyOf(out);
    }

    @Override
    public InputStream shippedLake(String name) {
        String res = "drishti/lakes/" + name + ".yaml";
        InputStream in = DrishtiLakeCatalog.class.getClassLoader().getResourceAsStream(res);
        for (ClassLoader l : ModuleLoaders.live()) {
            if (in != null) {
                break;
            }
            in = l.getResourceAsStream(res);
        }
        return in;
    }

    /** The site's packs folder: every pack the one resolution finds; any other folder: its packs and its domain folders'. */
    @Override
    public List<Path> packFiles(Path root) {
        ConfigResolution r = ConfigResolution.currentOrOffline();
        List<Path> out = new ArrayList<>();
        if (root.toAbsolutePath().normalize().equals(r.settings().siteDir())) {
            r.packs().packs().values().forEach(l -> out.add(l.dir().resolve("pack.yaml")));
            return out;
        }
        PackFolders.list(root).stream().map(d -> d.resolve("pack.yaml")).forEach(out::add);
        return out;
    }

    @Override
    public Map<String, Map<String, Object>> connectorTemplates(Collection<String> kinds) {
        return ConfigResolution.currentOrOffline().templatesForKinds(kinds);
    }

    @Override
    public Set<String> namedLakeWriters() {
        return DomainVocabulary.union(DomainVocabulary::namedLakeWriters);
    }

    @Override
    public <T> List<T> extensions(Class<T> type) {
        return Extensions.of(type);
    }

    /** The module boot's result ({@code drishti.module.homes}, {@code drishti.module.dir.*}, {@code drishti.modules.dev}) and the settings file. */
    @Override
    public Map<String, String> childProcessSettings() {
        Map<String, String> out = new TreeMap<>();
        for (String k : System.getProperties().stringPropertyNames()) {
            if (k.equals(ModuleHomes.PROPERTY) || k.startsWith(ModuleHomes.DIR_PROPERTY) || k.equals("drishti.modules.dev")
                    || k.equals("drishti.settings.file") || k.equals("drishti.home") || k.equals("drishti.data.dir")
                    || k.equals("drishti.config.dir")) {
                out.put(k, System.getProperty(k));
            }
        }
        return out;
    }
}
