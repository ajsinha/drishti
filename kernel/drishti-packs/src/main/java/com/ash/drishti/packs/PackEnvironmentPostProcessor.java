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
        // the lake files' references (${DRISHTI_DELTA_ROOT:...}, lake.lakes.dir) resolved with the server's settings, as a connector
        // file's are (docs/guides/DATA_LAKE_GUIDE.md): an environment variable, a system property, a --setting or a test's property
        // the named lakes, databases and brokers ([lake.<name>], [db.<name>], [broker.<name>], [connector.<name>], [pack.<name>]) read
        // through the same settings: the settings files' entries, -D, the environment, a --setting or a test's property
        com.ash.drishti.common.NamedResources.useView(k -> {
            try {
                return env.getProperty(k);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }, () -> {
            java.util.Set<String> keys = new java.util.LinkedHashSet<>();
            for (org.springframework.core.env.PropertySource<?> ps : env.getPropertySources()) {
                if (ps instanceof org.springframework.core.env.EnumerablePropertySource<?> e && !ps.getName().equals("configurationProperties")) {
                    keys.addAll(Arrays.asList(e.getPropertyNames()));
                }
            }
            return keys;
        });
        if (System.getProperty(com.ash.drishti.api.DataDir.PROPERTY) == null) {           // plugins have no Spring environment: hand them the umbrella
            System.setProperty(com.ash.drishti.api.DataDir.PROPERTY, PackPaths.dataDir(env));
        }
        // the one LakeRuntime of the server (Drishti's catalog, the mem:// storage): its options and the lake files' references read through
        // the same settings
        com.ash.drishti.lakehost.DrishtiLake.use(com.ash.drishti.lakehost.DrishtiLake.build(k -> {
            try {
                String v = env.getProperty(k);
                return v != null ? v : System.getProperty(k);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }, () -> {
            java.util.Set<String> keys = new java.util.LinkedHashSet<>();
            for (org.springframework.core.env.PropertySource<?> ps : env.getPropertySources()) {
                if (ps instanceof org.springframework.core.env.EnumerablePropertySource<?> e && !ps.getName().equals("configurationProperties")) {
                    keys.addAll(Arrays.asList(e.getPropertyNames()));
                }
            }
            return keys;
        }));
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
        String connDir = env.getProperty("drishti.sources.connectors-dir", System.getenv().getOrDefault("DRISHTI_CONNECTORS_DIR", com.ash.drishti.api.Roots.site("connectors")));
        ConnectorFiles files = new ConnectorFiles(com.ash.drishti.api.Roots.anchored(connDir), com.ash.drishti.api.Roots.anchored(PackPaths.dataDir(env)).resolve("connectors"));
        PackLoader loader = new PackLoader(files);
        // where every pack is: the one resolution (KERNEL_AND_MODULES.md section 18), installed for every reader of this process
        com.ash.drishti.api.resolution.ConfigResolution resolution = PackPaths.resolve(env);
        resolution.problems().forEach(p -> System.getLogger(PackEnvironmentPostProcessor.class.getName()).log(System.Logger.Level.WARNING, p));
        names.removeIf(n -> resolution.packs().off().containsKey(n.trim()));        // a pack of a module that is off: not loaded (Health says why)
        SamplePolicy samples = SamplePolicy.of(env);                                    // hidden: sample packs are not loaded
        List<Pack> packs = loader.load(resolution, samples.select(resolution, List.copyOf(names), loader));
        java.util.Set<String> suppressed = new java.util.LinkedHashSet<>();
        if (samples.hidden()) {                                                          // ... and the connectors only they use do not start
            suppressed.addAll(SamplePolicy.sampleOnlyConnectors(resolution, loader));
        }
        // a pack of a module that is off is not loaded, and neither the connectors only such packs use nor the module's own start
        // (docs/architecture/KERNEL_AND_MODULES.md: module off = gone everywhere)
        com.ash.drishti.registry.AccessRegistry access = Modules.of(env);
        List<Pack> offPacks = packs.stream().filter(p -> !access.packOn(p.name(), p.domain())).toList();
        if (!offPacks.isEmpty()) {
            System.getLogger(PackEnvironmentPostProcessor.class.getName()).log(System.Logger.Level.INFO,
                    "not loading {0}: their module is off (config/modules/<module>.yaml, DRISHTI_MODULES_<MODULE>_ENABLED)", offPacks.stream().map(Pack::name).toList());
            packs = new java.util.ArrayList<>(packs);
            packs.removeAll(offPacks);
            offPacks.forEach(p -> suppressed.addAll(p.connectorRefs()));
        }
        for (com.ash.drishti.registry.ModuleState m : access.modules()) {
            if (!m.on()) {
                access.manifest(m.id()).ifPresent(man -> suppressed.addAll(man.connectors()));
            }
        }
        List<Pack> loaded = packs;
        loaded.forEach(p -> suppressed.removeAll(p.connectorRefs()));
        // the connectors: each one's layers (template, site override, secrets) merged in memory; the server writes no connector file
        // (docs/guides/DATA_GUIDE.md, "Connector layers"). A template is left out of the packs' properties where a site file is in force.
        env.getPropertySources().addLast(new MapPropertySource(SOURCE, loader.properties(packs)));
        java.util.Map<String, ConnectorLayers.Template> templates = ConnectorLayers.templates(packs, officialReaders(env));
        java.util.Map<String, String> problems = new java.util.LinkedHashMap<>();
        java.util.Map<String, ConnectorFiles.Definition> defs = new java.util.LinkedHashMap<>(ConnectorLayers.resolve(templates, files, problems));
        List<String> superseded = ConnectorLayers.superseded(templates, files);
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
        // the site's data bindings (config/bindings.yaml, KERNEL_AND_MODULES.md section 5.4) are the one home of which connectors serve each kind
        try {
            bindingsFile(env, connDir).read();
        } catch (RuntimeException e) {
            System.getLogger(PackEnvironmentPostProcessor.class.getName()).log(System.Logger.Level.WARNING,  // Health says it again
                    "data bindings: {0} is not usable, every kind resolves without it: {1}", bindingsFile(env, connDir).path(), e.getMessage());
        }
        java.util.Map<String, Object> fileProps = new java.util.LinkedHashMap<>(ConnectorFiles.properties(defs));
        int si = 0;
        for (String n : suppressed) {
            fileProps.put("drishti.sources.connectors-suppressed[" + si++ + "]", n);
        }
        for (int i = 0; i < superseded.size(); i++) {
            fileProps.put("drishti.sources.connectors-bootstrap[" + i + "]", "connector file " + superseded.get(i) + ".yaml was generated by an earlier "
                    + "release and never edited: it is ignored, the template is in force (safe to delete)");
        }
        problems.forEach((n, m) -> fileProps.put("drishti.sources.connector-file-problems." + n, m));
        env.getPropertySources().addBefore(SOURCE, new MapPropertySource(FILES_SOURCE, fileProps));
    }

    /** The site's {@code bindings.yaml}: {@code drishti.bindings.file}, else beside the connectors' folder. */
    public static com.ash.drishti.packs.bindings.BindingsFile bindingsFile(org.springframework.core.env.Environment env, String connectorsDir) {
        String f = env.getProperty(com.ash.drishti.packs.bindings.BindingsFile.PROPERTY, "");
        return f.isBlank() ? com.ash.drishti.packs.bindings.BindingsFile.besideConnectors(java.nio.file.Path.of(connectorsDir))
                : new com.ash.drishti.packs.bindings.BindingsFile(java.nio.file.Path.of(f));
    }

    /** A pack's template as the server reads it: the readers of official results follow their publishing format (OfficialReaders). */
    public static java.util.function.UnaryOperator<java.util.Map<String, Object>> officialReaders(org.springframework.core.env.ConfigurableEnvironment env) {
        org.springframework.boot.context.properties.bind.Binder binder = org.springframework.boot.context.properties.bind.Binder.get(env);
        return t -> OfficialReaders.adjust(t, env::getProperty, prefix -> List.copyOf(binder.bind(prefix,
                org.springframework.boot.context.properties.bind.Bindable.mapOf(String.class, String.class)).orElse(java.util.Map.of()).keySet()));
    }
}
