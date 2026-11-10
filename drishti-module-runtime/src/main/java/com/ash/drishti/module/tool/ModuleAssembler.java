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
package com.ash.drishti.module.tool;

import com.ash.drishti.module.runtime.Dmod;
import com.ash.drishti.module.runtime.DmodManifest;
import com.ash.drishti.module.runtime.ModuleRefusedException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Lays a module project's files into the {@code .dmod} layout and packs them with the module runtime's packer (section 8.2): everything
 * under {@code src/dmod/} as it is (module.yaml, console/, data/, packs/, docs/ ...) plus the module's jar under {@code java/} by the name
 * its manifest gives. Nothing is written into the sources: the files are staged under {@code target/dmod-stage/<id>/}.
 */
public final class ModuleAssembler {

    private ModuleAssembler() {
    }

    /** Reads {@code module.yaml} of a module's {@code src/dmod} folder (or of an unpacked module). */
    public static DmodManifest manifest(Path folder) {
        Path file = folder.resolve(DmodManifest.FILE);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("there is no " + DmodManifest.FILE + " in " + folder + ": a module's files start with it");
        }
        try (InputStream in = Files.newInputStream(file)) {
            return DmodManifest.read(in, file.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@code drishti:validate}: the manifest reads, and its id and version are the project's. Returns the problems in plain words (empty:
     * valid).
     */
    public static List<String> validate(Path source, String id, String version) {
        List<String> out = new ArrayList<>();
        DmodManifest m;
        try {
            m = manifest(source);
        } catch (IllegalArgumentException | ModuleRefusedException e) {
            out.add(e.getMessage());
            return out;
        }
        if (id != null && !id.equals(m.id())) {
            out.add("module.yaml says id " + m.id() + " but the project is " + id + ": make them the same");
        }
        if (version != null && !version.equals(m.version())) {
            out.add("module.yaml says version " + m.version() + " but the project is " + version + ": make them the same");
        }
        if (m.java() != null && m.java().jars().isEmpty()) {
            out.add("java.jars must name at least one jar, the project's own first (third-party jars go in java.lib)");
        }
        if (m.java() != null) {
            for (String j : m.java().classPath()) {
                if (j.contains("..") || j.contains("\\") || !j.endsWith(".jar") || j.indexOf('/') != j.lastIndexOf('/')
                        || (j.contains("/") && !j.startsWith("lib/"))) {
                    out.add("java: " + j + " is not a jar name (java.jars: names in java/, java.lib: names in java/lib/)");
                }
            }
        }
        return out;
    }

    /**
     * {@code drishti:assemble}: stages {@code source} and {@code jar} under {@code target/dmod-stage/<id>/}, packs
     * {@code target/dmod/<id>-<version>.dmod} with its hash list and writes its {@code .sha256} beside it. Returns the package.
     */
    public static Path assemble(Path source, Path jar, Path target) {
        return assemble(source, jar, target.resolve("dmod-stage"), target.resolve("dmod"));
    }

    /**
     * Stages {@code source} and {@code jar} under {@code work/<id>/} and packs {@code out/<id>-<version>.dmod} with its hash list and its
     * {@code .sha256} beside it. The one packer: the Maven plugin, the command-line tool and any other build call it.
     */
    public static Path assemble(Path source, Path jar, Path work, Path out) {
        return assemble(source, jar, List.of(), work, out);
    }

    /**
     * As above, with more jars: {@code more} are put under {@code java/} or {@code java/lib/} by their file names, as module.yaml names them
     * ({@code java.jars} after the first, {@code java.lib}); a jar module.yaml does not name is refused. Every named jar must be there.
     */
    public static Path assemble(Path source, Path jar, List<Path> more, Path work, Path out) {
        DmodManifest m = manifest(source);
        Path stage = work.resolve(m.id());
        try {
            delete(stage);
            copyTree(source, stage);
            if (m.java() != null) {
                Path to = stage.resolve("java").resolve(m.java().jars().get(0));
                if (jar != null && Files.isRegularFile(jar)) {
                    Files.createDirectories(to.getParent());
                    Files.copy(jar, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else if (!Files.isRegularFile(to)) {           // a folder from any build may hold its jar under java/ already
                    throw new IllegalStateException(m.label() + ": module.yaml names java/" + m.java().jars().get(0)
                            + ", which is neither in the folder nor given (--jar)" + (jar == null ? "" : ": " + jar + " is not there"));
                }
                for (Path x : more == null ? List.<Path>of() : more) {
                    String name = x.getFileName().toString();
                    String rel = m.java().jars().contains(name) ? name : m.java().lib().contains(name) ? "lib/" + name : null;
                    if (rel == null) {
                        throw new IllegalStateException(m.label() + ": " + name + " is given but module.yaml names it in neither java.jars nor java.lib");
                    }
                    Path t = stage.resolve("java").resolve(rel);
                    Files.createDirectories(t.getParent());
                    Files.copy(x, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                for (String rel : m.java().classPath()) {
                    if (!Files.isRegularFile(stage.resolve("java").resolve(rel))) {
                        throw new IllegalStateException(m.label() + ": module.yaml names java/" + rel + ", which is neither in the folder nor given");
                    }
                }
            }
            Path dmod = Dmod.pack(stage, out.resolve(Dmod.fileName(m.id(), m.version())));
            try (InputStream in = Files.newInputStream(dmod)) {
                Files.writeString(dmod.resolveSibling(dmod.getFileName() + ".sha256"), Dmod.sha256(in) + "  " + dmod.getFileName() + "\n",
                        StandardCharsets.UTF_8);
            }
            return dmod;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.createDirectories(t.getParent());
                    Files.copy(p, t);
                }
            }
        }
    }

    static void delete(Path dir) throws IOException {
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
