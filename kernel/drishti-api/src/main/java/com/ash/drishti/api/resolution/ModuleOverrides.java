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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Site overrides of module files (docs/architecture/KERNEL_AND_MODULES.md section 18.10): the one rule. A site names one or more override
 * folders ({@value #SETTING}, a comma-separated list, the right-most wins; {@code DRISHTI_OVERRIDES_DIR}; in the settings file
 * {@code drishti.overrides.dir=/abs/a,/abs/b}). A file in an override folder at the same relative path a module's file has under the
 * modules folder ({@code banking/packs/trading/sutras/trade.v1.sutra.yaml}) replaces that file, whatever its kind: a pack's YAML, a Sutra,
 * {@code console/plugin.yaml}, {@code data/needs.yaml}, a mapping, a blotter, {@code docs/help.yaml}. A file the module does not ship is
 * added. The first folder of an override path is the module's <b>id</b> ({@code module.yaml}'s {@code id:}), the one key of the rule, for
 * a module of the modules folder and one installed from its {@code .dmod} alike ({@code capitalmarkets/...}, never its folder
 * {@code capital-markets/...}). Never overridden: a module's {@code module.yaml} (its identity), its connector templates
 * ({@code <module>/connectors/}: a site changes a connector in {@code config/connectors/<name>.yaml}, merged over the template, section
 * 18.5), what only builds it ({@code pom.xml}, {@code target/}, {@code tools/}) and its <b>code</b>: {@link #CODE} ({@code java/},
 * {@code lib/}, {@code python/}, {@code engines/}), any file of a {@link #CODE_TYPES code type} wherever it sits, and a symbolic link (never
 * followed): an override folder changes what a module says, never what it runs. Such a file is refused in words and not used.
 *
 * <p>The rule is applied once, at launch, before any reader. A module the boot took from a package gets a <b>view</b> of its own
 * ({@link #view}): {@code <data>/overrides/modules/<id>/<version>-<hash16>/}, its cache folder copied (only what changed since the last
 * start) with the overrides laid over it, and the boot's result names the view as the module's folder; every reader (the pack resolver,
 * Sutras, the console part, the blotters, the data needs, Help) then sees the overridden file without knowing the rule exists. The
 * module's own folder is never written. The whole view's {@value #LIST} names every overridden file with its source, which Admin → Health
 * shows.
 */
public final class ModuleOverrides {

    /** The setting that names the override folders. */
    public static final String SETTING = "drishti.overrides.dir";
    /** Where the modules home stood before the view replaced it (Admin shows both). */
    public static final String SOURCE_HOME = "drishti.modules.source-home";
    /** The view's list of the overridden files, written last. */
    public static final String LIST = "overrides.yaml";
    /**
     * Whether a name at a module's top is overridden and copied into the view: the {@code .dmod} content of the one layout
     * ({@link ModuleLayout}) but its jars ({@code java/}); what only builds the module and anything else (an old build folder) never is.
     */
    static boolean viewed(String top) {
        return ModuleLayout.content(top) && !top.equals("java");
    }

    /** The top folders of a module that hold code (jars, Python, compute engines): copied into the view as shipped, never overridden. */
    public static final Set<String> CODE = Set.of("java", "lib", "python", "engines");
    /** The file types that are code wherever they sit in a module: never taken from an override folder. */
    public static final List<String> CODE_TYPES = List.of(".jar", ".class", ".py", ".pyc", ".pyd", ".so", ".dll", ".dylib", ".exe", ".sh", ".bat",
            ".cmd", ".ps1");

    /**
     * Why a path inside one module ({@code packs/trading/pack.yaml}, without the module's id) may not be overridden; null when it may. The
     * one check of the modules folder's view and of an installed module's view.
     */
    static String refusalInside(String inner) {
        String[] parts = inner.split("/");
        String top = parts[0];
        if (parts.length == 1 && top.equals("module.yaml")) {
            return "a module's module.yaml is its identity and is never overridden";
        }
        if (CODE.contains(top)) {
            return top + "/ holds the module's code and is never overridden (an override changes what a module says, never what it runs)";
        }
        if (!viewed(top)) {
            return top + (ModuleLayout.content(top) || ModuleLayout.BUILD.contains(top) ? " only builds the module"
                    : " is not part of the module layout") + " and is never overridden";
        }
        if (top.equals("connectors")) {
            return "a module's connector templates are changed in the site's config/connectors/<name>.yaml (merged over the template), not here";
        }
        String name = parts[parts.length - 1].toLowerCase(java.util.Locale.ROOT);
        for (String t : CODE_TYPES) {
            if (name.endsWith(t)) {
                return "a " + t + " file is code and is never overridden (an override changes what a module says, never what it runs)";
            }
        }
        return null;
    }

    /**
     * The modules of a modules folder by id: {@code module.yaml}'s {@code id:} (its folder's name when it has none) to the folder's name
     * ({@code capitalmarkets -> capital-markets}).
     */
    public static Map<String, String> modules(Path modulesHome) {
        Map<String, String> out = new TreeMap<>();
        if (modulesHome == null || !Files.isDirectory(modulesHome)) {
            return out;
        }
        try (Stream<Path> ms = Files.list(modulesHome)) {
            for (Path m : ms.filter(d -> Files.isRegularFile(d.resolve("module.yaml"))).sorted().toList()) {
                out.putIfAbsent(idOf(m), m.getFileName().toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static String idOf(Path module) {
        try (java.io.InputStream in = Files.newInputStream(module.resolve("module.yaml"))) {
            Object m = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).readValue(in, Map.class);
            if (m instanceof Map<?, ?> y && y.get("id") != null && !String.valueOf(y.get("id")).isBlank()) {
                return String.valueOf(y.get("id")).strip();
            }
        } catch (IOException | RuntimeException e) {
            // a module.yaml that does not read: the folder's name, as the resolver reports the file itself
        }
        return module.getFileName().toString();
    }

    /**
     * One overridden (or added) file: its path keyed by the module's id ({@code capitalmarkets/packs/x/pack.yaml}), the override file used,
     * whether it replaced a shipped file, and where it is applied: the file in the view it was copied to.
     */
    public record File(String path, Path source, boolean replaces, Path applied) {
    }

    /**
     * What the rule did at start: the view (null: no override applies, the modules folder is read as it is), the files, the refused ones in
     * words, and the ones waiting for a module of their id to be installed from its {@code .dmod} (no module of that id in the folder).
     */
    public record Result(Path view, Path modulesHome, List<Path> dirs, List<File> files, List<String> refused, List<String> waiting) {

        /** The log block. */
        public String report() {
            StringBuilder b = new StringBuilder("module overrides: ");
            if (view == null) {
                b.append(dirs.isEmpty() ? "none named (" + SETTING + ")" : "no file applies in " + dirs);
            } else {
                b.append(files.size()).append(" file(s) over ").append(modulesHome).append(", read from the view ").append(view);
                files.forEach(f -> b.append("\n  ").append(f.path()).append("  <- ").append(f.source()).append(f.replaces() ? "" : "  (added)"));
            }
            refused.forEach(r -> b.append("\n  refused: ").append(r));
            waiting.forEach(w -> b.append("\n  waiting: ").append(w));
            return b.toString();
        }
    }

    private ModuleOverrides() {
    }

    /** The override folders a setting names: comma-separated, each absolute or under {@code home}; blank entries dropped. */
    public static List<Path> dirs(String setting, Path home) {
        List<Path> out = new ArrayList<>();
        if (setting == null || setting.isBlank()) {
            return out;
        }
        for (String s : setting.split(",")) {
            if (!s.isBlank()) {
                Path p = Path.of(s.strip());
                out.add((p.isAbsolute() || home == null ? p : home.resolve(p)).toAbsolutePath().normalize());
            }
        }
        return out;
    }

    /** The files the override folders give, keyed by the module's id (the right-most folder wins), the refused ones and the waiting ones. */
    public static Result find(Path modulesHome, List<Path> dirs) {
        Map<String, String> ids = modules(modulesHome);
        Map<String, String> idOfFolder = new TreeMap<>();
        ids.forEach((id, folder) -> idOfFolder.put(folder, id));
        Map<String, File> files = new TreeMap<>();
        List<String> refused = new ArrayList<>();
        java.util.Set<String> waiting = new java.util.TreeSet<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                refused.add(dir + " (" + SETTING + ") is not a folder: nothing in it is used");
                continue;
            }
            for (Entry e : entries(dir, refused)) {
                String[] parts = e.rel().split("/", 2);
                if (parts.length < 2) {
                    refused.add(e.file() + ": an override sits under a module's id (<module id>/<path inside the module>), the same relative path as the module's file");
                    continue;
                }
                String why = refusalInside(parts[1]);
                if (why != null) {
                    refused.add(e.file() + ": " + why);
                } else if (ids.containsKey(parts[0])) {
                    Path shipped = modulesHome.resolve(ids.get(parts[0])).resolve(parts[1]);
                    files.put(e.rel(), new File(e.rel(), e.file(), Files.isRegularFile(shipped), null));
                } else if (idOfFolder.containsKey(parts[0])) {
                    refused.add(e.file() + ": '" + parts[0] + "' is the folder of the module '" + idOfFolder.get(parts[0])
                            + "': an override names a module by its id (" + idOfFolder.get(parts[0]) + "/" + parts[1] + ")");
                } else {
                    waiting.add(parts[0] + "/" + parts[1] + " <- " + e.file() + ": no module '" + parts[0] + "' in " + modulesHome
                            + "; applied when a module of that id is installed from its .dmod");
                }
            }
        }
        return new Result(null, modulesHome, List.copyOf(dirs), List.copyOf(files.values()), List.copyOf(refused), List.copyOf(waiting));
    }

    /** A file of an override folder: its path under the folder ({@code /}-separated) and the file. */
    private record Entry(String rel, Path file) {
    }

    /**
     * The regular files of an override folder (or of one module's folder in it), sorted; a symbolic link anywhere (a file or a folder) is
     * refused and never followed.
     */
    private static List<Entry> entries(Path dir, List<String> refused) {
        List<Entry> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir)) {                        // never follows a link: a linked folder is one entry, not walked
            for (Path f : s.sorted().toList()) {
                if (f.equals(dir)) {
                    continue;
                }
                if (Files.isSymbolicLink(f)) {
                    refused.add(f + ": a symbolic link is never followed in an override folder (copy the file itself)");
                } else if (Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    out.add(new Entry(dir.relativize(f).toString().replace('\\', '/'), f));
                }
            }
        } catch (IOException e) {
            refused.add(dir + " cannot be read: " + e.getMessage());
        }
        return out;
    }

    /**
     * Applies the rule: when any file overrides, brings the view {@code <view>} up to date (the modules folder copied, only the files whose
     * size or time changed, the files gone removed, then the overrides laid over) and returns it in the result; else the result's view is
     * null and the view folder, if any, is removed.
     */
    public static Result apply(Path modulesHome, List<Path> dirs, Path view) {
        Result found = find(modulesHome, dirs);
        try {
            if (found.files().isEmpty()) {
                delete(view);
                return found;
            }
            Files.createDirectories(view);
            Files.deleteIfExists(view.resolve(LIST));
            Map<String, String> ids = modules(modulesHome);
            Set<Path> keep = new HashSet<>();
            try (Stream<Path> ms = Files.list(modulesHome)) {
                for (Path m : ms.filter(d -> Files.isRegularFile(d.resolve("module.yaml"))).sorted().toList()) {
                    sync(m, view.resolve(m.getFileName().toString()), keep);
                }
            }
            List<File> applied = new ArrayList<>();
            for (File f : found.files()) {
                String[] parts = f.path().split("/", 2);
                Path t = view.resolve(ids.get(parts[0])).resolve(parts[1]);       // the view mirrors the modules folder: the module's own folder
                Files.createDirectories(t.getParent());
                Files.copy(f.source(), t, StandardCopyOption.REPLACE_EXISTING);
                keep.add(t);
                applied.add(new File(f.path(), f.source(), f.replaces(), t));
            }
            prune(view, keep);
            StringBuilder list = new StringBuilder("# The files the site's override folders replace or add (KERNEL_AND_MODULES.md section 18.10); "
                    + "written by the server at each start.\nmodules-home: '" + modulesHome + "'\nfiles:\n");
            applied.forEach(f -> list.append("- {path: '").append(f.path()).append("', source: '").append(f.source()).append("', replaces: ")
                    .append(f.replaces()).append(", applied: '").append(f.applied()).append("'}\n"));
            Files.writeString(view.resolve(LIST), list.toString(), StandardCharsets.UTF_8);
            return new Result(view, modulesHome, found.dirs(), List.copyOf(applied), found.refused(), found.waiting());
        } catch (IOException e) {
            throw new UncheckedIOException("the module overrides' view " + view + " cannot be made: " + e.getMessage(), e);
        }
    }

    /** The override folders and the data folder of this server's start (LaunchRoots): the rule for each module's view. */
    private static volatile List<Path> activeDirs = List.of();
    private static volatile Path activeData;

    /** Sets the override folders and the data folder at a server's start ({@link #view} reads them); empty folders: no rule. */
    public static void activate(List<Path> dirs, Path data) {
        activeDirs = dirs == null ? List.of() : List.copyOf(dirs);
        activeData = data;
        INSTALLED.clear();
    }

    /** What the rule applied to modules installed from their {@code .dmod}, by id: the same report as the start's, kept current. */
    private static final Map<String, List<File>> INSTALLED = new java.util.concurrent.ConcurrentSkipListMap<>();
    private static final Map<String, List<String>> INSTALLED_REFUSED = new java.util.concurrent.ConcurrentSkipListMap<>();

    /**
     * The start's report of the per-module views ({@link #view}): the override folders, the view root, the files and refusals (kept with
     * the installed modules' in {@link #installedFiles}, {@link #installedRefused}) and the override folders' module ids no module booted
     * under ({@code waiting}).
     */
    public static Result viewsReport(Path modulesHome, Path viewRoot, java.util.Set<String> booted) {
        List<String> refused = new ArrayList<>();
        java.util.Set<String> waiting = new java.util.TreeSet<>();
        for (Path dir : activeDirs) {
            if (!Files.isDirectory(dir)) {
                refused.add(dir + " (" + SETTING + ") is not a folder: nothing in it is used");
                continue;
            }
            for (Entry e : entries(dir, refused)) {
                String[] parts = e.rel().split("/", 2);
                if (parts.length < 2) {
                    refused.add(e.file() + ": an override sits under a module's id (<module id>/<path inside the module>), the same relative path as the module's file");
                } else if (!booted.contains(parts[0])) {
                    waiting.add(parts[0] + "/" + parts[1] + " <- " + e.file() + ": no module '" + parts[0] + "' booted from " + modulesHome);
                }
            }
        }
        boolean any = !installedFiles().isEmpty();
        return new Result(any ? viewRoot : null, modulesHome, activeDirs, List.of(), List.copyOf(refused), List.copyOf(waiting));
    }

    /** The files applied to installed modules' views now (Admin → Health lists them with the start's), by the module's id. */
    public static List<File> installedFiles() {
        return INSTALLED.values().stream().flatMap(List::stream).toList();
    }

    /** The files refused for installed modules' views (a code file, a link...), in words. */
    public static List<String> installedRefused() {
        return INSTALLED_REFUSED.values().stream().flatMap(List::stream).toList();
    }

    /**
     * The rule for a module the boot took from a package: when an override folder holds files under {@code <id>/}, the folder its readers
     * use is {@code <viewRoot>/<id>/<version>-<hash16 | development>/} (a server's {@code <data>/overrides/modules}; a command line's own),
     * its folder copied with the overrides laid over it; else its own folder (and a view left from an earlier start is removed). The
     * module's folder is never written. {@link #activate} names the override folders first.
     */
    public static ModuleHomes.Home view(ModuleHomes.Home home, Path viewRoot) {
        if (activeData == null || viewRoot == null) {
            return home;
        }
        String key = home.version() + "-" + (home.contentHash16() == null ? ModuleHomes.NO_CONTENT_HASH : home.contentHash16());
        Path v = view(home.dir(), home.id(), viewRoot.resolve(home.id()).resolve(key));
        return v.equals(home.dir()) ? home : new ModuleHomes.Home(home.id(), home.version(), v, home.contentHash(), home.origin());
    }

    /** One view of one module's folder, brought up to date under the one lock (file work: a lock, never {@code synchronized}). */
    private static Path view(Path home, String id, Path view) {
        LOCK.lock();
        try {
            return installedLocked(home, id, view);
        } finally {
            LOCK.unlock();
        }
    }

    /** One view is brought up to date at a time (file work: a lock, never {@code synchronized}). */
    private static final java.util.concurrent.locks.ReentrantLock LOCK = new java.util.concurrent.locks.ReentrantLock();

    private static Path installedLocked(Path home, String id, Path view) {
        List<Path> dirs = activeDirs;
        Path data = activeData;
        if (data == null || dirs.isEmpty() || home == null || home.getFileName() == null) {
            return home;
        }
        try {
            Map<String, File> files = new TreeMap<>();
            List<String> refused = new ArrayList<>();
            for (Path dir : dirs) {
                Path mine = dir.resolve(id);
                if (Files.isSymbolicLink(mine)) {
                    refused.add(mine + ": a symbolic link is never followed in an override folder (copy the file itself)");
                    continue;
                }
                if (!Files.isDirectory(mine)) {
                    continue;
                }
                for (Entry e : entries(mine, refused)) {
                    String why = refusalInside(e.rel());
                    if (why != null) {
                        refused.add(e.file() + ": " + why);
                    } else {
                        files.put(e.rel(), new File(id + "/" + e.rel(), e.file(), Files.isRegularFile(home.resolve(e.rel())), view.resolve(e.rel())));
                    }
                }
            }
            INSTALLED_REFUSED.put(id, List.copyOf(refused));
            INSTALLED.put(id, List.copyOf(files.values()));
            if (files.isEmpty()) {
                delete(view);
                for (Path up = view.getParent(); up != null && !up.equals(data.resolve("overrides")) && Files.isDirectory(up); up = up.getParent()) {
                    try (Stream<Path> in = Files.list(up)) {
                        if (in.findAny().isPresent()) {
                            break;
                        }
                    }
                    Files.delete(up);                                  // an empty <id>/ and its parent go with the last view
                }
                return home;
            }
            Set<Path> keep = new HashSet<>();
            Files.createDirectories(view);
            sync(home, view, keep);
            for (Map.Entry<String, File> e : files.entrySet()) {
                Path t = view.resolve(e.getKey());
                Files.createDirectories(t.getParent());
                Files.copy(e.getValue().source(), t, StandardCopyOption.REPLACE_EXISTING);
                keep.add(t);
            }
            prune(view, keep);
            return view;
        } catch (IOException e) {
            throw new UncheckedIOException("the module overrides' view " + view + " cannot be made: " + e.getMessage(), e);
        }
    }

    /** Copies a module's content (only what {@link #viewed} names at its top) into the view where it changed, noting every file the view keeps. */
    private static void sync(Path module, Path to, Set<Path> keep) throws IOException {
        Files.walkFileTree(module, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                Path rel = module.relativize(d);
                if (!rel.toString().isEmpty() && !viewed(rel.getName(0).toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(to.resolve(rel.toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Path rel = module.relativize(f);
                if (rel.getNameCount() == 1 && !viewed(rel.toString())) {
                    return FileVisitResult.CONTINUE;
                }
                Path t = to.resolve(rel.toString());
                if (!Files.isRegularFile(t) || Files.size(t) != a.size() || !Files.getLastModifiedTime(t).equals(a.lastModifiedTime())) {
                    Files.copy(f, t, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
                keep.add(t);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Removes from the view every file it no longer keeps (a file gone from a module, an override removed). */
    private static void prune(Path view, Set<Path> keep) throws IOException {
        try (Stream<Path> s = Files.walk(view)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isRegularFile(p) && !keep.contains(p) && !p.equals(view.resolve(LIST))) {
                    Files.delete(p);
                } else if (Files.isDirectory(p) && !p.equals(view)) {
                    try (Stream<Path> in = Files.list(p)) {
                        if (in.findAny().isEmpty()) {
                            Files.delete(p);
                        }
                    }
                }
            }
        }
    }

    private static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
