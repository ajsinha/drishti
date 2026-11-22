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
import org.springframework.core.env.PropertySource;

/**
 * Each engine's switch as the server starts with it (docs/guides/ADMIN_GUIDE.md sections 3.3 and 4): its value, and where it
 * comes from, read from Spring's own sources in their order (the command line, the environment, the packs overlay, application.local.yaml,
 * the edition, the site's engine file, the jar's copy). An old switch name ({@code drishti.var.enabled}) set anywhere above the shipped files
 * stops the start, naming the new key ({@link com.ash.drishti.packs.module.RetiredNames}).
 */
public final class EngineSwitches {

    /**
     * One engine's switch.
     *
     * @param on whether the engine is to run here
     * @param source where the value comes from, in words ({@code engine file}, {@code environment DRISHTI_FRTB_ENABLED}, {@code local file})
     * @param overridden whether the value comes from above the engine's file (a change written to the file waits for that to be removed)
     */
    public record Switch(boolean on, String source, boolean overridden) {}

    private final Map<String, Switch> switches = new java.util.concurrent.ConcurrentHashMap<>();
    private final ConfigurableEnvironment env;

    public EngineSwitches(EngineCatalogue catalogue, ConfigurableEnvironment env) {
        this.env = env;
        List<String> retired = new java.util.ArrayList<>();
        for (EngineCatalogue.Entry e : catalogue.all()) {
            retired.addAll(retiredNames(e, env));
            switches.put(e.id(), read(e, env));
        }
        com.ash.drishti.packs.module.RetiredNames.refuse(retired);
    }

    /**
     * Reads the switch of an engine a loaded module brought (its catalogue entry, its defaults now in the settings), as the server's own
     * were read at the start; forgotten again when it leaves.
     */
    public void learn(EngineCatalogue.Entry e) {
        if (env != null) {
            com.ash.drishti.packs.module.RetiredNames.refuse(retiredNames(e, env));
            switches.put(e.id(), read(e, env));
        }
    }

    /** An engine that left with its module. */
    public void forget(String id) {
        switches.remove(id);
    }

    public Switch of(String id) {
        return switches.getOrDefault(id, new Switch(false, "not in the catalogue", false));
    }

    /** The refusal lines of the engine's retired switch names that a site sets: {@link com.ash.drishti.packs.module.RetiredNames#in}'s one rule. */
    private static List<String> retiredNames(EngineCatalogue.Entry e, ConfigurableEnvironment env) {
        List<String> out = new java.util.ArrayList<>();
        com.ash.drishti.packs.module.RetiredNames.Check check = com.ash.drishti.packs.module.RetiredNames.in(env);
        for (String old : e.retired()) {
            String where = check.whereSet(old);
            if (where != null) {
                out.add(com.ash.drishti.packs.module.RetiredNames.line(old, describe(where), e.switchKey() + " (config/engines/" + e.file() + ", or Admin -> Engines)"));
            }
        }
        return out;
    }

    private Switch read(EngineCatalogue.Entry e, ConfigurableEnvironment env) {
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
            return new Switch(on, "environment " + e.env(), true);
        }
        if (from == null) {
            return new Switch(on, "shipped default", false);
        }
        boolean fileOrShipped = shipped(from) || EngineFiles.isEngineFile(from.getName());
        return new Switch(on, describe(from), !fileOrShipped);
    }

    /** Spring's own view over every source ({@code configurationProperties}), not a source of its own. */
    private static boolean attached(PropertySource<?> ps) {
        return "configurationProperties".equals(ps.getName());
    }

    private static final String MODULE_COPY = com.ash.drishti.packs.module.RetiredNames.MODULE_COPY;

    /**
     * The name of a module's shipped copy of an engine's settings ({@code engines/<file>} in the module's own jars), as the module host adds
     * it: a shipped copy like the server's own, so a site's {@code config/engines/<file>} overrides it and Admin, Engines says where it came from.
     */
    public static String moduleCopy(String module, String file) {
        return MODULE_COPY + "[" + module + "] engines/" + file;
    }

    /** The jar's own files: application.yaml and the engines' shipped copies, the server's and each module's. */
    static boolean shipped(PropertySource<?> ps) {
        return com.ash.drishti.packs.module.RetiredNames.shipped(ps);
    }

    /** A source's name in words for Admin, Engines and {@code drishti.py server engines show --sources}. */
    static String describe(PropertySource<?> ps) {
        return describe(ps.getName());
    }

    /** {@link #describe(PropertySource)} by the source's name ({@code environment NAME} as it is). */
    static String describe(String name) {
        String n = name.replace('\\', '/');
        if (n.startsWith("environment ")) {
            return n;
        }
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
        if (n.startsWith(MODULE_COPY)) {
            return "shipped copy (module " + n.substring(n.indexOf('[') + 1, n.indexOf(']')) + ")";
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
