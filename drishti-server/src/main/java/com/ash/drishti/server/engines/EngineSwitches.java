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
package com.ash.drishti.server.engines;

import java.util.List;
import java.util.Map;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * Each engine's switch as the server starts with it (docs/guides/ADMIN_GUIDE.md sections 3.3 and 4): its value, and where it
 * comes from, read from Spring's own sources in their order (the command line, the environment, the packs overlay, application.local.yaml,
 * the edition, the site's engine file, the jar's copy). An old switch name ({@code drishti.var.enabled}) set anywhere above the shipped files
 * wins, with one warning (DRS-1024). The in-memory lake is on when its stores are configured in the old place and nothing sets its switch.
 */
public final class EngineSwitches {

    /**
     * One engine's switch.
     *
     * @param on whether the engine is to run here
     * @param source where the value comes from, in words ({@code engine file}, {@code environment DRISHTI_FRTB_ENABLED}, {@code local file})
     * @param alias the old name that decided it, or null
     * @param overridden whether the value comes from above the engine's file (a change written to the file waits for that to be removed)
     */
    public record Switch(boolean on, String source, String alias, boolean overridden) {}

    private final Map<String, Switch> switches = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<String> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ConfigurableEnvironment env;

    public EngineSwitches(EngineCatalogue catalogue, ConfigurableEnvironment env) {
        this.env = env;
        for (EngineCatalogue.Entry e : catalogue.all()) {
            switches.put(e.id(), read(e, env));
        }
    }

    /**
     * Reads the switch of an engine a loaded module brought (its catalogue entry, its defaults now in the settings), as the server's own
     * were read at the start; forgotten again when it leaves.
     */
    public void learn(EngineCatalogue.Entry e) {
        if (env != null) {
            switches.put(e.id(), read(e, env));
        }
    }

    /** An engine that left with its module. */
    public void forget(String id) {
        switches.remove(id);
    }

    public Switch of(String id) {
        return switches.getOrDefault(id, new Switch(false, "not in the catalogue", null, false));
    }

    /** The DRS-1024 lines of the old names in use (logged once at start, shown beside the switch on Admin, Engines). */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    private Switch read(EngineCatalogue.Entry e, ConfigurableEnvironment env) {
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (skipForAliases(ps) || shipped(ps) || attached(ps)) {
                continue;
            }
            for (String alias : e.aliases()) {
                if (ps.containsProperty(alias)) {
                    boolean on = env.getProperty(alias, Boolean.class, e.shipped());
                    String where = describe(ps);
                    warnings.add(alias + " is the old name of " + e.switchKey() + " (set in " + where + "); it still works. Move it to config/engines/"
                            + e.file() + ", or use Admin -> Engines (DRS-1024).");
                    return new Switch(on, where + " (old name " + alias + ")", alias, true);
                }
            }
        }
        boolean on = Boolean.TRUE.equals(env.getProperty(e.switchKey(), Boolean.class, e.shipped()));
        PropertySource<?> from = null;
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (!"systemEnvironment".equals(ps.getName()) && !attached(ps) && ps.containsProperty(e.switchKey())) {
                from = ps;
                break;
            }
        }
        boolean envSet = !e.env().isBlank() && System.getenv(e.env()) != null;
        String raw = from == null ? "" : String.valueOf(from.getProperty(e.switchKey()));
        if (envSet && (from == null || raw.contains("${" + e.env() + ":") || raw.contains("${" + e.env() + "}"))) {
            return new Switch(on, "environment " + e.env(), null, true);
        }
        if (from == null) {
            return memoryLake(e, env, new Switch(on, "shipped default", null, false));
        }
        boolean fileOrShipped = shipped(from) || EngineFiles.isEngineFile(from.getName());
        return memoryLake(e, env, new Switch(on, describe(from), null, !fileOrShipped));
    }

    /** The in-memory lake on, when the switch is only the shipped one and stores are configured in the old place (application.local.yaml). */
    private static Switch memoryLake(EngineCatalogue.Entry e, ConfigurableEnvironment env, Switch s) {
        if (!"memory-lake".equals(e.id()) || s.on() || s.overridden()) {
            return s;
        }
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (shipped(ps) || attached(ps) || EngineFiles.isEngineFile(ps.getName()) || !(ps instanceof EnumerablePropertySource<?> en)) {
                continue;
            }
            for (String n : en.getPropertyNames()) {
                if (n.startsWith("drishti.lake.memory.stores.")) {
                    return new Switch(true, "stores configured in " + describe(ps) + " (the old place)", null, true);
                }
            }
        }
        return s;
    }

    /** Spring's own view over every source ({@code configurationProperties}), not a source of its own. */
    private static boolean attached(PropertySource<?> ps) {
        return "configurationProperties".equals(ps.getName());
    }

    private static boolean skipForAliases(PropertySource<?> ps) {
        // the environment's DRISHTI_VAR_ENABLED binds to drishti.var.enabled too; it is the switch's own variable, not an old name
        return "systemEnvironment".equals(ps.getName());
    }

    /** The jar's own files: application.yaml and the engines' shipped copies. */
    static boolean shipped(PropertySource<?> ps) {
        String n = ps.getName();
        return n.startsWith("Config resource") && n.contains("class path resource");
    }

    /** A source's name in words for Admin, Engines and {@code drishti.py server engines show --sources}. */
    static String describe(PropertySource<?> ps) {
        String n = ps.getName().replace('\\', '/');
        if ("commandLineArgs".equals(n)) {
            return "command line";
        }
        if ("systemProperties".equals(n)) {
            return "system property";
        }
        if ("systemEnvironment".equals(n)) {
            return "environment";
        }
        if (n.equals("drishti-edition")) {
            return "edition";
        }
        if (n.contains("application.local.yaml")) {
            return "local file";
        }
        if (EngineFiles.isEngineFile(n)) {
            return "engine file";
        }
        if (n.contains("class path resource [engines/")) {
            return "shipped copy";
        }
        if (n.contains("class path resource")) {
            return "application.yaml";
        }
        if (n.contains("added.yaml")) {
            return "packs overlay";
        }
        if (n.startsWith("Config resource")) {
            int a = n.indexOf('[');
            int b = n.indexOf(']');
            return a > 0 && b > a ? n.substring(a + 1, b) : n;
        }
        return n;
    }
}
