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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Where every pack is: the one resolver (docs/architecture/KERNEL_AND_MODULES.md, "Configuration resolution"). Three layers, first wins:
 * <ol>
 *   <li><b>the site</b>: {@code <site>/<pack>} (and {@code <site>/<domain>/<pack>}), {@code drishti.packs.site-dir}, default
 *   {@code config/packs}. A site pack of a shipped pack's name replaces it only when its {@code pack.yaml} says {@code replaces: <pack>};
 *   otherwise the clash is refused (a problem naming both) and the shipped pack is read;</li>
 *   <li><b>the active modules</b>: each active module's manifest {@code packs:} list ({@code folder} + {@code list}), in its development
 *   folder ({@code modules/<dir>/module.yaml}) or its installed {@code .dmod}
 *   ({@code <store>/installed/<id>/<version>}, named by {@code <store>/installed/active.yaml}, which wins over the development copy);</li>
 *   <li><b>the kernel</b>: {@code <site>/core/<pack>} (core, gleif, compute-demo).</li>
 * </ol>
 * The same name twice in one layer is an error naming both places, and neither is read. A module that is not active brings no pack (its
 * packs are listed in {@link Result#off()}). Pure: the files are only read, never written; YAML comes through the caller's reader (this
 * module has no dependencies). The Python twin is {@code drishti-console/core/resolution.py}; both pass the shared fixture
 * {@code kernel/drishti-api/src/test/resources/pack-resolution/cases.yaml}. Every reader of packs asks this, never a folder.
 */
public final class PackResolver {

    /** The layer a pack came from. */
    public enum Layer {
        /** The site's own packs folder ({@code drishti.packs.site-dir}): first, and a shipped pack's name only with {@code replaces:}. */
        SITE,
        /** An active module's packs ({@code packs:} in its manifest), from its development folder or its installed {@code .dmod}. */
        MODULE,
        /** The kernel's own packs under {@code <site>/core/} (core, gleif, compute-demo): last, read whatever modules are on. */
        KERNEL
    }

    /**
     * One pack, found.
     *
     * @param name   the pack's name
     * @param dir    its folder (absolute)
     * @param layer  the layer it came from
     * @param module the module it belongs to (null for the site and the kernel)
     */
    public record Located(String name, Path dir, Layer layer, String module) {

        /** In words for Admin: {@code site}, {@code module:trading}, {@code kernel}. */
        public String origin() {
            return layer == Layer.MODULE ? "module:" + module : layer.name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A module found on disk, with its packs.
     *
     * @param id     the manifest's id (lower case)
     * @param home   the folder of its {@code module.yaml}
     * @param folder its packs folder (null when it declares none)
     * @param packs  the packs its manifest lists, in order
     * @param source     in words: {@code modules/trading}, {@code the module store's genomics 1.2.0}
     * @param connectors its connector templates' folder (null: none)
     * @param dataload   its inbox rules file (null: none)
     * @param requires   the modules it requires (not the optional ones)
     */
    public record Module(String id, Path home, Path folder, List<String> packs, String source, Path connectors, Path dataload,
            List<String> requires) {}

    /**
     * Where the layers are.
     *
     * @param site    the site's packs folder ({@code config/packs})
     * @param kernel  the kernel's packs folder ({@code config/packs/core})
     * @param modules   the development tree of the modules ({@code modules}; null: none)
     * @param store     the module store ({@code <data>/modules}; null: none)
     * @param installed the packs an administrator deployed, read before the site's folder (null: none)
     */
    public record Layout(Path site, Path kernel, Path modules, Path store, Path installed, List<Path> extra) {

        public Layout {
            extra = extra == null ? List.of() : List.copyOf(extra);
        }

        /** As above, with no extra folders of packs. */
        public Layout(Path site, Path kernel, Path modules, Path store, Path installed) {
            this(site, kernel, modules, store, installed, List.of());
        }

        /** The layout of a site folder, its kernel folder {@code <site>/core}, no deployed packs. */
        public static Layout of(Path site, Path modules, Path store) {
            Path s = site.toAbsolutePath().normalize();
            return new Layout(s, s.resolve("core"), modules == null ? null : modules.toAbsolutePath().normalize(),
                    store == null ? null : store.toAbsolutePath().normalize(), null);
        }
    }

    /**
     * The answer.
     *
     * @param packs    every pack read, by name in name order
     * @param off      a pack of a module that is not active, by name -> that module
     * @param problems what is wrong, each naming the places
     * @param modules  every module found, by id
     */
    public record Result(Map<String, Located> packs, Map<String, String> off, List<String> problems, Map<String, Module> modules) {

        /** A pack's folder, or null when no layer has it. */
        public Path dir(String name) {
            Located l = packs.get(name);
            return l == null ? null : l.dir();
        }

        /** The names of the packs read, in name order. */
        public List<String> names() {
            return List.copyOf(packs.keySet());
        }
    }

    private PackResolver() {
    }

    /**
     * Resolves every pack.
     *
     * @param layout where the layers are
     * @param active whether a module (by id) is active: the one active-modules set (distribution + Admin switches)
     * @param yaml   reads a YAML file into a map (an empty map for an empty file); throws on a broken one
     */
    public static Result resolve(Layout layout, Predicate<String> active, Function<Path, Map<String, Object>> yaml) {
        List<String> problems = new ArrayList<>();
        Map<String, Module> modules = modules(layout, yaml, problems);
        Map<String, Located> site = new TreeMap<>();
        Map<String, Located> mods = new TreeMap<>();
        Map<String, Located> kernel = new TreeMap<>();
        Map<String, String> off = new TreeMap<>();
        // the site: its packs and its domain folders' (the kernel's folder is not a domain folder of the site)
        Map<String, List<Path>> siteFound = new TreeMap<>();
        java.util.Set<String> deployed = new java.util.TreeSet<>();
        for (Path p : children(layout.installed())) {               // a deployed pack is the site's, and wins the site folder's copy
            deployed.add(p.getFileName().toString());
            siteFound.computeIfAbsent(p.getFileName().toString(), k -> new ArrayList<>()).add(p);
        }
        for (Path root : siteRoots(layout)) {
            for (Path p : children(root)) {
                if (deployed.contains(p.getFileName().toString())) {
                    continue;
                }
                siteFound.computeIfAbsent(p.getFileName().toString(), k -> new ArrayList<>()).add(p);
            }
        }
        for (Path root : layout.extra()) {                              // a test's or a tool's own folder: only names the site does not hold
            for (Path p : children(root)) {
                siteFound.putIfAbsent(p.getFileName().toString(), new ArrayList<>(List.of(p)));
            }
        }
        layer(siteFound, "the site", n -> null, Layer.SITE, site, problems, yaml);
        Map<String, List<Path>> kernelFound = new TreeMap<>();
        for (Path p : children(layout.kernel())) {
            kernelFound.computeIfAbsent(p.getFileName().toString(), k -> new ArrayList<>()).add(p);
        }
        layer(kernelFound, "the kernel", n -> null, Layer.KERNEL, kernel, problems, yaml);
        Map<String, List<Path>> modFound = new TreeMap<>();
        Map<Path, String> owner = new LinkedHashMap<>();
        for (Module m : modules.values()) {
            if (m.folder() == null) {
                continue;
            }
            for (String name : m.packs()) {
                Path dir = m.folder().resolve(name).normalize();
                if (!active.test(m.id())) {
                    off.putIfAbsent(name, m.id());
                    continue;
                }
                if (!dir.startsWith(m.folder()) || !Files.isRegularFile(dir.resolve("pack.yaml"))) {
                    problems.add("the module " + m.id() + " lists the pack '" + name + "', which is not at " + dir.resolve("pack.yaml"));
                    continue;
                }
                modFound.computeIfAbsent(name, k -> new ArrayList<>()).add(dir);
                owner.put(dir, m.id());
            }
        }
        layer(modFound, "the modules", owner::get, Layer.MODULE, mods, problems, yaml);
        // first wins: the site, the modules, the kernel; an earlier layer takes a later one's name only by saying replaces:
        Map<String, Located> out = new TreeMap<>(kernel);
        for (Located m : mods.values()) {
            Located k = out.get(m.name());
            if (k == null || replaces(m, yaml)) {
                out.put(m.name(), m);
            } else {
                problems.add(clash("the module " + m.module() + "'s pack '" + m.name() + "'", m, k));
            }
        }
        for (Located s : site.values()) {
            Located shipped = out.get(s.name());
            if (shipped == null || replaces(s, yaml)) {
                out.put(s.name(), s);
            } else {
                problems.add(clash("the site's pack '" + s.name() + "'", s, shipped));
            }
        }
        out.keySet().forEach(off::remove);
        return new Result(new LinkedHashMap<>(out), new LinkedHashMap<>(off), List.copyOf(problems), modules);
    }

    /**
     * Every module on disk, by id: the development tree's ({@code <modules>/<dir>/module.yaml}: every module's one layout),
     * then the module store's active installs, which win an id.
     */
    public static Map<String, Module> modules(Layout layout, Function<Path, Map<String, Object>> yaml, List<String> problems) {
        Map<String, Module> out = new TreeMap<>();
        if (layout.modules() != null && Files.isDirectory(layout.modules())) {
            for (Path dir : dirs(layout.modules())) {
                if (Files.isRegularFile(dir.resolve("module.yaml"))) {        // one layout: the module's files at its folder's top
                    module(dir, layout.modules().relativize(dir).toString().replace('\\', '/'), yaml, problems, out);
                }
            }
        }
        Path installed = layout.store() == null ? null : layout.store().resolve("installed");
        if (installed != null && Files.isRegularFile(installed.resolve("active.yaml"))) {
            try {
                if (yaml.apply(installed.resolve("active.yaml")).get("modules") instanceof Map<?, ?> active) {
                    for (Map.Entry<?, ?> e : active.entrySet()) {
                        Path home = installed.resolve(String.valueOf(e.getKey())).resolve(String.valueOf(e.getValue())).normalize();
                        if (home.startsWith(installed) && Files.isRegularFile(home.resolve("module.yaml"))) {
                            module(home, "the module store's " + e.getKey() + " " + e.getValue(), yaml, problems, out);
                        }
                    }
                }
            } catch (RuntimeException e) {
                problems.add(installed.resolve("active.yaml") + " cannot be read: " + e.getMessage());
            }
        }
        return out;
    }

    private static void module(Path home, String source, Function<Path, Map<String, Object>> yaml, List<String> problems, Map<String, Module> out) {
        Map<String, Object> m;
        try {
            m = yaml.apply(home.resolve("module.yaml"));
        } catch (RuntimeException e) {
            problems.add(home.resolve("module.yaml") + " cannot be read: " + e.getMessage());
            return;
        }
        String id = String.valueOf(m.getOrDefault("id", home.getFileName())).strip().toLowerCase(Locale.ROOT);
        Object packs = m.get("packs");
        String folderName = "packs";
        List<String> names = List.of();
        if (packs instanceof Map<?, ?> pm) {
            folderName = pm.get("folder") == null ? "packs" : String.valueOf(pm.get("folder"));
            names = pm.get("list") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        } else if (packs instanceof List<?> l) {
            names = l.stream().map(String::valueOf).toList();
        }
        Path folder = null;
        if (!names.isEmpty()) {
            folder = home.resolve(folderName).normalize();
            if (!folder.startsWith(home)) {
                problems.add("the module " + id + ": its packs folder " + folderName + " is outside the module's folder " + home + "; none of its packs is read");
                folder = null;
                names = List.of();
            }
        }
        Path connectors = null;
        if (m.get("connectors") instanceof Map<?, ?> cm && cm.get("folder") != null) {
            connectors = home.resolve(String.valueOf(cm.get("folder"))).normalize();
        } else if (Files.isDirectory(home.resolve("connectors"))) {
            connectors = home.resolve("connectors");
        }
        if (connectors != null && !connectors.startsWith(home)) {
            problems.add("the module " + id + ": its connectors folder is outside the module's folder " + home + "; none of its templates is read");
            connectors = null;
        }
        Path dataload = m.get("dataload") == null ? null : home.resolve(String.valueOf(m.get("dataload"))).normalize();
        List<String> requires = new ArrayList<>();
        if (m.get("requires") instanceof Map<?, ?> rq) {
            rq.forEach((k, v) -> {
                if (!"optional".equals(String.valueOf(k))) {
                    requires.add(String.valueOf(k).strip().toLowerCase(Locale.ROOT));
                }
            });
        }
        out.put(id, new Module(id, home, folder, List.copyOf(names), source, connectors,
                dataload == null || !dataload.startsWith(home) ? null : dataload, List.copyOf(requires)));
    }

    private static void layer(Map<String, List<Path>> found, String where, Function<Path, String> module, Layer layer, Map<String, Located> into,
            List<String> problems, Function<Path, Map<String, Object>> yaml) {
        found.forEach((name, at) -> {
            if (at.size() > 1) {
                problems.add("pack '" + name + "' is in two places of " + where + ": " + describe(at.get(0), module) + " and " + describe(at.get(1), module)
                        + "; neither is read (keep one, rename or remove the other)");
                return;
            }
            Path dir = at.get(0);
            String declared;
            try {
                declared = String.valueOf(yaml.apply(dir.resolve("pack.yaml")).get("pack"));
            } catch (RuntimeException e) {
                declared = name;                                  // a broken manifest: the pack loader says what is wrong with it
            }
            if (!name.equals(declared)) {
                problems.add(dir.resolve("pack.yaml") + " declares pack '" + declared + "', expected '" + name + "'; it is not read");
                return;
            }
            into.put(name, new Located(name, dir, layer, module.apply(dir)));
        });
    }

    private static String describe(Path p, Function<Path, String> module) {
        String m = module.apply(p);
        return p + (m == null ? "" : " (module " + m + ")");
    }

    private static boolean replaces(Located l, Function<Path, Map<String, Object>> yaml) {
        try {
            return l.name().equals(String.valueOf(yaml.apply(l.dir().resolve("pack.yaml")).get("replaces")).strip());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String clash(String what, Located mine, Located shipped) {
        return what + " (" + mine.dir() + ") has the name of " + (shipped.layer() == Layer.KERNEL ? "the kernel's pack" : "the " + shipped.origin().replace(':', ' ')
                + "'s pack") + " at " + shipped.dir() + ": add 'replaces: " + mine.name() + "' to its pack.yaml to replace that one, or rename it; "
                + "the shipped one is read";
    }

    private static List<Path> siteRoots(Layout layout) {
        List<Path> out = new ArrayList<>();
        out.add(layout.site());
        for (Path d : dirs(layout.site())) {
            if (!Files.isRegularFile(d.resolve("pack.yaml")) && !d.equals(layout.kernel())) {
                out.add(d);                                            // a domain folder of the site
            }
        }
        return out;
    }

    private static List<Path> children(Path root) {
        return dirs(root).stream().filter(d -> Files.isRegularFile(d.resolve("pack.yaml"))).toList();
    }

    private static List<Path> dirs(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(Files::isDirectory).map(p -> p.toAbsolutePath().normalize()).sorted().toList();
        } catch (IOException e) {
            return List.of();                                          // an unreadable folder has nothing to offer
        }
    }
}
