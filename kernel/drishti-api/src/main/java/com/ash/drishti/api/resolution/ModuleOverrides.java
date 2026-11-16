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
 * added. Never overridden: a module's {@code module.yaml} (its identity), its connector templates ({@code <module>/connectors/}: a site
 * changes a connector in {@code config/connectors/<name>.yaml}, merged over the template, section 18.5) and what only builds it
 * ({@code java/}, {@code pom.xml}, {@code target/}, {@code tools/}); such a file is refused in words and not used.
 *
 * <p>The rule is applied once, at launch, before any reader: when at least one file overrides, the server reads its modules from a
 * <b>view</b>, {@code <data>/overrides/modules}, the modules folder copied (only what changed since the last start) with the overrides laid
 * over it, and {@code drishti.modules.home} names the view; every reader (the pack resolver, Sutras, the console part, the blotters, the
 * data needs, Help) then sees the overridden file without knowing the rule exists. The module's own folder is never written. The view's
 * {@value #LIST} names every overridden file with its source, which Admin → Health shows and the console's resolver reads. A module the
 * resolver reads from the module store (installed from its {@code .dmod}) gets the same rule where its folder is picked ({@link #installed}).
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

    /** One overridden (or added) file: its path under the modules folder, the override file used, and whether it replaced a shipped file. */
    public record File(String path, Path source, boolean replaces) {
    }

    /** What the rule did: the view (null: no override applies, the modules folder is read as it is), the files, and the refused ones in words. */
    public record Result(Path view, Path modulesHome, List<Path> dirs, List<File> files, List<String> refused) {

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

    /** The files the override folders give, by path under the modules folder (the right-most folder wins), and the refused ones. */
    public static Result find(Path modulesHome, List<Path> dirs) {
        Map<String, File> files = new TreeMap<>();
        List<String> refused = new ArrayList<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                refused.add(dir + " (" + SETTING + ") is not a folder: nothing in it is used");
                continue;
            }
            try (Stream<Path> s = Files.walk(dir)) {
                for (Path f : s.filter(Files::isRegularFile).sorted().toList()) {
                    String rel = dir.relativize(f).toString().replace('\\', '/');
                    String why = refusal(modulesHome, rel);
                    if (why != null) {
                        refused.add(f + ": " + why);
                    } else {
                        files.put(rel, new File(rel, f, Files.isRegularFile(modulesHome.resolve(rel))));
                    }
                }
            } catch (IOException e) {
                refused.add(dir + " cannot be read: " + e.getMessage());
            }
        }
        return new Result(null, modulesHome, List.copyOf(dirs), List.copyOf(files.values()), List.copyOf(refused));
    }

    /** Why a path under the modules folder may not be overridden; null when it may. */
    static String refusal(Path modulesHome, String rel) {
        String[] parts = rel.split("/");
        if (parts.length < 2) {
            return "an override sits under a module's folder (<module>/<path inside the module>), the same relative path as the module's file";
        }
        if (!Files.isRegularFile(modulesHome.resolve(parts[0]).resolve("module.yaml"))) {
            return "there is no module '" + parts[0] + "' in " + modulesHome + ": the first folder names the module (used only if a module of that id is installed from its .dmod)";
        }
        if (parts.length == 2 && parts[1].equals("module.yaml")) {
            return "a module's module.yaml is its identity and is never overridden";
        }
        if (!viewed(parts[1])) {
            return parts[1] + (ModuleLayout.content(parts[1]) || ModuleLayout.BUILD.contains(parts[1]) ? " only builds the module"
                    : " is not part of the module layout") + " and is never overridden";
        }
        if (parts[1].equals("connectors")) {
            return "a module's connector templates are changed in the site's config/connectors/<name>.yaml (merged over the template), not here";
        }
        return null;
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
            Set<Path> keep = new HashSet<>();
            try (Stream<Path> ms = Files.list(modulesHome)) {
                for (Path m : ms.filter(d -> Files.isRegularFile(d.resolve("module.yaml"))).sorted().toList()) {
                    sync(m, view.resolve(m.getFileName().toString()), keep);
                }
            }
            for (File f : found.files()) {
                Path t = view.resolve(f.path());
                Files.createDirectories(t.getParent());
                Files.copy(f.source(), t, StandardCopyOption.REPLACE_EXISTING);
                keep.add(t);
            }
            prune(view, keep);
            StringBuilder list = new StringBuilder("# The files the site's override folders replace or add (KERNEL_AND_MODULES.md section 18.10); "
                    + "written by the server at each start.\nmodules-home: '" + modulesHome + "'\nfiles:\n");
            found.files().forEach(f -> list.append("- {path: '").append(f.path()).append("', source: '").append(f.source()).append("', replaces: ")
                    .append(f.replaces()).append("}\n"));
            Files.writeString(view.resolve(LIST), list.toString(), StandardCharsets.UTF_8);
            return new Result(view, modulesHome, found.dirs(), found.files(), found.refused());
        } catch (IOException e) {
            throw new UncheckedIOException("the module overrides' view " + view + " cannot be made: " + e.getMessage(), e);
        }
    }

    /** The override folders and the data folder of this server's start (LaunchRoots): the rule for the module store's installs. */
    private static volatile List<Path> activeDirs = List.of();
    private static volatile Path activeData;

    /** Sets the override folders and the data folder at a server's start ({@link #installed} reads them); empty folders: no rule. */
    public static void activate(List<Path> dirs, Path data) {
        activeDirs = dirs == null ? List.of() : List.copyOf(dirs);
        activeData = data;
    }

    /**
     * The same rule for a module installed from its {@code .dmod} ({@code <store>/installed/<id>/<version>}, which the resolver reads before
     * the modules folder's copy): when an override folder holds files under {@code <id>/}, the folder the resolver reads is the view
     * {@code <data>/overrides/installed/<id>/<version>}, the installed folder copied with the overrides laid over it; else the installed
     * folder itself (and a view left from an earlier start is removed). The installed folder is never written.
     */
    public static Path installed(Path home, String id) {
        LOCK.lock();
        try {
            return installedLocked(home, id);
        } finally {
            LOCK.unlock();
        }
    }

    /** One view is brought up to date at a time (file work: a lock, never {@code synchronized}). */
    private static final java.util.concurrent.locks.ReentrantLock LOCK = new java.util.concurrent.locks.ReentrantLock();

    private static Path installedLocked(Path home, String id) {
        List<Path> dirs = activeDirs;
        Path data = activeData;
        if (data == null || dirs.isEmpty() || home == null || home.getFileName() == null) {
            return home;
        }
        Path view = data.resolve("overrides").resolve("installed").resolve(id).resolve(home.getFileName().toString());
        try {
            Map<String, File> files = new TreeMap<>();
            for (Path dir : dirs) {
                Path mine = dir.resolve(id);
                if (!Files.isDirectory(mine)) {
                    continue;
                }
                try (Stream<Path> s = Files.walk(mine)) {
                    for (Path f : s.filter(Files::isRegularFile).sorted().toList()) {
                        String rel = mine.relativize(f).toString().replace('\\', '/');
                        String top = rel.split("/")[0];
                        if (!rel.equals("module.yaml") && viewed(top) && !top.equals("connectors")) {
                            files.put(rel, new File(id + "/" + rel, f, Files.isRegularFile(home.resolve(rel))));
                        }
                    }
                }
            }
            if (files.isEmpty()) {
                delete(view);
                for (Path up = view.getParent(); up != null && !up.equals(data.resolve("overrides")) && Files.isDirectory(up); up = up.getParent()) {
                    try (Stream<Path> in = Files.list(up)) {
                        if (in.findAny().isPresent()) {
                            break;
                        }
                    }
                    Files.delete(up);                                  // an empty <id>/ and installed/ go with the last view
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
