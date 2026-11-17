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
package com.ash.drishti.module.runtime;

import com.ash.drishti.api.resolution.ModuleHomes;
import com.ash.drishti.api.resolution.ModuleHomes.Origin;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Module boot (KERNEL_AND_MODULES.md sections 4.1, 4.2, 18.1): the one class that answers <i>which modules, from which file, unpacked
 * where</i>, before any Spring context exists. Every reader of a module's files asks its {@link Result}; nothing else lists the modules
 * folder or the cache.
 *
 * <ol>
 *   <li>Lists {@code <modulesHome>/*.dmod} (files only; source folders beside them are not read). A file must be named
 *       {@code <id>-<version>.dmod} after its own manifest, else it is refused in words.</li>
 *   <li>Two files of one id: both refused (<i>banking: two packages in &lt;folder&gt; (1.0.0, 1.1.0); remove one and restart</i>).</li>
 *   <li>The SHA-256 of the file addresses its unpacked copy, {@code <cacheRoot>/<id>/<version>-<first 16 hex>/}. Missing: unpacked through
 *       a {@code .part-<pid>} folder and one atomic move (a lost race uses the winner's folder). Present: checked against its
 *       {@code META-INF/DMOD.MF} (V2); a mismatch is deleted and unpacked again.</li>
 *   <li>{@code sweep} (the server only; the loader process and the command-line tools pass false): every cache folder no present
 *       {@code .dmod} owns is deleted, unless another node marked it in use ({@link #markInUse}); a folder that cannot be deleted is
 *       noted, never fatal.</li>
 *   <li>The first-party list is the file {@code <modulesHome>/first-party.yaml}, written by {@code drishti.py build modules}; without it
 *       every module is third-party.</li>
 *   <li>The development switch ({@code devFolders} non-empty): the candidates are those source folders only, each marked
 *       <i>development (unverified)</i>; no {@code .dmod} is read and the cache is not swept.</li>
 * </ol>
 *
 * <p>Each accepted module carries two hashes: the SHA-256 of its {@code .dmod} file names its cache folder only; the SHA-256 of its hash
 * list ({@code META-INF/DMOD.MF}) is its <i>content hash</i>, its identity (the same files give the same hash whatever the zip bytes or
 * file times). {@link Result#homes()} is the plain value of the result ({@link ModuleHomes}, in drishti-api) that the readers below this
 * module take; {@link #remember} publishes it. {@link #source} is the boot of a checkout's module folders (source mode).
 *
 * <p>The kernel's API ranges (V3) are checked here too, so a module built for another kernel is refused before anything of it unpacks.
 * The other checks (allowance, licence, data needs, resources) stay with {@link ModuleVerifier} when the module loads.
 *
 * <p>Folders of the deployment store this release removed ({@code installed}, {@code store}, {@code shipped}, {@code staging},
 * {@code tombstones} under {@code <data>/modules} or the modules folder, and {@code <data>/overrides/installed}) are never read and never
 * deleted: each is named once in {@link Result#leftovers()} as safe to delete.
 */
public final class ModuleBoot {

    /** The file of the first-party list in the modules folder. */
    public static final String FIRST_PARTY_FILE = "first-party.yaml";

    /** The folder of a checkout that holds the module source folders (source mode). */
    public static final String SOURCE_MODULES = ModuleHomes.SOURCE_MODULES;

    /** The setting of the modules folder ({@code <DRISHTI_HOME>/modules}), the folder the boot reads its packages from. */
    public static final String HOME_SETTING = "drishti.modules.home";

    /**
     * {@code drishti.modules.packages} (default true): false makes the process ignore the {@code .dmod} files of the modules folder and take
     * its modules from the folders and the old store, as before i7a. For the kernel's own tests that serve the module sources in a checkout
     * where packages were built (until E1 puts them on fixtures); deleted with the old path in i7a's I8.
     */
    public static final String PACKAGES_SETTING = "drishti.modules.packages";

    /**
     * The modules folder of this process as its settings name it (the one reader of that setting, for the module host and the first-party
     * list), or null when it names none (unset, blank or {@code none}). Not the launch roots' answer: those are resolved, absolute and
     * published; this takes a property function so a caller with a Spring environment or the system properties asks the same way.
     */
    public static Path processHome(java.util.function.Function<String, String> property) {
        String home = property.apply(HOME_SETTING);
        return home == null || home.isBlank() || "none".equals(home.strip()) ? null : Path.of(home.strip()).toAbsolutePath().normalize();
    }

    /** Whether packages in the modules folder are used: {@code drishti.modules.packages} is not {@code false}. */
    public static boolean usePackages(String setting) {
        return !"false".equalsIgnoreCase(setting == null ? "" : setting.strip());
    }

    /** The extension of a module package. */
    public static final String EXTENSION = ".dmod";

    /** How many hex digits of the package's SHA-256 name its cache folder. */
    public static final int HASH_DIGITS = 16;

    /** The folders of the removed deployment store: leftovers, never read. */
    public static final List<String> LEFTOVER_FOLDERS = List.of("installed", "store", "shipped", "staging", "tombstones");

    /** The words a leftover carries. */
    public static final String SAFE_TO_DELETE = "not read: safe to delete";

    /** The infix of an in-use marker beside a cache folder: {@code <version>-<hash16>.in-use-<node>}. */
    public static final String IN_USE = ".in-use-";

    /** An in-use marker younger than this keeps its cache folder from any sweep (another node on the same data folder runs it). */
    public static final Duration IN_USE_FRESH = Duration.ofMinutes(15);

    /** How often a running server refreshes its in-use markers ({@link #markInUse}); well inside {@link #IN_USE_FRESH}. */
    public static final Duration IN_USE_REFRESH = Duration.ofMinutes(5);

    /** The old store's module registry under {@code <data>/modules}: read once for the upgrade note, never written. */
    public static final String OLD_REGISTRY = "registry.json";

    /**
     * One module the boot accepted.
     *
     * @param id       the module's id
     * @param version  its version
     * @param manifest its {@code module.yaml}
     * @param dir      the folder every reader uses: the cache folder, or the source folder under the development switch
     * @param file     the {@code .dmod} it came from; null under the development switch
     * @param sha256   the SHA-256 of that file, lower-case hex: it names the cache folder only; null under the development switch
     * @param contentHash the SHA-256 of the unpacked {@code META-INF/DMOD.MF}, lower-case hex: the module's identity (same files, same
     *                 hash, whatever the zip bytes or file times); null under the development switch
     * @param origin   first-party, third-party or development
     * @param why      the words behind the origin (Health)
     */
    public record Module(String id, String version, DmodManifest manifest, Path dir, Path file, String sha256, String contentHash,
            Origin origin, String why) {

        /** The first {@link ModuleBoot#HASH_DIGITS} hex digits of the package's hash (the cache folder's suffix), or null. */
        public String hash16() {
            return sha256 == null ? null : sha256.substring(0, HASH_DIGITS);
        }

        /** The candidate the loader and the verifier take. */
        public ModuleCandidate candidate() {
            return new ModuleCandidate(manifest, dir, file);
        }

        /** Its home: what the readers below this module see ({@link ModuleHomes}). */
        public ModuleHomes.Home home() {
            return new ModuleHomes.Home(id, version, dir, contentHash, origin);
        }
    }

    /**
     * What the boot found.
     *
     * @param modules    id → the module, sorted by id
     * @param refused    id (or the file name when no id reads) → the words
     * @param leftovers  folders of the removed deployment store, each with {@link #SAFE_TO_DELETE}
     * @param notes      everything else said: cache folders removed or held open, a missing first-party list, the development switch
     * @param firstParty the first-party list read from the modules folder ({@link FirstParty#none()} without one)
     * @param facts      the kernel facts given, with that first-party list: what {@link ModuleVerifier} takes for these modules
     */
    public record Result(Map<String, Module> modules, Map<String, String> refused, Map<Path, String> leftovers, List<String> notes,
            FirstParty firstParty, KernelFacts facts) {

        /** Copies every collection, so a result never changes after the boot. */
        public Result {
            modules = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(modules));
            refused = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(refused));
            leftovers = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(leftovers));
            notes = List.copyOf(notes);
        }

        /** The module of an id, if it was accepted. */
        public Optional<Module> module(String id) {
            return Optional.ofNullable(modules.get(id));
        }

        /** The plain value of this result (id → folder, version, content hash, origin) for the readers below this module. */
        public ModuleHomes homes() {
            return ModuleHomes.of(modules.values().stream().map(Module::home).toList());
        }

        /** The candidates, in id order, for the loader. */
        public List<ModuleCandidate> candidates() {
            return modules.values().stream().map(Module::candidate).toList();
        }

        /** The report the server log and {@code drishti.py setup} print. */
        public String report() {
            StringBuilder b = new StringBuilder("Modules: ").append(modules.isEmpty() ? "none" : String.join(", ", modules.keySet()));
            modules.values().forEach(m -> b.append("\n  ").append(m.id()).append(' ').append(m.version()).append("  ")
                    .append(m.file() == null ? m.dir() : m.file().getFileName() + " " + m.hash16()).append("  ").append(m.origin().word()));
            refused.forEach((id, w) -> b.append("\n  refused ").append(id).append(": ").append(w));
            leftovers.forEach((p, w) -> b.append("\n  leftover ").append(p).append(": ").append(w));
            notes.forEach(n -> b.append("\n  note: ").append(n));
            return b.toString();
        }
    }

    private static final AtomicReference<Result> CURRENT = new AtomicReference<>();

    private ModuleBoot() {
    }

    /** The boot result this process keeps (set once at launch by whoever ran the boot); empty before. */
    public static Optional<Result> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Keeps the result for this process and publishes its plain value ({@link ModuleHomes#publish}); the launcher calls it once, right
     * after {@link #boot}.
     */
    public static Result remember(Result r) {
        CURRENT.set(r);
        ModuleHomes.publish(r.homes());
        return r;
    }

    /**
     * Finds, verifies, unpacks and (when asked) sweeps.
     *
     * @param modulesHome the modules folder ({@code <DRISHTI_HOME>/modules}); absent means no module
     * @param cacheRoot   the cache root ({@code <data>/cache/modules}); its grandparent is the data folder searched for leftovers
     * @param sweep       true only for the server: delete cache folders no present package owns
     * @param facts       this kernel's facts (the API versions for V3)
     * @param devFolders  under the development switch, the module source folders to use instead of any package; empty otherwise
     */
    public static Result boot(Path modulesHome, Path cacheRoot, boolean sweep, KernelFacts facts, List<Path> devFolders) {
        Map<String, Module> modules = new TreeMap<>();
        Map<String, String> refused = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        FirstParty list = firstParty(modulesHome, notes);
        KernelFacts withList = facts.withBuild(facts.kernelBuild(), list);
        Map<Path, String> leftovers = leftovers(modulesHome, cacheRoot);
        notes.addAll(oldRegistryNotes(cacheRoot));
        if (devFolders != null && !devFolders.isEmpty()) {
            development(devFolders, withList, modules, refused);
            notes.add("development switch on: modules come from their source folders; no .dmod is read; the cache is not swept");
            return new Result(modules, refused, leftovers, notes, list, withList);
        }
        Map<String, List<Found>> byId = new TreeMap<>();
        Set<Path> owned = new HashSet<>();
        for (Path file : packages(modulesHome)) {
            String name = file.getFileName().toString();
            try {
                DmodManifest m = ModuleFiles.manifest(file);
                String sha = ModuleFiles.sha256(file);
                Path dir = cacheRoot.resolve(m.id()).resolve(m.version() + "-" + sha.substring(0, HASH_DIGITS));
                owned.add(dir);
                if (!name.equals(Dmod.fileName(m.id(), m.version()))) {
                    refused.put(m.id(), m.label() + ": the file " + name + " must be named " + Dmod.fileName(m.id(), m.version())
                            + " after its module.yaml; rename it and restart");
                    continue;
                }
                byId.computeIfAbsent(m.id(), k -> new ArrayList<>()).add(new Found(m, file, sha, dir));
            } catch (ModuleRefusedException e) {
                refused.put(name, e.getMessage());
            } catch (UncheckedIOException e) {
                refused.put(name, name + ": " + e.getMessage());
            }
        }
        byId.forEach((id, found) -> {
            if (refused.containsKey(id)) {
                return;                                                // a misnamed file of this id refuses the id: one file per id
            }
            if (found.size() > 1) {
                refused.put(id, id + ": two packages in " + modulesHome.toAbsolutePath() + " (" + String.join(", ", found.stream()
                        .map(f -> f.manifest().version()).sorted(VersionRange::compare).toList()) + "); remove one and restart");
                return;
            }
            Found f = found.get(0);
            try {
                String range = rangeRefusal(f.manifest(), withList);
                if (range != null) {
                    throw new ModuleRefusedException("V3", range);
                }
                cache(f, notes);
                FirstParty.Origin o = list.origin(f.manifest(), f.dir());
                modules.put(id, new Module(id, f.manifest().version(), f.manifest(), f.dir(), f.file(), f.sha256(),
                        FirstParty.listHash(f.dir()), o.firstParty() ? Origin.FIRST_PARTY : Origin.THIRD_PARTY, o.why()));
            } catch (ModuleRefusedException e) {
                refused.put(id, e.getMessage());
            } catch (UncheckedIOException e) {
                refused.put(id, f.manifest().label() + ": " + e.getMessage());
            }
        });
        if (sweep) {
            notes.addAll(ModuleFiles.sweep(cacheRoot, owned));
        }
        return new Result(modules, refused, leftovers, notes, list, withList);
    }

    /**
     * Source mode (the i7a decision on {@code --root}): the module folders of a checkout, {@code <checkout>/modules/<id>/} with a
     * {@code module.yaml}, booted through the development path: every module of the checkout, each read where it lies, origin
     * development, no content hash; no {@code .dmod} is read, nothing is unpacked, nothing swept. It is what the command-line tools and the
     * tests mean by "the shipped files" of a checkout; servers under test and the drill boot packages.
     *
     * @param checkout the checkout's root folder
     * @param facts    this kernel's facts (the API versions for V3)
     */
    public static Result source(Path checkout, KernelFacts facts) {
        return boot(null, null, false, facts, sourceFolders(checkout));
    }

    /** The module folders of a checkout ({@code <checkout>/modules/<id>/} holding a {@code module.yaml}), sorted; the one listing of them. */
    public static List<Path> sourceFolders(Path checkout) {
        return ModuleHomes.folders(checkout.resolve(SOURCE_MODULES));
    }

    /**
     * Writes (or refreshes) this node's in-use marker beside the cache folder of every package of the result,
     * {@code <cache>/<id>/<version>-<hash16>.in-use-<node>}: while it is younger than {@link #IN_USE_FRESH} no sweep deletes the folder,
     * so nodes that share one data folder through a rolling upgrade never delete each other's modules. A running server calls it at start
     * and every {@link #IN_USE_REFRESH}; {@link #releaseInUse} at stop. Returns notes; never fails.
     */
    public static List<String> markInUse(Result r, String node) {
        List<String> notes = new ArrayList<>();
        r.modules().values().stream().filter(m -> m.file() != null)
                .forEach(m -> ModuleFiles.touch(inUseMarker(m.dir(), node), "node: " + node + "\npid: "
                        + ProcessHandle.current().pid() + "\n", notes));
        return notes;
    }

    /** Deletes this node's in-use markers of the result (the server's stop). Returns notes; never fails. */
    public static List<String> releaseInUse(Result r, String node) {
        List<String> notes = new ArrayList<>();
        r.modules().values().stream().filter(m -> m.file() != null).forEach(m -> ModuleFiles.delete(inUseMarker(m.dir(), node), notes));
        return notes;
    }

    /** The in-use marker of a cache folder for a node (characters a file name cannot hold become {@code _}). */
    public static Path inUseMarker(Path cacheDir, String node) {
        String safe = node == null || node.isBlank() ? "node" : node.replaceAll("[^A-Za-z0-9._-]", "_");
        return cacheDir.resolveSibling(cacheDir.getFileName() + IN_USE + safe);
    }

    /**
     * The words refusing a manifest built for API ranges this kernel does not satisfy (V3), naming the range and the kernel's number; null
     * when every range the kernel knows is satisfied.
     */
    public static String rangeRefusal(DmodManifest m, KernelFacts facts) {
        for (String r : DmodManifest.RANGES) {
            String have = facts.versions().get(r);
            if (have != null && !VersionRange.parse(m.ranges().get(r)).contains(have)) {
                return m.label() + ": built for " + rangeName(r) + " " + m.ranges().get(r) + "; this "
                        + ("plugin-api".equals(r) || "elements".equals(r) ? "console" : "kernel") + " has " + have;
            }
        }
        return null;
    }

    private static String rangeName(String range) {
        return switch (range) {
            case "kernel" -> "kernel API";
            case "plugin-api" -> "console plugin API";
            case "elements" -> "Elements API";
            case "contracts" -> "contract format";
            default -> range;
        };
    }

    /** A package found in the modules folder, before it is accepted. */
    private record Found(DmodManifest manifest, Path file, String sha256, Path dir) {
    }

    /** Makes sure the cache folder holds exactly the package: unpacks when missing; checks when present and unpacks again on a mismatch. */
    private static void cache(Found f, List<String> notes) {
        String label = f.manifest().label();
        if (Files.isDirectory(f.dir())) {
            try {
                Dmod.verify(f.dir(), f.file(), label);
                return;
            } catch (ModuleRefusedException changed) {
                List<String> held = new ArrayList<>();
                if (!ModuleFiles.delete(f.dir(), held)) {
                    notes.addAll(held);
                    throw new ModuleRefusedException("V2", label + ": its cache folder " + f.dir() + " no longer matches the package ("
                            + changed.getMessage() + ") and cannot be deleted; stop the process that holds it and restart");
                }
                notes.add("cache: " + label + ": " + changed.getMessage() + "; unpacked again from " + f.file().getFileName());
            }
        }
        notes.addAll(ModuleFiles.unpack(f.file(), f.dir()));
        Dmod.verify(f.dir(), f.file(), label);
    }

    /** The cache root under a data folder: {@code <data>/cache/modules}, where the packages are unpacked. */
    public static Path cacheRoot(Path data) {
        return data.resolve("cache/modules");
    }

    /** Whether the modules folder holds at least one package ({@code *.dmod}): what decides that the boot runs on packages. */
    public static boolean hasPackages(Path modulesHome) {
        return !packages(modulesHome).isEmpty();
    }

    /** The {@code .dmod} files of the modules folder, sorted; folders and other files are not read. */
    public static List<Path> packages(Path modulesHome) {
        if (modulesHome == null || !Files.isDirectory(modulesHome)) {
            return List.of();
        }
        return ModuleFiles.list(modulesHome).stream()
                .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(EXTENSION)).toList();
    }

    /** The first-party list of the modules folder, or none with a note. */
    private static FirstParty firstParty(Path modulesHome, List<String> notes) {
        Path file = modulesHome == null ? null : modulesHome.resolve(FIRST_PARTY_FILE);
        if (file == null || !Files.isRegularFile(file)) {
            notes.add("no " + FIRST_PARTY_FILE + " in the modules folder: every module loads as third-party (python tools/drishti.py build "
                    + "modules writes it)");
            return FirstParty.none();
        }
        try {
            return FirstParty.read(file);
        } catch (UncheckedIOException e) {
            notes.add(file + " does not read (" + e.getCause().getMessage() + "): every module loads as third-party");
            return FirstParty.none();
        }
    }

    /** The source folders of the development switch, each read as an exploded module. */
    private static void development(List<Path> folders, KernelFacts facts, Map<String, Module> modules, Map<String, String> refused) {
        Map<String, List<Module>> byId = new TreeMap<>();
        for (Path folder : folders) {
            String where = folder.getFileName() == null ? folder.toString() : folder.getFileName().toString();
            try (InputStream in = Files.newInputStream(folder.resolve(DmodManifest.FILE))) {
                DmodManifest m = DmodManifest.read(in, where);
                String range = rangeRefusal(m, facts);
                if (range != null) {
                    refused.put(m.id(), range);
                    continue;
                }
                byId.computeIfAbsent(m.id(), k -> new ArrayList<>()).add(new Module(m.id(), m.version(), m, folder, null, null, null,
                        Origin.DEVELOPMENT, ModuleVerifier.DEVELOPMENT));
            } catch (ModuleRefusedException e) {
                refused.put(where, e.getMessage());
            } catch (IOException e) {
                refused.put(where, folder + ": " + DmodManifest.FILE + " does not read: " + e.getMessage());
            }
        }
        byId.forEach((id, list) -> {
            if (list.size() > 1) {
                refused.put(id, id + ": two source folders (" + String.join(", ", list.stream().map(m -> m.dir().toString()).toList())
                        + "); remove one and restart");
            } else if (!refused.containsKey(id)) {
                modules.put(id, list.get(0));
            }
        });
    }

    /**
     * The upgrade note of the old store: each module {@code <data>/modules/registry.json} names OFF or REMOVED, told once per boot with
     * the one switch that remains. The file is not otherwise read and never written.
     */
    @SuppressWarnings("unchecked")
    private static List<String> oldRegistryNotes(Path cacheRoot) {
        Path data = dataFolder(cacheRoot);
        Path file = data == null ? null : data.resolve("modules").resolve(OLD_REGISTRY);
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try {
            for (Object o : new ObjectMapper().readValue(file.toFile(), List.class)) {
                if (o instanceof Map<?, ?> e && e.get("id") != null
                        && ("OFF".equals(String.valueOf(e.get("state"))) || "REMOVED".equals(String.valueOf(e.get("state"))))) {
                    String id = String.valueOf(e.get("id"));
                    out.add(id + ": was off in the old store: set enabled: false in config/modules/" + id + ".yaml");
                }
            }
        } catch (IOException | RuntimeException e) {
            out.add(file.toAbsolutePath().normalize() + " (the old store) does not read: " + e.getMessage());
        }
        return out;
    }

    /** The data folder: the cache root's grandparent ({@code <data>/cache/modules}), or null without a cache root. */
    private static Path dataFolder(Path cacheRoot) {
        return cacheRoot == null || cacheRoot.toAbsolutePath().getParent() == null ? null : cacheRoot.toAbsolutePath().getParent().getParent();
    }

    /** The folders of the removed deployment store, under the data folder's {@code modules} and the modules folder itself. */
    private static Map<Path, String> leftovers(Path modulesHome, Path cacheRoot) {
        Map<Path, String> out = new LinkedHashMap<>();
        List<Path> roots = new ArrayList<>();
        Path data = dataFolder(cacheRoot);
        if (data != null) {
            roots.add(data.resolve("modules"));
        }
        if (modulesHome != null) {
            roots.add(modulesHome);
        }
        for (Path root : roots) {
            for (String name : LEFTOVER_FOLDERS) {
                Path p = root.resolve(name).toAbsolutePath().normalize();
                if (Files.isDirectory(p)) {
                    out.putIfAbsent(p, SAFE_TO_DELETE);
                }
            }
        }
        if (data != null && Files.isRegularFile(data.resolve("modules").resolve(OLD_REGISTRY))) {
            out.putIfAbsent(data.resolve("modules").resolve(OLD_REGISTRY).toAbsolutePath().normalize(), SAFE_TO_DELETE);
        }
        if (data != null && Files.isDirectory(data.resolve("overrides").resolve("installed"))) {
            out.putIfAbsent(data.resolve("overrides").resolve("installed").toAbsolutePath().normalize(), SAFE_TO_DELETE);
        }
        return out;
    }
}
