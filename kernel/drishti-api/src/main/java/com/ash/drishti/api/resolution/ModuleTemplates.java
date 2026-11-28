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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The connector templates and inbox rules the modules that are on bring (docs/architecture/KERNEL_AND_MODULES.md sections 18.5 and 18.7).
 * A template is a file directly in a module's connectors folder; its connector's name is the file's {@code name:} key, else the file's
 * name; files under {@code connectors/examples/} are examples to copy, never templates. Two templates of one name: neither is used
 * (DRS-1045). A module that is off brings nothing. Read-only.
 */
public final class ModuleTemplates {

    /** The template key that names the module a template came from (never a connector setting). */
    public static final String FROM_MODULE = "from-module";
    /**
     * The setting that names the module's folder, which a template's relative files (its {@code map:}) are read from: the kernel sets it on
     * every module template (SourceShape reads it first), as it does for a module's connector extension.
     */
    public static final String MODULE_DIR = "module-dir";

    /**
     * One template.
     *
     * @param name       the connector's name
     * @param module     the module that brings it
     * @param file       the template's file
     * @param definition the file's content without {@code name:}, with {@link #FROM_MODULE} and the setting {@link #MODULE_DIR}
     */
    public record Template(String name, String module, Path file, Map<String, Object> definition) {}

    /**
     * One inbox rule of a module.
     *
     * @param module the module
     * @param file   its rules file
     * @param rule   the rule as written
     */
    public record Rule(String module, Path file, Map<String, Object> rule) {}

    private ModuleTemplates() {
    }

    /** The templates of the modules that are on, by connector name in name order; problems are added to {@code problems}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Template> of(Collection<PackResolver.Module> modules, Predicate<String> active,
            Function<Path, Map<String, Object>> yaml, List<String> problems) {
        Map<String, List<Template>> found = new TreeMap<>();
        for (PackResolver.Module m : modules) {
            if (m.connectors() == null || !active.test(m.id()) || !Files.isDirectory(m.connectors())) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> s = Files.list(m.connectors())) {
                files = s.filter(Files::isRegularFile).filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.ya?ml"))
                        .sorted().toList();
            } catch (IOException e) {
                problems.add("the connectors of module " + m.id() + " (" + m.connectors() + ") cannot be listed: " + e.getMessage());
                continue;
            }
            for (Path f : files) {
                Map<String, Object> c;
                try {
                    c = new LinkedHashMap<>(yaml.apply(f));
                } catch (RuntimeException e) {
                    problems.add("the connector template " + f + " of module " + m.id() + " cannot be read: " + e.getMessage());
                    continue;
                }
                String stem = f.getFileName().toString().replaceFirst("\\.ya?ml$", "");
                Object n = c.remove("name");
                String name = n == null || String.valueOf(n).isBlank() ? stem : String.valueOf(n).strip();
                c.put(FROM_MODULE, m.id());
                Map<String, Object> settings = c.get("settings") instanceof Map<?, ?> sm ? new LinkedHashMap<>((Map<String, Object>) sm)
                        : new LinkedHashMap<>();
                settings.putIfAbsent(MODULE_DIR, m.home().toAbsolutePath().normalize().toString());
                c.put("settings", settings);
                found.computeIfAbsent(name, k -> new ArrayList<>()).add(new Template(name, m.id(), f, c));
            }
        }
        Map<String, Template> out = new LinkedHashMap<>();
        found.forEach((name, at) -> {
            if (at.size() > 1) {
                problems.add("connector '" + name + "' is declared by module " + at.get(0).module() + " (" + at.get(0).file() + ") and by module "
                        + at.get(1).module() + " (" + at.get(1).file() + "); neither is used: remove one declaration (DRS-1045)");
            } else {
                out.put(name, at.get(0));
            }
        });
        return out;
    }

    /** The inbox rules of the modules that are on, in module order; an unreadable rules file is a problem (DRS-1048), never an empty list. */
    public static List<Rule> rules(Collection<PackResolver.Module> modules, Predicate<String> active, Function<Path, Map<String, Object>> yaml,
            List<String> problems) {
        List<Rule> out = new ArrayList<>();
        for (PackResolver.Module m : modules) {
            if (m.dataload() == null || !active.test(m.id())) {
                continue;
            }
            try {
                Object d = yaml.apply(m.dataload()).get("dataload");
                Object rules = d instanceof Map<?, ?> dm ? dm.get("rules") : null;
                if (rules instanceof List<?> l) {
                    for (Object r : l) {
                        if (r instanceof Map<?, ?> rm) {
                            Map<String, Object> one = new LinkedHashMap<>();
                            rm.forEach((k, v) -> one.put(String.valueOf(k), v));
                            out.add(new Rule(m.id(), m.dataload(), one));
                        }
                    }
                }
            } catch (RuntimeException e) {
                problems.add("the inbox rules of module " + m.id() + " (" + m.dataload() + ") cannot be read: " + e.getMessage()
                        + "; its files will not be matched (DRS-1048)");
            }
        }
        return out;
    }

    /**
     * The modules that are on when there is no server to ask (the command line, the loader process): every module found, less those an
     * administrator switched off ({@code <switches>/<id>.yaml} {@code enabled: false}, or the setting {@code drishti.modules.<id>.enabled}),
     * less those whose required module is off. The licence is not evaluated here (section 18.1).
     */
    public static Predicate<String> offline(Map<String, PackResolver.Module> modules, Path switchesDir, Function<String, String> property,
            Function<Path, Map<String, Object>> yaml) {
        return offline(modules, null, switchesDir, property, yaml);
    }

    /** As above, the release's shipped switch files ({@code defaultsDir}) read where the site's folder has no file of a module. */
    public static Predicate<String> offline(Map<String, PackResolver.Module> modules, Path defaultsDir, Path switchesDir,
            Function<String, String> property, Function<Path, Map<String, Object>> yaml) {
        Map<String, Boolean> on = new TreeMap<>();
        for (String id : modules.keySet()) {
            Boolean set = null;
            String v = property.apply("drishti.modules." + id + ".enabled");
            if (v != null && !v.isBlank()) {
                set = List.of("true", "on", "yes").contains(v.strip().toLowerCase(Locale.ROOT));
            } else {
                for (Path dir : new Path[] {defaultsDir, switchesDir}) {   // the release's file first, the site's after it (it wins)
                    if (dir != null && Files.isRegularFile(dir.resolve(id + ".yaml"))) {
                        try {
                            Object e = yaml.apply(dir.resolve(id + ".yaml")).get("enabled");
                            set = e == null ? set : Boolean.valueOf(String.valueOf(e));
                        } catch (RuntimeException e) {
                            set = null;                                  // an unreadable switch: the module's own default
                        }
                    }
                }
            }
            on.put(id, set == null || set);
        }
        boolean changed = true;
        while (changed) {                                               // a module whose requirement is off is off
            changed = false;
            for (PackResolver.Module m : modules.values()) {
                if (on.get(m.id()) && m.requires().stream().anyMatch(r -> modules.containsKey(r) && !on.get(r))) {
                    on.put(m.id(), false);
                    changed = true;
                }
            }
        }
        return id -> "core".equals(id) || on.getOrDefault(id == null ? "" : id.strip().toLowerCase(Locale.ROOT), false);
    }
}
