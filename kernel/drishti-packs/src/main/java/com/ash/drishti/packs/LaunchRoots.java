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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

/**
 * The server's launch-time roots, each an absolute path from an explicit setting or from the one anchor, {@code drishti.home}
 * ({@code DRISHTI_HOME}: the install folder, which drishti.py start server, the image and the test launchers pass explicitly), never from the
 * folder the process happens to start in (docs/admin/CONFIGURATION.md, "Launch roots").
 *
 * <p>Right after the configuration files are read, every root is resolved: its setting when given (a relative one is taken under
 * {@code drishti.home}, never the process folder), else its place under the anchor or under another root (packs under config, the
 * inbox under data). The absolute values are put ahead of every other source, so each reader of the server (the pack resolver, the
 * lakes, the inbox, the access registry) sees the same absolute folder. One block, {@code resolved paths:}, names each with where it
 * came from. A required root that is not there, or that nothing names, stops the start with one message naming the setting and the
 * folders looked at, before any pack is read.
 */
public final class LaunchRoots implements EnvironmentPostProcessor, Ordered {

    /** Right after the configuration files (a site's application.yaml may name a root), before every other reader. */
    public static final int ORDER = ConfigDataEnvironmentPostProcessor.ORDER + 1;
    /** The property source of the resolved values, first of all. */
    public static final String SOURCE = "drishti-launch-roots";
    /** The anchor: the install folder. */
    public static final String HOME = "drishti.home";

    /** One root: its name in the log, its settings (the first is the one written back), where it sits when not set, and whether it must exist. */
    record Root(String name, List<String> keys, String parent, String child, boolean required) {
    }

    /** In order: a root's default may sit under one resolved before it. */
    static final List<Root> ROOTS = List.of(
            new Root("data", List.of("drishti.data.dir"), HOME, "data", false),
            new Root("config", List.of("drishti.config.dir"), HOME, "config", false),
            // the site folder: where every site file is read and every Admin change is written; none named: the config folder (the developer layout)
            new Root("site", List.of(com.ash.drishti.api.Roots.SITE), "config", "", false),
            new Root("packs", List.of("drishti.packs.site-dir", "drishti.packs.dir"), "config", "packs", false),
            new Root("kernel packs", List.of("drishti.kernel.packs-dir"), "packs", "core", false),
            new Root("modules", List.of(com.ash.drishti.module.runtime.ModuleBoot.HOME_SETTING), HOME, "modules", true),
            new Root("module switches", List.of("drishti.modules.dir"), "site", "modules", false),
            new Root("access", List.of("drishti.access.dir"), "site", "access", false),
            new Root("lakes", List.of("lake.lakes.dir"), "site", "lakes", false),
            new Root("connectors", List.of("drishti.sources.connectors-dir"), "site", "connectors", false),
            new Root("inbox", List.of("drishti.dataload.dir"), "data", "dataload", false));
    /** The keys the settings file set (server main, before Spring): their origin is the file. */
    public static final String FROM_FILE = "drishti.settings.file.keys";
    /** The modules folder {@code none}: the server has no modules (a kernel-only server; the unit tests). */
    public static final String NONE = com.ash.drishti.api.resolution.ModuleHomes.NONE;
    /** The modules folder's setting: the module boot's. */
    static final String MODULES = com.ash.drishti.module.runtime.ModuleBoot.HOME_SETTING;
    /** The name of the shipped defaults (the server's own application.yaml): a value from there was not named by anyone. */
    static final String DEFAULTS = "class path resource [application.yaml]";
    /** Folders of packs beside the site's (a test's, a tool's): each must exist. */
    static final String EXTRA = "drishti.packs.extra-dirs";
    /** The connectors folder's environment variable, read as is by the older readers. */
    static final String CONNECTORS_ENV = "DRISHTI_CONNECTORS_DIR";

    /** The development switch: modules are loaded unpacked and unverified and reloaded on change (a developer's own server). */
    public static final String DEV = "drishti.modules.dev";

    /** Whether the development switch is asked for ({@code drishti.modules.dev=true}); {@link #devRefusal} says whether it is allowed. */
    public static boolean devAsked(org.springframework.core.env.Environment env) {
        return env.getProperty(DEV, Boolean.class, false);
    }

    /**
     * Why the development switch is refused, or null when it is allowed (or not asked for): never in a cluster, never under the
     * {@code prod} or {@code production} profile (production loads only verified {@code .dmod} packages).
     */
    public static String devRefusal(org.springframework.core.env.Environment env, boolean inCluster) {
        if (!devAsked(env)) {
            return null;
        }
        return inCluster ? "this server is in a cluster"
                : env.acceptsProfiles(org.springframework.core.env.Profiles.of("prod", "production")) ? "the profile is production" : null;
    }

    /** A relative path setting taken under the install folder: its key, its absolute value, where the relative value came from. */
    public record Anchored(String key, String path, String from) {
    }

    /** What one start resolved, for Admin → Configuration ("Resolved paths"): the roots and the anchored settings. */
    public record Snapshot(List<Resolved> roots, List<Anchored> anchored) {
    }

    private static final Map<ConfigurableEnvironment, Snapshot> SNAPSHOTS = new java.util.WeakHashMap<>();

    /** What the start of the server with this environment resolved; null when this post-processor did not run for it. */
    public static Snapshot snapshot(ConfigurableEnvironment env) {
        synchronized (SNAPSHOTS) {
            return SNAPSHOTS.get(env);
        }
    }

    /** One resolved root: its name, its absolute folder, where it came from, and whether it is there. */
    public record Resolved(String name, Path path, String from, boolean exists) {
    }

    /** The roots, the values written back (setting to absolute path), and the problems that stop the start. */
    public record Result(List<Resolved> roots, Map<String, Object> values, List<String> problems) {

        /** The {@code resolved paths:} block. */
        public String report() {
            StringBuilder b = new StringBuilder("resolved paths:");
            for (Resolved r : roots) {
                b.append("\n  ").append(String.format("%-16s", r.name())).append("= ").append(r.path() == null ? "(none)" : r.path())
                        .append("  (from: ").append(r.from()).append(r.path() != null && !r.exists() ? "; not there" : "").append(')');
            }
            return b.toString();
        }
    }

    private final Log log;

    public LaunchRoots(DeferredLogFactory logs) {
        this.log = logs.getLog(LaunchRoots.class);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        Result r = resolve(k -> {
            try {
                String v = env.getProperty(k);
                return v == null && k.equals("drishti.sources.connectors-dir") ? System.getenv(CONNECTORS_ENV) : v;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }, k -> origin(env, k));
        if (!r.problems().isEmpty()) {
            throw new IllegalStateException("the server cannot start: " + String.join("; ", r.problems()) + "\n" + r.report());
        }
        Map<String, Object> values = new LinkedHashMap<>(r.values());
        Object home = values.get(HOME);
        Path homePath = home == null ? null : Path.of(home.toString());
        modules(env, values, homePath);
        java.util.Set<String> roots = java.util.Set.copyOf(values.keySet());
        env.getPropertySources().addFirst(new MapPropertySource(SOURCE, values));   // first, so the other settings' placeholders see the absolute roots
        List<String> anchoredProblems = new ArrayList<>();
        // the path variables (DRISHTI_*_DIR, _FILE, _ROOT, ...): anchored under their own names, so a placeholder ${DRISHTI_X:...} and
        // Spring's relaxed binding both see the absolute value
        Map<String, Object> envValues = new LinkedHashMap<>();
        PropertySource<?> sysEnv = env.getPropertySources().get(org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        if (sysEnv instanceof EnumerablePropertySource<?> e) {
            List<String> envKeys = java.util.Arrays.stream(e.getPropertyNames()).filter(n -> PATH_ENV.matcher(n).matches()).sorted().toList();
            envValues.putAll(anchorSettings(envKeys, k -> {
                Object v = sysEnv.getProperty(k);
                return v == null ? null : v.toString();
            }, homePath, anchoredProblems));
            if (!envValues.isEmpty()) {
                env.getPropertySources().addAfter(SOURCE, new org.springframework.core.env.SystemEnvironmentPropertySource(
                        SOURCE + "-" + org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, envValues));
            }
        }
        Map<String, String> anchored = anchorSettings(names(env), k -> {
            try {
                return roots.contains(k) ? null : env.getProperty(k);
            } catch (IllegalArgumentException ex) {
                return null;                                          // a placeholder nothing resolves: not a path to anchor
            }
        }, homePath, anchoredProblems);
        if (!anchoredProblems.isEmpty()) {
            throw new IllegalStateException("the server cannot start: " + String.join("; ", anchoredProblems) + "\n" + r.report());
        }
        List<Anchored> shown = new ArrayList<>();
        anchored.forEach((k, v) -> shown.add(new Anchored(k, v, origin(env, k))));
        envValues.forEach((k, v) -> shown.add(new Anchored(k, v.toString(), "(environment variable)")));
        values.putAll(anchored);
        envValues.forEach((k, v) -> anchored.put(k, v.toString()));
        synchronized (SNAPSHOTS) {
            SNAPSHOTS.put(env, new Snapshot(r.roots(), List.copyOf(shown)));
        }
        List<String> published = new ArrayList<>(List.of(HOME, MODULES, "drishti.config.dir", com.ash.drishti.api.Roots.SITE));
        values.keySet().stream().filter(k -> k.startsWith(com.ash.drishti.api.resolution.ModuleHomes.DIR_PROPERTY)
                || k.equals(com.ash.drishti.api.resolution.ModuleHomes.PROPERTY)).forEach(published::add);
        for (String k : published) {   // for code with no Spring environment (a record's default) and the child processes
            Object v = values.get(k);
            if (v != null && System.getProperty(k) == null) {
                System.setProperty(k, v.toString());
            }
        }
        log.info(r.report() + (anchored.isEmpty() ? "" : anchoredReport(anchored)));
        otherHome(homePath, installedJar(System.getProperty("java.class.path"))).ifPresent(log::warn);
    }

    /** The server jar this JVM runs ({@code java -jar <home>/lib/drishti-server-*.jar}: the class path is that one jar), or null. */
    static Path installedJar(String classPath) {
        if (classPath == null || classPath.isBlank() || classPath.contains(java.io.File.pathSeparator)) {
            return null;
        }
        return classPath.endsWith(".jar") ? Path.of(classPath) : null;
    }

    /**
     * A WARNING when the settings name a drishti.home other than the folder this server is installed in ({@code <home>/lib/<jar>}): the
     * data and the modules then come from that other folder (an old settings file from an earlier copy). Empty when they agree or when the
     * server does not run from an installed jar.
     */
    static java.util.Optional<String> otherHome(Path home, Path jar) {
        if (home == null || jar == null) {
            return java.util.Optional.empty();
        }
        Path lib = jar.toAbsolutePath().normalize().getParent();
        if (lib == null || lib.getFileName() == null || !lib.getFileName().toString().equals("lib") || lib.getParent() == null) {
            return java.util.Optional.empty();
        }
        Path installed = lib.getParent();
        if (sameFolder(installed, home)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of("WARNING: the settings name drishti.home=" + home + ", but this server is installed in " + installed
                + ": its data and modules come from " + home + ". If that is not what you want, run python tools/drishti.py setup in "
                + installed + ": it moves the old settings files to a dated folder in drishti-envs and writes fresh ones.");
    }

    private static boolean sameFolder(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (java.io.IOException | RuntimeException e) {
            String x = a.toAbsolutePath().normalize().toString();
            String y = b.toAbsolutePath().normalize().toString();
            return java.io.File.separatorChar == '\\' ? x.equalsIgnoreCase(y) : x.equals(y);
        }
    }

    private static final Map<ConfigurableEnvironment, com.ash.drishti.api.resolution.ModuleOverrides.Result> OVERRIDES = new java.util.WeakHashMap<>();

    /** What the module overrides rule did at this server's start (KERNEL_AND_MODULES.md section 18.10); null when it did not run. */
    public static com.ash.drishti.api.resolution.ModuleOverrides.Result overrides(ConfigurableEnvironment env) {
        synchronized (OVERRIDES) {
            return OVERRIDES.get(env);
        }
    }

    /**
     * What the module boot gave one start (KERNEL_AND_MODULES.md section 4.2.1): the modules (with the site's overrides laid over, each
     * module's folder its view where an override applies), what the overrides rule did, and the words for the log.
     */
    public record Booted(com.ash.drishti.api.resolution.ModuleHomes homes, com.ash.drishti.api.resolution.ModuleOverrides.Result overrides,
            String report) {

        /** The settings that carry the result to every reader and child process: {@code drishti.module.dir.<id>}, and the whole value. */
        public Map<String, String> properties() {
            Map<String, String> out = new LinkedHashMap<>(homes.dirProperties());
            out.put(com.ash.drishti.api.resolution.ModuleHomes.PROPERTY, homes.toProperty());
            return out;
        }
    }

    /**
     * The module boot of one process (the server: {@code sweep}; a command line: no sweep, its own view folder). {@code ModuleBoot}
     * reads, verifies and unpacks the packages ({@code *.dmod}) of the modules folder, or, in source mode, the module source folders of that
     * folder; the site's overrides are laid over each module's folder in a view of its own under {@code viewRoot}.
     * Publishes the result for the process ({@code ModuleHomes.current()}).
     *
     * @param modulesHome the modules folder (null: none)
     * @param data        the data folder (the cache is under it)
     * @param sources     the module source folders of the modules folder instead of any package (source mode, or the development switch)
     * @param dirs        the site's override folders (empty: none)
     * @param viewRoot    where the overrides' views go
     * @param sweep       true only for the server
     */
    public static Booted bootModules(Path modulesHome, Path data, boolean sources, List<Path> dirs, Path viewRoot, boolean sweep) {
        com.ash.drishti.api.resolution.ModuleOverrides.activate(dirs, data);   // the same rule for every module of this process
        if (modulesHome == null || data == null) {
            var none = com.ash.drishti.api.resolution.ModuleHomes.none();
            com.ash.drishti.api.resolution.ModuleHomes.publish(none);
            return new Booted(none, null, "Modules: none (" + MODULES + "=" + NONE + ")");
        }
        var r = com.ash.drishti.module.runtime.ModuleBoot.remember(com.ash.drishti.module.runtime.ModuleBoot.boot(modulesHome,
                com.ash.drishti.module.runtime.ModuleBoot.cacheRoot(data), sweep && !sources, com.ash.drishti.module.runtime.KernelFacts.ofKernel(),
                sources ? com.ash.drishti.api.resolution.ModuleHomes.folders(modulesHome) : List.of()));
        List<com.ash.drishti.api.resolution.ModuleHomes.Home> viewed = new ArrayList<>();
        r.homes().homes().values().forEach(h -> viewed.add(com.ash.drishti.api.resolution.ModuleOverrides.view(h, viewRoot)));
        var homes = com.ash.drishti.api.resolution.ModuleHomes.publish(com.ash.drishti.api.resolution.ModuleHomes.of(viewed));
        var o = com.ash.drishti.api.resolution.ModuleOverrides.viewsReport(modulesHome, viewRoot, homes.homes().keySet());
        return new Booted(homes, o, r.report() + (dirs.isEmpty() ? "" : "\n" + o.report()));
    }

    /**
     * The module boot of this server's start ({@link #bootModules}, with the sweep): its settings are put first with the roots, so every
     * reader with a property lookup and every child process takes the same modules.
     */
    private void modules(ConfigurableEnvironment env, Map<String, Object> values, Path homePath) {
        Object modules = values.get(MODULES);
        Object data = values.get("drishti.data.dir");
        String setting;
        try {
            setting = env.getProperty(com.ash.drishti.api.resolution.ModuleOverrides.SETTING);
        } catch (IllegalArgumentException e) {
            setting = null;
        }
        boolean none = modules == null || NONE.equals(modules.toString()) || data == null;
        boolean dev = devAsked(env);
        String refusal = dev ? devRefusal(env, false) : null;
        if (refusal != null) {
            log.error(DEV + "=true is refused: " + refusal + "; only verified .dmod packages load");
            dev = false;
        }
        boolean sources = dev || com.ash.drishti.module.runtime.ModuleBoot.sourcesAsked(env.getProperty(com.ash.drishti.module.runtime.ModuleBoot.SOURCES_SETTING));
        Booted b = bootModules(none ? null : Path.of(modules.toString()), data == null ? null : Path.of(data.toString()), sources,
                none ? List.of() : com.ash.drishti.api.resolution.ModuleOverrides.dirs(setting, homePath),
                data == null ? null : Path.of(data.toString()).resolve("overrides").resolve("modules"), true);
        synchronized (OVERRIDES) {
            if (b.overrides() != null) {
                OVERRIDES.put(env, b.overrides());
            }
        }
        values.putAll(b.properties());
        log.info(b.report());
    }

    /**
     * A setting that names a folder or a file: a {@code drishti.*} key ending in {@code dir}, {@code dirs}, {@code file}, {@code folder},
     * {@code root}, {@code path} or {@code home}, and Spring's own log file and TLS stores.
     */
    static final java.util.regex.Pattern PATH_KEY = java.util.regex.Pattern.compile(
            "^drishti\\..*[.-](?:dir|dirs|file|folder|root|path|home|url)$|^logging\\.file\\.(?:name|path)$|^server\\.ssl\\.(?:key|trust)-store$"
            + "|^spring\\.datasource\\.url$");
    /** The settings the configuration imports name (application.yaml spring.config.import): each must be absolute when given. */
    static final List<String> IMPORTED = List.of("DRISHTI_ENGINES_DIR", "drishti.config.dir", "DRISHTI_CONFIG_DIR", "drishti.site.dir", "DRISHTI_SITE_DIR", "DRISHTI_PACKS_OVERLAY",
            "drishti.data.dir", "DRISHTI_DATA_DIR");
    /** An environment variable that names a folder or a file. */
    static final java.util.regex.Pattern PATH_ENV = java.util.regex.Pattern.compile("^DRISHTI_[A-Z0-9_]*_(?:DIR|DIRS|FILE|FOLDER|ROOT|PATH|HOME)$");

    /** Every path key the configuration names (every source but the environment variables, which are read through the keys they fill). */
    private static List<String> names(ConfigurableEnvironment env) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (ps instanceof EnumerablePropertySource<?> e && !ps.getName().equals("configurationProperties") && !ps.getName().contains("Environment")
                    && !ps.getName().startsWith(SOURCE)) {
                for (String k : e.getPropertyNames()) {
                    if (PATH_KEY.matcher(k).matches() || PATH_ENV.matcher(k).matches()) {
                        out.add(k);
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * Every other path setting made absolute (docs/admin/CONFIGURATION.md, "Launch roots"): a relative value is taken under the install
     * folder, never the process's folder; a list ({@code ...dirs}, {@code drishti...path}) is anchored entry by entry. A relative value with
     * no install folder named is a problem that stops the start. Returns only the settings it changed.
     *
     * @param keys     the path settings the configuration names
     * @param property a setting's resolved value (null: not set, or already a launch root)
     * @param home     the install folder, absolute, or null
     * @param problems where a relative value with no install folder is reported
     */
    static Map<String, String> anchorSettings(List<String> keys, Function<String, String> property, Path home, List<String> problems) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : keys) {
            if (k.equals(HOME) || k.contains(".routes.")) {      // a route maps a kind (such as exposure-path) to its source: a name, not a path
                continue;
            }
            String v = property.apply(k);
            if (blank(v)) {
                continue;
            }
            boolean list = k.endsWith("dirs") || k.endsWith("DIRS") || k.startsWith("drishti.") && k.endsWith(".path") || k.endsWith("_PATH");
            List<String> fixed = new ArrayList<>();
            boolean changed = false;
            for (String part : list ? v.split(",", -1) : new String[] {v}) {
                String r = com.ash.drishti.api.Roots.anchorValue(k, part, home, problems);    // the one rule (docs/admin/CONFIGURATION.md, "Launch roots")
                fixed.add(r);
                changed |= !r.equals(part);
            }
            if (changed) {
                out.put(k, String.join(",", fixed));
            }
        }
        return out;
    }

    /** The settings {@link #anchorSettings} made absolute, for the {@code resolved paths:} block. */
    static String anchoredReport(Map<String, String> anchored) {
        StringBuilder b = new StringBuilder("\n  relative settings, taken under the install folder:");
        anchored.forEach((k, v) -> b.append("\n    ").append(k).append(" = ").append(v));
        return b.toString();
    }

    /**
     * Resolves every root from the settings ({@code property}: a resolved setting or null) and names where each came from
     * ({@code origin}: the source that set a key). No folder is taken from the process's working folder.
     */
    /** The one placeholder a root's value may hold: the install folder (a committed profile names folders under it). */
    static final String HOME_PLACEHOLDER = "${drishti.home}";

    public static Result resolve(Function<String, String> property, Function<String, String> origin) {
        Map<String, Path> at = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();
        List<Resolved> rows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        String homeText = blank(property.apply(HOME)) ? null : property.apply(HOME).strip();
        Path home = null;
        if (homeText != null) {
            Path h = Path.of(homeText);
            if (!h.isAbsolute()) {
                problems.add(HOME + " (DRISHTI_HOME) is '" + homeText + "', a relative path: give the install folder as an absolute path");
            } else {
                home = h.normalize();
                if (!Files.isDirectory(home)) {
                    problems.add(HOME + " (DRISHTI_HOME) is " + home + ", which is not a folder");
                }
                values.put(HOME, home.toString());
            }
        }
        rows.add(new Resolved("home", home, homeText == null ? "not set" : HOME + " " + origin.apply(HOME), home != null && Files.isDirectory(home)));
        at.put(HOME, home);
        for (Root root : ROOTS) {
            String key = null;
            String given = null;
            for (String k : root.keys()) {
                String v = property.apply(k);
                if (!blank(v)) {
                    if (NONE.equals(v.strip()) && given == null) {   // "none" is the weakest answer: a folder another key names wins
                        key = k;
                        given = NONE;
                        continue;
                    }
                    if (given == null || NONE.equals(given)) {
                        key = k;
                        given = v.strip();
                    }
                    break;
                }
            }
            if (NONE.equals(given) && root.name().equals("modules")) {   // said outright: no shipped module folders (a kernel-only server, a unit test)
                rows.add(new Resolved(root.name(), null, key + " " + origin.apply(key) + ": none", false));
                continue;
            }
            Path path;
            String from;
            if (given != null) {
                if (home != null && given.contains(HOME_PLACEHOLDER)) {      // a profile's drishti.data.dir=${drishti.home}/data/qa/data
                    given = given.replace(HOME_PLACEHOLDER, home.toString());
                }
                Path p = Path.of(given);
                if (p.isAbsolute()) {
                    path = p.normalize();
                } else if (home != null) {
                    path = home.resolve(p).normalize();               // a relative setting is under the install folder, never the process's
                } else {
                    problems.add(root.name() + ": " + key + " is '" + given + "', a relative path, and " + HOME
                            + " (DRISHTI_HOME) is not set: give an absolute path, or set " + HOME);
                    rows.add(new Resolved(root.name(), null, key + " " + origin.apply(key), false));
                    continue;
                }
                from = key + " " + origin.apply(key);
            } else {
                Path parent = at.get(root.parent());
                if (parent == null) {
                    if (root.required()) {
                        problems.add(root.name() + ": nothing names its folder: set " + root.keys().get(0) + " (" + env(root.keys().get(0))
                                + ") or " + HOME + " (DRISHTI_HOME), the install folder drishti.py start server passes");
                    }
                    rows.add(new Resolved(root.name(), null, "not set", false));
                    continue;
                }
                path = parent.resolve(root.child()).normalize();
                from = "default under " + (root.parent().equals(HOME) ? HOME : root.parent());
            }
            boolean exists = Files.isDirectory(path);
            if (root.required() && !exists) {
                problems.add(root.name() + ": " + path + " is not there (" + from + "); set " + root.keys().get(0) + " (" + env(root.keys().get(0))
                        + ") to the folder that holds it" + (given == null ? ", or " + HOME + " to the install folder" : ""));
            }
            if (root.name().equals("data") && (given == null || origin.apply(key).contains(DEFAULTS)) && !Files.isDirectory(path.resolve("identity"))) {
                // a data folder nobody named and that holds no identity database: starting would make a new, empty one (users, audit and
                // approvals would look lost); a new data folder is made only when it is named
                problems.add("data: " + path + " was not named (" + from + ") and holds no identity database: set drishti.data.dir (DRISHTI_DATA_DIR)"
                        + " to the server's data folder; a new one is made only when it is named");
            }
            at.put(root.name(), path);
            values.put(root.keys().get(0), path.toString());          // the canonical setting, which every reader asks first
            if ("data".equals(root.name())) {
                values.put("lake.data.dir", path.toString());             // Drishti Lake's name of the data folder (the lake files' ${lake.data.dir})
            }
            if (key != null) {
                values.put(key, path.toString());                         // and the one that named it (a former name), as the same folder
            }
            rows.add(new Resolved(root.name(), path, from, exists));
        }
        for (String k : IMPORTED) {   // read by spring.config.import before this runs: a relative value there would be the process's folder
            String v = property.apply(k);
            if (!blank(v) && !com.ash.drishti.api.Roots.isAbsolute(v)) {
                problems.add(k + " is '" + v.strip() + "', a relative path: the configuration files it names (the engines' files, the packs overlay)"
                        + " are read before the roots are resolved, so give it as an absolute path");
            }
        }
        String extra = property.apply(EXTRA);
        if (!blank(extra)) {
            List<String> abs = new ArrayList<>();
            for (String part : extra.split(",")) {
                String x = part.strip();
                if (x.isEmpty()) {
                    continue;
                }
                Path p = Path.of(x);
                if (!p.isAbsolute()) {
                    if (home == null) {
                        problems.add("extra packs: " + EXTRA + " names '" + x + "', a relative path, and " + HOME + " is not set");
                        continue;
                    }
                    p = home.resolve(p);
                }
                p = p.normalize();
                boolean exists = Files.isDirectory(p);
                if (!exists) {
                    problems.add("extra packs: " + p + " (" + EXTRA + ") is not there");
                }
                abs.add(p.toString());
                rows.add(new Resolved("extra packs", p, EXTRA + " " + origin.apply(EXTRA), exists));
            }
            values.put(EXTRA, String.join(",", abs));
        }
        return new Result(List.copyOf(rows), values, List.copyOf(problems));
    }

    /** The environment variable of a setting, as Spring reads it. */
    static String env(String key) {
        return key.toUpperCase(java.util.Locale.ROOT).replace('.', '_').replace("-", "");
    }

    /** The source that set a key, in words (an environment variable, a system property, the command line, a configuration file). */
    private static String origin(ConfigurableEnvironment env, String key) {
        String file = com.ash.tools.settings.SettingsFile.sourceOf(key);   // the settings file that set it (one of several)
        if (file != null && System.getProperty(key) != null) {
            return "(" + file + ")";
        }
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (ps.getName().equals("configurationProperties")) {        // Spring's view over every other source: name the real one
                continue;
            }
            if (ps.getProperty(key) != null || ps instanceof EnumerablePropertySource<?> e && ps.getName().contains("Environment")
                    && java.util.Arrays.asList(e.getPropertyNames()).contains(env(key))) {
                return "(" + ps.getName() + ")";
            }
        }
        return key.equals("drishti.sources.connectors-dir") && System.getenv(CONNECTORS_ENV) != null ? "(" + CONNECTORS_ENV + ")" : "";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
