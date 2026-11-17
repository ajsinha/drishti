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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 *       {@code .dmod} owns is deleted; a folder that cannot be deleted is noted, never fatal.</li>
 *   <li>The first-party list is the file {@code <modulesHome>/first-party.yaml}, written by {@code drishti.py build modules}; without it
 *       every module is third-party.</li>
 *   <li>The development switch ({@code devFolders} non-empty): the candidates are those source folders only, each marked
 *       <i>development (unverified)</i>; no {@code .dmod} is read and the cache is not swept.</li>
 * </ol>
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

    /** The extension of a module package. */
    public static final String EXTENSION = ".dmod";

    /** How many hex digits of the package's SHA-256 name its cache folder. */
    public static final int HASH_DIGITS = 16;

    /** The folders of the removed deployment store: leftovers, never read. */
    public static final List<String> LEFTOVER_FOLDERS = List.of("installed", "store", "shipped", "staging", "tombstones");

    /** The words a leftover carries. */
    public static final String SAFE_TO_DELETE = "not read: safe to delete";

    /** Where a module came from. */
    public enum Origin {
        /** A {@code .dmod} whose id and hash list are in {@code first-party.yaml}. */
        FIRST_PARTY("first-party"),
        /** Any other {@code .dmod}. */
        THIRD_PARTY("third-party"),
        /** A source folder read under the development switch: no package, no hash, not verified. */
        DEVELOPMENT("development (unverified)");

        private final String word;

        Origin(String word) {
            this.word = word;
        }

        /** The word Health and {@code module list} show. */
        public String word() {
            return word;
        }
    }

    /**
     * One module the boot accepted.
     *
     * @param id       the module's id
     * @param version  its version
     * @param manifest its {@code module.yaml}
     * @param dir      the folder every reader uses: the cache folder, or the source folder under the development switch
     * @param file     the {@code .dmod} it came from; null under the development switch
     * @param sha256   the SHA-256 of that file, lower-case hex; null under the development switch
     * @param origin   first-party, third-party or development
     * @param why      the words behind the origin (Health)
     */
    public record Module(String id, String version, DmodManifest manifest, Path dir, Path file, String sha256, Origin origin, String why) {

        /** The first {@link ModuleBoot#HASH_DIGITS} hex digits of the package's hash (the cache folder's suffix), or null. */
        public String hash16() {
            return sha256 == null ? null : sha256.substring(0, HASH_DIGITS);
        }

        /** The candidate the loader and the verifier take. */
        public ModuleCandidate candidate() {
            return new ModuleCandidate(manifest, dir, file);
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

    /** Keeps the result for this process; the launcher calls it once, right after {@link #boot}. */
    public static Result remember(Result r) {
        CURRENT.set(r);
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
                        o.firstParty() ? Origin.FIRST_PARTY : Origin.THIRD_PARTY, o.why()));
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

    /** The {@code .dmod} files of the modules folder, sorted; folders and other files are not read. */
    private static List<Path> packages(Path modulesHome) {
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
                byId.computeIfAbsent(m.id(), k -> new ArrayList<>()).add(new Module(m.id(), m.version(), m, folder, null, null,
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

    /** The folders of the removed deployment store, under the data folder's {@code modules} and the modules folder itself. */
    private static Map<Path, String> leftovers(Path modulesHome, Path cacheRoot) {
        Map<Path, String> out = new LinkedHashMap<>();
        List<Path> roots = new ArrayList<>();
        Path data = cacheRoot == null || cacheRoot.toAbsolutePath().getParent() == null ? null
                : cacheRoot.toAbsolutePath().getParent().getParent();
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
        if (data != null && Files.isDirectory(data.resolve("overrides").resolve("installed"))) {
            out.putIfAbsent(data.resolve("overrides").resolve("installed").toAbsolutePath().normalize(), SAFE_TO_DELETE);
        }
        return out;
    }
}
