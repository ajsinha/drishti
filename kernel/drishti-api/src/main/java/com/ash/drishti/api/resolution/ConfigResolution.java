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
package com.ash.drishti.api.resolution;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One immutable snapshot of where everything comes from (docs/architecture/KERNEL_AND_MODULES.md section 18.3): the packs of the three
 * layers, the connector templates and inbox rules of the modules that are on, every problem, and a version. A process holds the current
 * one ({@link #current()}); a rebuild (a module switched, a file changed, an administrator's save) replaces it whole and tells every
 * listener once ({@link #onChange}). The server builds it from the access registry's "what is on"; the command line and the loader process
 * from the switch files ({@link #offline}).
 *
 * @param settings  where the layers are
 * @param active    whether a module is on
 * @param packs     the packs
 * @param templates the modules' connector templates, by name
 * @param rules     the modules' inbox rules, in module order
 * @param problems  every problem, in words
 * @param version   changes whenever anything above does
 */
public record ConfigResolution(ResolutionSettings settings, Predicate<String> active, PackResolver.Result packs,
        Map<String, ModuleTemplates.Template> templates, List<ModuleTemplates.Rule> rules, List<String> problems, long version) {

    private static final AtomicReference<ConfigResolution> CURRENT = new AtomicReference<>();
    private static final List<Consumer<ConfigResolution>> LISTENERS = new CopyOnWriteArrayList<>();

    /** Resolves everything from the settings and the one "what is on" answer. */
    public static ConfigResolution build(ResolutionSettings settings, Predicate<String> active) {
        Function<Path, Map<String, Object>> yaml = ResolutionSettings::yaml;
        PackResolver.Result packs = PackResolver.resolve(settings.layout(), active, yaml);
        List<String> problems = new ArrayList<>(packs.problems());
        Map<String, ModuleTemplates.Template> templates = ModuleTemplates.of(packs.modules().values(), active, yaml, problems);
        List<ModuleTemplates.Rule> rules = ModuleTemplates.rules(packs.modules().values(), active, yaml, problems);
        long v = Objects.hash(packs.packs().keySet(), packs.packs().values().stream().map(PackResolver.Located::dir).toList(), packs.off(),
                templates.keySet(), templates.values().stream().map(t -> t.definition().toString()).toList(), rules.size(), problems);
        return new ConfigResolution(settings, active, packs, templates, rules, List.copyOf(problems), v);
    }

    /** As {@link #build}, "what is on" from the switch files when no server is there to ask (section 18.1). */
    public static ConfigResolution offline(Function<String, String> property) {
        ResolutionSettings s = ResolutionSettings.of(property);
        String switches = property.apply("drishti.modules.dir");
        Path sw = Path.of(switches == null || switches.isBlank() ? com.ash.drishti.api.Roots.site("modules") : switches.strip()).toAbsolutePath().normalize();
        Map<String, PackResolver.Module> modules = PackResolver.modules(s.layout(), ResolutionSettings::yaml, new ArrayList<>());
        return build(s, ModuleTemplates.offline(modules, Path.of(com.ash.drishti.api.Roots.config("modules")), sw, property, ResolutionSettings::yaml));
    }

    /**
     * A checkout's resolution with every module on (the {@code all} distribution): what a test or a tool of the shipped files means. The
     * tools read it through {@code resolve --json --root} ({@code tools/resolved.py repository()}). Its modules are the checkout's source
     * folders in source mode ({@link ModuleHomes#source}): read where they lie, never a package, never the cache. Not installed as the
     * process's current one.
     *
     * @param root the repository's root (holds {@code config/packs} and {@code modules})
     */
    public static ConfigResolution repository(Path root) {
        Path r = root.toAbsolutePath().normalize();
        Path site = r.resolve("config/packs");
        return build(new ResolutionSettings(site, site.resolve("no-installed-packs"), site.resolve("core"), r.resolve(ModuleHomes.SOURCE_MODULES),
                List.of(), ModuleHomes.source(r)), id -> true);
    }

    /**
     * A resolution of this process's own settings, built now (a command line, a loader process): each setting from its system property,
     * else its environment variable ({@code drishti.packs.dir} -> {@code DRISHTI_PACKS_DIR}). Never another server's in the same JVM.
     */
    public static ConfigResolution ofProcess() {
        return offline(k -> {
            String v = System.getProperty(k);
            return v != null ? v : System.getenv(k.toUpperCase(java.util.Locale.ROOT).replace('.', '_').replace('-', '_'));
        });
    }

    /** Every pack folder of this resolution, in pack-name order. */
    public List<Path> packDirs() {
        return new java.util.TreeMap<>(packs.packs()).values().stream().map(PackResolver.Located::dir).toList();
    }

    /** The process's current resolution, or null before the first is installed. */
    public static ConfigResolution current() {
        return CURRENT.get();
    }

    /** The current resolution, or an offline one of the process's system properties installed now (a tool, a loader process, a test). */
    public static ConfigResolution currentOrOffline() {
        ConfigResolution r = CURRENT.get();
        if (r == null) {
            r = ofProcess();
            CURRENT.compareAndSet(null, r);
            r = CURRENT.get();
        }
        return r;
    }

    /** Makes {@code next} the current resolution; listeners hear of it once, and only when its version differs. Returns true when it did. */
    public static boolean install(ConfigResolution next) {
        ConfigResolution before = CURRENT.getAndSet(next);
        if (before != null && before.version() == next.version()) {
            return false;
        }
        for (Consumer<ConfigResolution> l : LISTENERS) {
            try {
                l.accept(next);
            } catch (RuntimeException e) {
                System.getLogger(ConfigResolution.class.getName()).log(System.Logger.Level.WARNING, "a resolution listener failed: " + e);
            }
        }
        return true;
    }

    /** Tells {@code listener} of every new resolution ({@code resolution.changed}); close the answer to stop. */
    public static AutoCloseable onChange(Consumer<ConfigResolution> listener) {
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    /** A pack's folder, or null when no layer has it (or its module is off). */
    public Path packDir(String name) {
        return packs.dir(name);
    }

    /** The modules' templates for the kinds a pack defines: what joins that pack's {@code connectors:} (a pack's own wins a name). */
    public Map<String, Map<String, Object>> templatesForKinds(java.util.Collection<String> kinds) {
        Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        templates.forEach((n, t) -> {
            Object k = t.definition().get("kinds");
            List<String> names = k instanceof List<?> l ? l.stream().map(String::valueOf).toList()
                    : k instanceof Map<?, ?> m ? m.keySet().stream().map(String::valueOf).toList() : List.of();
            if (!names.isEmpty() && kinds.contains(names.get(0))) {
                out.put(n, t.definition());
            }
        });
        return out;
    }
}
