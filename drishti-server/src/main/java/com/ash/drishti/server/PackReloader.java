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
package com.ash.drishti.server;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.command.CommandsProperties;
import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.packs.ConnectorBootstrap;
import com.ash.drishti.packs.ConnectorFiles;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackEnvironmentPostProcessor;
import com.ash.drishti.packs.PackLoader;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.packs.SamplePolicy;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.server.connectors.ConnectorManager;
import com.ash.drishti.server.connectors.DirectoryWatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Keeps the running packs in step with the packs folder, without a restart. It watches the folder (file events with a
 * polling safety net, {@code drishti.packs.watch}: auto | poll | off), and an administrator's Load and Unload (and a
 * deploy or registry change) come through the same {@link #reconcile} path:
 * <ul>
 *   <li>a <b>new pack folder</b> is checked together with the loaded packs, then loaded: kinds, mnemonics, Sutras and
 *   connector templates (which become connector files by the usual rule) take effect at once;</li>
 *   <li>a <b>changed</b> {@code pack.yaml}, {@code about.yaml}, Sutra or sample is checked again and swapped in; a bad edit
 *   keeps the last good version and the problem is shown in Admin &rarr; Packs and on the Health page;</li>
 *   <li>a <b>deleted</b> pack is unloaded: connectors only it used stop, and open views of its kinds say the pack was
 *   removed. A pack another loaded pack extends is never unloaded; that is reported instead.</li>
 * </ul>
 * Bursts of file changes are debounced, one reload runs at a time, and every change is audited. Roles, graph and search
 * settings, formats and About pages are bound when the server starts; when a change touches them the result says a
 * restart is needed for them (the rest has already taken effect).
 */
public final class PackReloader implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PackReloader.class);
    private static final int MAX_FILES = 50_000;

    /** What one reconcile did: pack names loaded, reloaded and unloaded; problems by pack; and settings that need a restart. */
    public record Result(List<String> loaded, List<String> reloaded, List<String> unloaded, Map<String, String> problems, List<String> restartFor) {
        public boolean changed() {
            return !loaded.isEmpty() || !reloaded.isEmpty() || !unloaded.isEmpty();
        }
    }

    private final ConfigurableEnvironment env;
    private final PackRegistry registry;
    private final ConnectorManager connectors;
    private final SutraRegistry sutras;
    private final Mnemonics mnemonics;
    private final PackOverlay overlay;
    private final AuditLog audit;
    private final SamplePolicy samples;
    /** The folders packs are read from, fixed when the server starts (a later change to the environment must not move them). */
    private final String packsDir;
    private final String installedDir;
    private final String watchMode;
    private final long pollMs;
    private final long debounceMs;
    private final ReentrantLock lock = new ReentrantLock();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    /** Pack folders as last seen (name to fingerprint), and the connectors held back because the packs that used them left. */
    private volatile Map<String, String> seen = Map.of();
    private final Set<String> goneConnectors = new LinkedHashSet<>();
    /** New folders that appeared while running and are not loaded (yet): tried again at every change. */
    private final Set<String> candidates = new LinkedHashSet<>();
    private final List<DirectoryWatcher> watchers = new ArrayList<>();
    private volatile String lastRun = "";

    public PackReloader(ConfigurableEnvironment env, PackRegistry registry, ConnectorManager connectors, SutraRegistry sutras, Mnemonics mnemonics,
            PackOverlay overlay, AuditLog audit, SamplePolicy samples) {
        this.env = env;
        this.registry = registry;
        this.connectors = connectors;
        this.sutras = sutras;
        this.mnemonics = mnemonics;
        this.overlay = overlay;
        this.audit = audit;
        this.samples = samples;
        this.packsDir = com.ash.drishti.packs.PackPaths.packsDir(env);
        this.installedDir = com.ash.drishti.packs.PackPaths.installedDir(env);
        this.watchMode = env.getProperty("drishti.packs.watch", "auto").strip().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("auto", "poll", "off").contains(watchMode)) {
            throw new IllegalArgumentException("drishti.packs.watch must be auto, poll or off, not " + watchMode);
        }
        this.pollMs = duration(env.getProperty("drishti.packs.poll", "5s")).toMillis();
        this.debounceMs = duration(env.getProperty("drishti.packs.reload-debounce", "750ms")).toMillis();
        this.seen = fingerprints();
    }

    private static Duration duration(String text) {
        return org.springframework.boot.convert.DurationStyle.detectAndParse(text);
    }

    // -- watching --------------------------------------------------------------------------------------------------

    /** Starts watching the packs folders according to {@code drishti.packs.watch}. */
    public void start() {
        if (!watchers.isEmpty()) {
            return;
        }
        for (Path root : roots()) {
            DirectoryWatcher w = new DirectoryWatcher(root, watchMode, pollMs, this::check, "drishti-packs-watch");
            w.start();
            watchers.add(w);
        }
    }

    /** {@code WATCHING}, {@code POLLING}, {@code OFF} or {@code STOPPED: reason}, for Health. */
    public String watchState() {
        return watchers.isEmpty() ? "OFF" : watchers.get(0).state();
    }

    public String lastRun() {
        return lastRun;
    }

    @Override
    public void close() {
        watchers.forEach(DirectoryWatcher::close);
        watchers.clear();
    }

    private List<Path> roots() {
        List<Path> out = new ArrayList<>();
        out.add(Path.of(packsDir).toAbsolutePath().normalize());
        if (installedDir != null && !installedDir.isBlank()) {
            out.add(Path.of(installedDir).toAbsolutePath().normalize());
        }
        return out;
    }

    private List<Path> dirs() {
        return PackLoader.dirs(packsDir, installedDir);
    }

    /** One look at the folders: acts only when something differs from what was last seen, after the change has settled. */
    public void check() {
        if (!lock.tryLock()) {
            return;                                                  // a reload is running; the next look sees what is left
        }
        try {
            Map<String, String> now = fingerprints();
            if (now.equals(seen)) {
                return;
            }
            for (int i = 0; i < 40; i++) {                           // debounce: wait until the folders stop changing
                Thread.sleep(debounceMs);
                Map<String, String> again = fingerprints();
                if (again.equals(now)) {
                    break;
                }
                now = again;
            }
            Map<String, String> before = seen;
            final Map<String, String> settled = now;
            seen = settled;
            Set<String> changed = new LinkedHashSet<>();
            settled.forEach((n, f) -> {
                if (!f.equals(before.get(n))) {
                    changed.add(n);
                    if (!before.containsKey(n) && !loadedNames().contains(n)) {
                        candidates.add(n);                           // a new folder: load it with the others
                    }
                }
            });
            before.keySet().stream().filter(n -> !settled.containsKey(n)).forEach(changed::add);
            candidates.removeIf(n -> !settled.containsKey(n));
            Result r = reconcile("watcher", "pack folder changed", changed, true);
            if (r.changed() || !r.problems().isEmpty()) {
                LOG.info("packs reloaded: loaded {}, reloaded {}, unloaded {}, problems {}", r.loaded(), r.reloaded(), r.unloaded(), r.problems());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }

    // -- the path every change takes -------------------------------------------------------------------------------

    private Set<String> loadedNames() {
        Set<String> out = new LinkedHashSet<>();
        registry.packs().forEach(p -> out.add(p.name()));
        return out;
    }

    /** The packs that should be loaded: the site's list, the administrator's overlay and new folders, minus hidden samples. */
    private List<String> desired(PackLoader loader, List<Path> dirs) {
        LinkedHashSet<String> names = new LinkedHashSet<>(java.util.Arrays.stream(env.getProperty("drishti.packs.enabled", "finance").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
        names.addAll(overlay.added());
        names.addAll(candidates);
        return samples.select(dirs, List.copyOf(names), loader);
    }

    /**
     * Brings the running packs to what the folders hold now, as far as it is valid: the entry for Admin &rarr; Packs Load
     * and Unload, the pack deploy and the registry as well as the watcher. One at a time; blocks until it can run.
     */
    public Result reconcile(String actor, String why, Set<String> changedFolders) {
        lock.lock();
        try {
            return reconcile(actor, why, changedFolders, true);
        } finally {
            lock.unlock();
        }
    }

    private Result reconcile(String actor, String why, Set<String> changedFolders, boolean persistNew) {
        List<Path> dirs = dirs();
        ConnectorFiles files = connectors.files();
        PackLoader loader = new PackLoader(files);
        Map<String, Pack> current = new LinkedHashMap<>();
        registry.packs().forEach(p -> current.put(p.name(), p));
        Map<String, String> problems = new LinkedHashMap<>();
        List<String> want = desired(loader, dirs);
        Map<String, Pack> read = new LinkedHashMap<>();
        Set<String> deleted = new LinkedHashSet<>();
        for (String n : want) {
            collect(loader, dirs, n, null, read, problems, current, deleted, new java.util.ArrayDeque<>());
        }
        // a pack that is gone from disk but another wanted pack still extends stays, as last loaded
        List<Pack> candidate = order(read);
        Set<String> touched = new LinkedHashSet<>(changedFolders);
        current.forEach((n, p) -> {
            Pack r = read.get(n);
            if (r != null && !r.manifest().equals(p.manifest())) {
                touched.add(n);
            }
        });
        List<Pack> accepted = validated(loader, candidate, current, touched, problems);
        // what changed against what runs
        Map<String, Pack> next = new LinkedHashMap<>();
        accepted.forEach(p -> next.put(p.name(), p));
        List<String> loaded = new ArrayList<>();
        List<String> reloaded = new ArrayList<>();
        List<String> unloaded = new ArrayList<>();
        next.forEach((n, p) -> {
            if (!current.containsKey(n)) {
                loaded.add(n);
            } else if (p != current.get(n) && (touched.contains(n) || !current.get(n).dir().equals(p.dir()))) {
                reloaded.add(n);
            }
        });
        current.keySet().stream().filter(n -> !next.containsKey(n)).forEach(unloaded::add);
        // sample packs that cannot be seen must also not hold on to a stale problem; a problem of a pack no longer wanted is dropped
        Map<String, String> earlier = Map.copyOf(registry.problems());
        registry.problems().clear();
        registry.problems().putAll(problems);
        problems.forEach((n, m) -> {
            if (!m.equals(earlier.get(n))) {
                audit.record(actor, "pack-problem", n, m);
            }
        });
        if (loaded.isEmpty() && reloaded.isEmpty() && unloaded.isEmpty()) {
            lastRun = java.time.Instant.now().toString();
            candidates.removeIf(n -> !problems.containsKey(n));
            return new Result(loaded, reloaded, unloaded, problems, List.of());
        }
        List<String> restartFor = apply(loader, dirs, files, accepted, current, loaded, unloaded);
        if (persistNew) {
            persistLoaded(loaded, unloaded);
        }
        candidates.removeIf(n -> next.containsKey(n) || !problems.containsKey(n));
        loaded.forEach(n -> audit.record(actor, "pack-loaded", n, why));
        reloaded.forEach(n -> audit.record(actor, "pack-reloaded", n, why));
        unloaded.forEach(n -> audit.record(actor, "pack-unloaded", n, why));
        lastRun = java.time.Instant.now().toString();
        return new Result(loaded, reloaded, unloaded, problems, restartFor);
    }

    /** Reads {@code name} and, first, the packs it extends. A pack that cannot be read keeps its last good version. */
    private void collect(PackLoader loader, List<Path> dirs, String name, String neededBy, Map<String, Pack> read, Map<String, String> problems,
            Map<String, Pack> current, Set<String> deleted, java.util.Deque<String> path) {
        if (read.containsKey(name) || path.contains(name)) {
            return;
        }
        path.addLast(name);
        Pack p;
        boolean onDisk = dirs.stream().anyMatch(d -> Files.isRegularFile(d.resolve(name).normalize().resolve("pack.yaml")));
        try {
            if (!onDisk) {
                throw new IllegalStateException("the pack folder is gone");
            }
            p = loader.readOne(dirs, name);
            checkFiles(p);
        } catch (RuntimeException e) {
            Pack last = current.get(name);
            if (!onDisk) {
                deleted.add(name);
                if (neededBy == null) {
                    if (current.containsKey(name) && java.util.Arrays.stream(env.getProperty("drishti.packs.enabled", "finance").split(",")).map(String::trim).anyMatch(name::equals)) {
                        problems.put(name, "its folder was removed and it was unloaded, but drishti.packs.enabled still names it: the server will not start until that is changed");
                    }
                    path.removeLast();
                    return;                                          // unload: nothing wanted keeps it
                }
                problems.put(name, "its folder was removed, but '" + neededBy + "' extends it; kept as last loaded until '" + neededBy + "' is unloaded");
            } else if (last != null) {
                problems.put(name, describe(e) + " (the last good version keeps running)");
            } else {
                problems.put(name, describe(e) + " (not loaded)");
                path.removeLast();
                return;
            }
            if (last == null) {
                path.removeLast();
                return;
            }
            p = last;
        }
        for (String parent : p.parents()) {
            collect(loader, dirs, parent.trim(), name, read, problems, current, deleted, path);
        }
        path.removeLast();
        read.put(name, p);
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    /** The files a pack points at must read: its about, formats and semantics files are YAML. */
    private void checkFiles(Pack p) {
        for (String key : new String[] {"about", "formats", "semantics"}) {
            Object rel = p.manifest().get(key);
            if (rel == null && !"about".equals(key)) {
                rel = "formats".equals(key) ? "config/formats.yaml" : "config/semantics.yaml";
            } else if (rel == null) {
                rel = "config/about.yaml";
            }
            Path f = p.resolve(String.valueOf(rel));
            if (Files.isRegularFile(f)) {
                try {
                    yaml.readTree(f.toFile());
                } catch (IOException e) {
                    throw new IllegalStateException(p.dir().relativize(f) + " is not valid YAML: " + firstLine(e.getMessage()));
                }
            }
        }
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("");
    }

    /** Dependencies first, each pack once (the loader's own order for a set of packs it already read). */
    private static List<Pack> order(Map<String, Pack> read) {
        List<Pack> out = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        for (String n : read.keySet()) {
            visit(n, read, done, out);
        }
        return out;
    }

    private static void visit(String n, Map<String, Pack> read, Set<String> done, List<Pack> out) {
        Pack p = read.get(n);
        if (p == null || !done.add(n)) {
            return;
        }
        for (String parent : p.parents()) {
            visit(parent.trim(), read, done, out);
        }
        out.add(p);
    }

    /**
     * Checks the packs together (what the server does at start). When they do not fit, the packs that changed go back
     * to their last good version (or are left out when they are new), one at a time, so one bad pack does not hold up the rest.
     */
    private List<Pack> validated(PackLoader loader, List<Pack> candidate, Map<String, Pack> current, Set<String> touched, Map<String, String> problems) {
        String error = problem(loader, candidate);
        if (error == null) {
            return candidate;
        }
        List<String> suspects = candidate.stream().map(Pack::name).filter(n -> touched.contains(n) || !current.containsKey(n)).toList();
        for (String n : suspects.reversed()) {                       // the most specific first: a pack before the packs it extends
            List<Pack> without = revert(candidate, current, Set.of(n));
            if (problem(loader, without) == null) {
                problems.put(n, error + (current.containsKey(n) ? " (the last good version keeps running)" : " (not loaded)"));
                return without;
            }
        }
        List<Pack> all = revert(candidate, current, new LinkedHashSet<>(suspects));
        String again = problem(loader, all);
        suspects.forEach(n -> problems.put(n, error + (current.containsKey(n) ? " (the last good version keeps running)" : " (not loaded)")));
        if (again == null) {
            return all;
        }
        problems.put("(all)", "the packs on disk do not fit together: " + again + "; nothing was changed");
        return new ArrayList<>(current.values());
    }

    private static List<Pack> revert(List<Pack> candidate, Map<String, Pack> current, Set<String> names) {
        List<Pack> out = new ArrayList<>();
        Set<String> removedNew = new LinkedHashSet<>();
        for (Pack p : candidate) {
            if (!names.contains(p.name())) {
                out.add(p);
            } else if (current.containsKey(p.name())) {
                out.add(current.get(p.name()));
            } else {
                removedNew.add(p.name());
            }
        }
        // a new pack left out takes with it the packs that extend it and are also new
        boolean again = true;
        while (again) {
            again = false;
            for (java.util.Iterator<Pack> it = out.iterator(); it.hasNext(); ) {
                Pack p = it.next();
                if (!current.containsKey(p.name()) && p.parents().stream().anyMatch(removedNew::contains)) {
                    removedNew.add(p.name());
                    it.remove();
                    again = true;
                }
            }
        }
        return out;
    }

    private static String problem(PackLoader loader, List<Pack> packs) {
        try {
            loader.properties(packs);
            Set<String> have = new LinkedHashSet<>();
            packs.forEach(p -> have.add(p.name()));
            for (Pack p : packs) {
                for (String parent : p.parents()) {
                    if (!have.contains(parent)) {
                        return "'" + p.name() + "' extends '" + parent + "', which is not loaded";
                    }
                }
            }
            return null;
        } catch (RuntimeException e) {
            return describe(e);
        }
    }

    /** Makes the accepted set the running one. Returns the settings that are bound at start and so need a restart. */
    private List<String> apply(PackLoader loader, List<Path> dirs, ConnectorFiles files, List<Pack> packs, Map<String, Pack> current,
            List<String> loaded, List<String> unloaded) {
        Map<String, Object> oldProps = env.getPropertySources().get(PackEnvironmentPostProcessor.SOURCE) instanceof MapPropertySource m
                ? new LinkedHashMap<>(m.getSource()) : Map.of();
        ConnectorBootstrap.Result boot = ConnectorBootstrap.run(packs, files, null,
                Boolean.parseBoolean(env.getProperty("drishti.sources.connectors-generate", "true")));
        boot.generated().forEach(n -> audit.record("system", "connector-generated", n, "written from a pack's connector template"));
        Map<String, Object> props = loader.properties(packs);
        replaceSource(PackEnvironmentPostProcessor.SOURCE, props);
        // mnemonics: recompute every code a pack ever contributed from the environment, so a site setting keeps winning
        Set<String> codes = new LinkedHashSet<>();
        Stream.of(oldProps.keySet(), props.keySet()).flatMap(Set::stream).filter(k -> k.startsWith("drishti.commands.mnemonics.") && k.endsWith(".kind"))
                .forEach(k -> codes.add(k.substring("drishti.commands.mnemonics.".length(), k.length() - ".kind".length())));
        Map<String, CommandsProperties.Mnemonic> table = new TreeMap<>(mnemonics.all());
        for (String code : codes) {
            table.remove(code.toUpperCase(java.util.Locale.ROOT));
            String kind = env.getProperty("drishti.commands.mnemonics." + code + ".kind");
            if (kind != null) {
                table.put(code, new CommandsProperties.Mnemonic(kind, env.getProperty("drishti.commands.mnemonics." + code + ".label", code)));
            }
        }
        mnemonics.replace(table);
        // Sutras
        List<String> sutraDirs = indexed(props, "drishti.rachana.pack-dirs");
        sutras.setPackDirs(sutraDirs);
        // the registry (kinds, python, packs shown everywhere), then the connectors
        registry.replace(packs);
        Set<String> refs = new LinkedHashSet<>();
        packs.forEach(p -> refs.addAll(p.connectorRefs()));
        current.values().stream().filter(p -> unloaded.contains(p.name())).forEach(p -> goneConnectors.addAll(p.connectorRefs()));
        goneConnectors.removeAll(refs);
        Set<String> held = new LinkedHashSet<>(goneConnectors);
        if (samples.hidden()) {
            held.addAll(SamplePolicy.sampleOnlyConnectors(dirs, loader));
            held.removeAll(refs);
        }
        connectors.suppress(held);
        List<String> restart = new ArrayList<>();
        restart(restart, oldProps, props, "drishti.security.roles.", "roles that packs define");
        restart(restart, oldProps, props, "drishti.graph.", "graph reference patterns, badges and impact settings");
        restart(restart, oldProps, props, "drishti.search.", "search columns and pivots");
        restart(restart, oldProps, props, "drishti.rachana.pack-formats-files", "formats");
        restart(restart, oldProps, props, "drishti.inference.pack-semantics-files", "semantic hints");
        restart(restart, oldProps, props, "drishti.about.", "About pages and pack guides");
        restart(restart, oldProps, props, "drishti.sources.routes.", "kind routes");
        restart(restart, oldProps, props, "drishti.sources.plugins.demo.", "sample data directories");
        return restart;
    }

    private static void restart(List<String> out, Map<String, Object> before, Map<String, Object> after, String prefix, String what) {
        Map<String, Object> a = new TreeMap<>();
        Map<String, Object> b = new TreeMap<>();
        before.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                a.put(k, v);
            }
        });
        after.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                b.put(k, v);
            }
        });
        if (!a.equals(b)) {
            out.add(what);
        }
    }

    private static List<String> indexed(Map<String, Object> props, String key) {
        List<String> out = new ArrayList<>();
        for (int i = 0; props.containsKey(key + "[" + i + "]"); i++) {
            out.add(String.valueOf(props.get(key + "[" + i + "]")));
        }
        return out;
    }

    private void replaceSource(String name, Map<String, Object> props) {
        MapPropertySource ps = new MapPropertySource(name, props);
        if (env.getPropertySources().contains(name)) {
            env.getPropertySources().replace(name, ps);
        } else {
            env.getPropertySources().addLast(ps);
        }
    }

    /** New folders and unloaded packs are remembered across restarts the way Admin &rarr; Packs Load and Unload do. */
    private void persistLoaded(List<String> loaded, List<String> unloaded) {
        Set<String> configured = new LinkedHashSet<>(java.util.Arrays.stream(env.getProperty("drishti.packs.enabled", "finance").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
        List<String> added = new ArrayList<>(overlay.added());
        boolean change = false;
        for (String n : loaded) {
            if (!configured.contains(n) && !added.contains(n)) {
                added.add(n);
                change = true;
            }
        }
        List<Path> dirs = dirs();           // only a pack whose folder is gone is forgotten; one hidden as a sample comes back with the mode
        change |= added.removeIf(n -> unloaded.contains(n) && dirs.stream().noneMatch(d -> Files.isRegularFile(d.resolve(n).normalize().resolve("pack.yaml"))));
        if (change) {
            overlay.write(added);
        }
    }

    // -- Admin: Load and Unload ------------------------------------------------------------------------------------

    /**
     * Loads {@code name} (on disk, not loaded) now. Throws if it cannot be used, leaving everything as it was.
     */
    public Result load(String name, String actor) {
        lock.lock();
        try {
            if (loadedNames().contains(name)) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "'" + name + "' is already loaded");
            }
            List<String> before = overlay.added();
            List<String> added = new ArrayList<>(before);
            added.add(name);
            overlay.write(added);
            Result r;
            try {
                r = reconcile(actor, "loaded from Admin -> Packs", Set.of(name), false);
            } catch (RuntimeException e) {
                overlay.write(before);
                throw e;
            }
            if (!r.loaded().contains(name)) {
                overlay.write(before);
                String why = r.problems().getOrDefault(name, r.problems().isEmpty() ? "the pack could not be found"
                        : String.join("; ", r.problems().values()));
                registry.problems().remove(name);
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "cannot load '" + name + "': " + why);
            }
            return r;
        } finally {
            lock.unlock();
        }
    }

    /** Unloads a pack an administrator loaded; refuses while another loaded pack extends it. */
    public Result unload(String name, String actor) {
        lock.lock();
        try {
            List<String> needs = registry.packs().stream().filter(p -> !p.name().equals(name) && p.parents().contains(name)).map(Pack::name).toList();
            if (!needs.isEmpty()) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "cannot unload '" + name + "': " + needs + " extend it; unload those first");
            }
            List<String> before = overlay.added();
            List<String> added = new ArrayList<>(before);
            if (!added.remove(name)) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST,
                        "'" + name + "' was not loaded from Admin -> Packs; it is in the site configuration (drishti.packs.enabled)");
            }
            overlay.write(added);
            candidates.remove(name);
            Result r = reconcile(actor, "unloaded from Admin -> Packs", Set.of(name), false);
            if (!r.unloaded().contains(name)) {
                overlay.write(before);
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "cannot unload '" + name + "': " + r.problems());
            }
            return r;
        } finally {
            lock.unlock();
        }
    }

    /**
     * After a loaded pack's files were replaced (a deploy, a registry upgrade or rollback): re-reads it and swaps it in. If it
     * cannot be used, {@code undo} puts the files back and this throws; the running pack is untouched either way.
     */
    public Result refresh(String name, String actor, String action, Runnable undo) {
        lock.lock();
        try {
            Result r = reconcile(actor, action, Set.of(name), false);
            String bad = r.problems().get(name);
            if (bad != null) {
                undo.run();
                seen = fingerprints();
                reconcile(actor, action + " undone", Set.of(name), false);
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "cannot use '" + name + "': " + bad);
            }
            seen = fingerprints();
            return r;
        } finally {
            lock.unlock();
        }
    }

    // -- fingerprints ----------------------------------------------------------------------------------------------

    /** For each pack folder (one holding pack.yaml): a digest of every file's path, size and modification time. */
    Map<String, String> fingerprints() {
        Map<String, String> out = new TreeMap<>();
        for (Path root : dirs()) {
            for (String n : PackLoader.folders(List.of(root))) {
                out.computeIfAbsent(n, k -> fingerprint(root.resolve(k)));
            }
        }
        return out;
    }

    private static String fingerprint(Path pack) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            int[] count = {0};
            try (Stream<Path> s = Files.walk(pack, 8)) {
                s.filter(p -> !p.toString().contains("/.git/") && !p.toString().contains("/__pycache__/")).sorted().forEach(p -> {
                    if (++count[0] > MAX_FILES) {
                        return;
                    }
                    try {
                        var a = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class);
                        md.update((pack.relativize(p) + "|" + a.size() + "|" + a.lastModifiedTime().toMillis() + "\n").getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        md.update(("?" + p + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                });
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            return "unreadable";
        }
    }
}
