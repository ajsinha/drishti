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
package com.ash.drishti.packs;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Loads the enabled packs ({@code drishti.packs.enabled}, plus {@code drishti.packs.added}: the packs an administrator
 * loaded from Admin → Packs, kept in the overlay file), from {@code drishti.packs.dir}, before any bean is built, and
 * adds their content as the lowest-precedence property source, so the site's own configuration always wins over a
 * pack. The core modules only read ordinary properties and never depend on this module.
 */
public final class PackEnvironmentPostProcessor implements EnvironmentPostProcessor {

    public static final String SOURCE = "drishti-packs";
    /** The connector files, ahead of the packs' own values and behind the server's configuration. */
    public static final String FILES_SOURCE = "drishti-connector-files";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String dir = PackPaths.packsDir(env);
        if (System.getProperty(com.ash.drishti.api.DataDir.PROPERTY) == null) {           // plugins have no Spring environment: hand them the umbrella
            System.setProperty(com.ash.drishti.api.DataDir.PROPERTY, PackPaths.dataDir(env));
        }
        if (System.getProperty("drishti.index.dir") == null) {                              // the type-ahead segment folder, for the same reason
            String idx = env.getProperty("drishti.index.dir", "");
            if (!idx.isBlank()) {
                System.setProperty("drishti.index.dir", idx);
            }
        }
        String enabled = PackPaths.enabled(env);
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(
                Arrays.stream(enabled.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        names.addAll(org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("drishti.packs.added", org.springframework.boot.context.properties.bind.Bindable.listOf(String.class))
                .orElse(List.of()));
        com.ash.drishti.packs.edition.Edition edition = com.ash.drishti.packs.edition.Editions.of(env);
        List<String> notInEdition = names.stream().filter(n -> !edition.packAllowed(n)).toList();
        if (!notInEdition.isEmpty()) {                                                   // allow-only: the edition's packs, nothing else
            System.getLogger(PackEnvironmentPostProcessor.class.getName()).log(System.Logger.Level.WARNING,
                    "edition {0} is allow-only: not loading {1} (named by DRISHTI_PACKS, drishti.packs.enabled or Admin -> Packs)", edition.name(), notInEdition);
            names.removeAll(notInEdition);
        }
        String connDir = env.getProperty("drishti.sources.connectors-dir", System.getenv().getOrDefault("DRISHTI_CONNECTORS_DIR", "./config/connectors"));
        ConnectorFiles files = new ConnectorFiles(java.nio.file.Path.of(connDir), java.nio.file.Path.of(PackPaths.dataDir(env), "connectors"));
        PackSettings overrides = new PackSettings(java.nio.file.Path.of(PackPaths.settingsDir(env)));
        PackLoader loader = new PackLoader(files);
        List<java.nio.file.Path> dirs = PackLoader.dirs(dir, PackPaths.installedDir(env), PackPaths.extraDirs(env));
        SamplePolicy samples = SamplePolicy.of(env);                                    // hidden: sample packs are not loaded
        List<Pack> packs = loader.load(dirs, samples.select(dirs, List.copyOf(names), loader));
        java.util.Set<String> suppressed = new java.util.LinkedHashSet<>();
        if (samples.hidden()) {                                                          // ... and the connectors only they use do not start
            suppressed.addAll(SamplePolicy.sampleOnlyConnectors(dirs, loader));
            packs.forEach(p -> suppressed.removeAll(p.connectorRefs()));
        }
        // connector files: migrate old per-pack overrides once, write files from the packs' templates where none exist
        ConnectorBootstrap.Result boot = ConnectorBootstrap.run(packs, files, overrides,
                Boolean.parseBoolean(env.getProperty("drishti.sources.connectors-generate", "true")), officialReaders(env));
        // the packs' own properties are computed after the files exist, so a template is left out where a file stands in for it
        env.getPropertySources().addLast(new MapPropertySource(SOURCE, loader.properties(packs)));
        java.util.Map<String, String> problems = new java.util.LinkedHashMap<>();
        java.util.Map<String, ConnectorFiles.Definition> defs = new java.util.LinkedHashMap<>(files.readAll(problems));
        suppressed.forEach(defs::remove);
        // an enabled placeholder that resolves to neither true nor false refuses the file, as a hot reload does (QA4-04)
        for (var it = defs.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            String raw = e.getValue().enabled();
            if (raw.contains("${")) {
                String v = env.resolvePlaceholders(raw);
                if (!v.contains("${")) {                                 // a variable not set is the server's own refusal
                    var refused = e.getValue().enabledRefusal(v);
                    if (refused.isPresent()) {
                        problems.put(e.getKey(), refused.get());
                        it.remove();
                    }
                }
            }
        }
        java.util.Map<String, Object> fileProps = new java.util.LinkedHashMap<>(ConnectorFiles.properties(defs));
        int si = 0;
        for (String n : suppressed) {
            fileProps.put("drishti.sources.connectors-suppressed[" + si++ + "]", n);
        }
        for (int i = 0; i < boot.messages().size(); i++) {
            fileProps.put("drishti.sources.connectors-bootstrap[" + i + "]", boot.messages().get(i));
        }
        for (int i = 0; i < boot.generated().size(); i++) {
            fileProps.put("drishti.sources.connectors-generated[" + i + "]", boot.generated().get(i));
        }
        for (int i = 0; i < boot.migrated().size(); i++) {
            fileProps.put("drishti.sources.connectors-migrated[" + i + "]", boot.migrated().get(i));
        }
        problems.forEach((n, m) -> fileProps.put("drishti.sources.connector-file-problems." + n, m));
        env.getPropertySources().addBefore(SOURCE, new MapPropertySource(FILES_SOURCE, fileProps));
    }

    /** A pack's template as the server reads it: the readers of official results follow their publishing format (OfficialReaders). */
    public static java.util.function.UnaryOperator<java.util.Map<String, Object>> officialReaders(org.springframework.core.env.ConfigurableEnvironment env) {
        org.springframework.boot.context.properties.bind.Binder binder = org.springframework.boot.context.properties.bind.Binder.get(env);
        return t -> OfficialReaders.adjust(t, env::getProperty, prefix -> List.copyOf(binder.bind(prefix,
                org.springframework.boot.context.properties.bind.Bindable.mapOf(String.class, String.class)).orElse(java.util.Map.of()).keySet()));
    }
}
