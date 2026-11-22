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
package com.ash.drishti.rachana;

import com.ash.drishti.api.Roots;
import com.ash.rachana.lib.Library;
import com.ash.rachana.lib.LibraryLock;
import com.ash.rachana.lib.LibraryRef;
import com.ash.rachana.lib.LibraryResolver;
import com.ash.rachana.lib.LibrarySet;
import com.ash.rachana.parse.SutraBlocks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drishti's Rachana libraries: ONE resolver chain (the libraries in the Rachana jars, then each configured folder, unpacked folders and
 * {@code .rlib} archives alike; the same library name found twice with different contents is refused, never shadowed), the lock file that
 * pins versions, and the listing the workbench palette reads.
 *
 * <p>Precedence with pack fragments: a library fragment is used qualified and with its major ({@code core/record-card@1}) in a Sutra that
 * declares the library ({@code libraries: [core@1]}) or names it in full ({@code core@1/record-card@1}); everything else, the UI kit's
 * plain names and a pack's {@code pack/fragment@N}, goes to Drishti's block library as before. A pack namespace that equals a library
 * name the Sutra declares is answered by the library (the declaration is the author's choice), so a pack must not be named like a
 * library: {@link #clashes} lists them and loading warns.
 */
public final class DrishtiLibraries {

    private DrishtiLibraries() {
    }

    /** The libraries these settings name (not yet loaded: a library is read when a Sutra declares it). */
    public static LibrarySet of(LibraryProperties props) {
        LibraryProperties p = props == null ? LibraryProperties.defaults() : props;
        List<LibraryResolver> chain = new ArrayList<>();
        if (p.bundled()) {
            chain.add(LibraryResolver.classpath(DrishtiLibraries.class.getClassLoader()));
        }
        for (String d : p.dirs()) {
            Path dir = anchored(d);
            chain.add(LibraryResolver.folder(dir));
            chain.add(LibraryResolver.archives(dir));
        }
        LibrarySet set = LibrarySet.of(LibraryResolver.first(chain));
        Path lock = lockFile(p);
        if (lock != null) {
            try {
                set = set.locked(LibraryLock.read(Files.readString(lock)));
            } catch (IOException e) {
                throw new IllegalStateException("drishti.rachana.libraries.lock-file " + lock + " does not read: " + e.getMessage(), e);
            }
        }
        return set;
    }

    /** A folder setting as an absolute path: {@code ${home}}, {@code ${config}} or {@code ${data}} first, else absolute, else under the install folder. */
    static Path anchored(String value) {
        String v = value.strip();
        for (String root : List.of("home", "config", "data")) {
            String tag = "${" + root + "}";
            if (v.equals(tag) || v.startsWith(tag + "/") || v.startsWith(tag + "\\")) {
                String rest = v.substring(tag.length()).replaceAll("^[/\\\\]+", "");
                Path base = switch (root) {
                    case "home" -> Roots.home();
                    case "config" -> Path.of(Roots.config());
                    default -> Path.of(Roots.data());
                };
                return rest.isEmpty() ? base : base.resolve(rest).normalize();
            }
        }
        return Roots.anchored(v);
    }

    private static Path lockFile(LibraryProperties p) {
        if (p.lockFile() != null) {
            Path f = anchored(p.lockFile());
            if (!Files.isRegularFile(f)) {
                throw new IllegalStateException("drishti.rachana.libraries.lock-file " + f + " is not a file");
            }
            return f;
        }
        try {
            Path f = Path.of(Roots.config("rachana.lock"));
            return Files.isRegularFile(f) ? f : null;
        } catch (IllegalStateException noRoots) {
            return null;                                         // no install or config folder named (a test): unpinned
        }
    }

    /**
     * Every library fragment the chain offers, newest library version, every major, for the palette: {@code ref} ({@code core/record-card@1}),
     * {@code library}, {@code name}, {@code version} (the fragment's major), {@code summary} (its {@code doc.summary}), {@code params},
     * {@code doc} and {@code origin} ({@code library:core}). A library that does not load is left out and reported by its use.
     */
    public static List<Map<String, Object>> fragments(LibrarySet set) {
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.TreeSet<>();
        for (LibraryRef r : set.resolver().list()) {
            if (!seen.add(r.name())) {
                continue;
            }
            Library lib;
            try {
                lib = set.load(r.name(), Integer.parseInt(r.version().substring(0, r.version().indexOf('.'))));
            } catch (RuntimeException e) {
                continue;
            }
            for (String f : lib.fragmentNames()) {
                for (int major : lib.majors(f)) {
                    lib.fragment(f, major).ifPresent(b -> out.add(describe(lib, f, major, b)));
                }
            }
        }
        return out;
    }

    private static Map<String, Object> describe(Library lib, String fragment, int major, SutraBlocks.Block b) {
        Map<String, Object> doc = b.doc();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ref", lib.name() + "/" + fragment + "@" + major);
        m.put("library", lib.name());
        m.put("libraryVersion", lib.version());
        m.put("name", lib.name() + "/" + fragment);
        m.put("version", major);
        m.put("fragment", true);
        m.put("summary", doc.getOrDefault("summary", b.description()));
        m.put("params", b.params());
        m.put("doc", doc);
        m.put("origin", "library:" + lib.name());
        m.put("source", "library:" + lib.name() + "@" + lib.version());
        return m;
    }

    /** The pack namespaces (the part before {@code /} of a block's name) that equal the name of an offered library. */
    public static List<String> clashes(LibrarySet set, java.util.Collection<String> blockNames) {
        java.util.Set<String> libs = new java.util.TreeSet<>();
        set.resolver().list().forEach(r -> libs.add(r.name()));
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (String n : blockNames) {
            int slash = n.indexOf('/');
            if (slash > 0 && libs.contains(n.substring(0, slash))) {
                out.add(n.substring(0, slash));
            }
        }
        return List.copyOf(out);
    }
}
