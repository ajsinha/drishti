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
 *
 * <p>A module folder may also hold {@value #CONTENT}: content the packer brings in from elsewhere in the same repository, so a pack, a
 * lens or a page that the running product still reads from its old place has one home and is never copied by hand. Each entry copies
 * {@code from} (a file or folder, relative to the module folder) to {@code to} inside the package, leaving out the folder or file names
 * of {@code leave-out}; {@code sections-out} drops those top-level sections of every {@code pack.yaml} it copies (the pack's connector
 * templates: a module carries no connector, the site binds its kinds). {@code trim} entries ({@code path}, {@code leave-out},
 * {@code sections-out}) do the same to a folder of the module itself (its packs, which live in it) after it is staged. The file itself is not packed.
 */
public final class ModuleAssembler {

    /** The module folder's list of repository content to bring in (not packed). */
    public static final String CONTENT = "content.yaml";

    private static final com.fasterxml.jackson.databind.ObjectMapper YAML =
            new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());

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
                if (j.contains("..") || j.contains("\\") || !j.endsWith(".jar") || j.indexOf('/') != j.lastIndexOf('/')) {
                    out.add("java: " + j + " is not a jar name (java.jars: file names in java/, java.lib: file names in lib/)");
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
     * As above, with more jars: {@code more} are put under {@code java/} or {@code lib/} by their file names, as module.yaml names them
     * ({@code java.jars} after the first, {@code java.lib}); a jar module.yaml does not name is refused. Every named jar must be there.
     */
    public static Path assemble(Path source, Path jar, List<Path> more, Path work, Path out) {
        return assemble(source, jar, more, work, out, null);
    }

    /**
     * As above, stamping a first-party module's allowance: a {@code kernel-internals:} that names a file (relative to the folder: one
     * package a line, {@code #} comments) is written into the packed manifest as the list, and {@code kernelBuild} (the kernel build this
     * build made, section 3.3) as its {@code kernel-build:}. A module without an allowance is packed unchanged.
     */
    public static Path assemble(Path source, Path jar, List<Path> more, Path work, Path out, String kernelBuild) {
        DmodManifest m = manifest(source);
        Path stage = work.resolve(m.id());
        try {
            delete(stage);
            copyTree(source, stage);
            bringContent(source, stage);
            stampAllowance(source, stage, kernelBuild);
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
                    String rel = m.java().jars().contains(name) ? "java/" + name : m.java().lib().contains(name) ? "lib/" + name : null;
                    if (rel == null) {
                        throw new IllegalStateException(m.label() + ": " + name + " is given but module.yaml names it in neither java.jars nor java.lib");
                    }
                    Path t = stage.resolve(rel);
                    Files.createDirectories(t.getParent());
                    Files.copy(x, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                for (String rel : m.java().classPath()) {
                    if (!Files.isRegularFile(stage.resolve(rel))) {
                        throw new IllegalStateException(m.label() + ": module.yaml names " + rel + ", which is neither in the folder nor given");
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

    /** The allowance stamp of {@link #assemble(Path, Path, List, Path, Path, String)}. */
    static void stampAllowance(Path source, Path stage, String kernelBuild) throws IOException {
        Path file = stage.resolve(DmodManifest.FILE);
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        boolean allowance = false;
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            if (l.startsWith("kernel-build:")) {
                continue;                                              // the build's own stamp replaces any
            }
            if (l.startsWith("kernel-internals:")) {
                allowance = true;
                String v = l.substring("kernel-internals:".length()).strip();
                if (!v.isEmpty() && !v.startsWith("[") && !v.startsWith("#")) {
                    Path list = source.resolve(v).normalize();
                    if (!Files.isRegularFile(list)) {
                        throw new IllegalStateException(file + ": kernel-internals names " + v + ", which is not a file");
                    }
                    out.add("kernel-internals:");
                    for (String p : Files.readAllLines(list, StandardCharsets.UTF_8)) {
                        if (!p.isBlank() && !p.strip().startsWith("#")) {
                            out.add("- " + p.replaceAll("\\s+#.*$", "").strip());
                        }
                    }
                    continue;
                }
            }
            out.add(l);
        }
        if (allowance) {
            if (kernelBuild == null || kernelBuild.isBlank()) {
                throw new IllegalStateException(file + ": a module with a kernel-internals allowance is packed for one kernel build: give it "
                        + "(--kernel-build)");
            }
            out.add("kernel-build: '" + kernelBuild + "'");
        }
        Files.write(file, out, StandardCharsets.UTF_8);
    }

    /** Copies what {@value #CONTENT} names into the stage, then removes the list from it. */
    static void bringContent(Path source, Path stage) throws IOException {
        Path list = source.resolve(CONTENT);
        if (!Files.isRegularFile(list)) {
            return;
        }
        Files.deleteIfExists(stage.resolve(CONTENT));
        com.fasterxml.jackson.databind.JsonNode root = YAML.readTree(list.toFile());
        for (com.fasterxml.jackson.databind.JsonNode e : root.path("trim")) {          // the module's own packs, without what it does not carry
            Path dir = stage.resolve(e.path("path").asText("")).normalize();
            if (e.path("path").asText("").isBlank() || e.path("path").asText().contains("..") || !Files.isDirectory(dir)) {
                throw new IllegalStateException(list + ": trim " + e + " needs a `path` of a folder inside the package");
            }
            for (com.fasterxml.jackson.databind.JsonNode x : e.path("leave-out")) {
                delete(dir.resolve(x.asText()));
            }
            List<String> sectionsOut = new ArrayList<>();
            e.path("sections-out").forEach(x -> sectionsOut.add(x.asText()));
            Path manifest = dir.resolve("pack.yaml");
            if (!sectionsOut.isEmpty() && Files.isRegularFile(manifest)) {
                Files.writeString(manifest, withoutSections(Files.readString(manifest, StandardCharsets.UTF_8), sectionsOut), StandardCharsets.UTF_8);
            }
        }
        for (com.fasterxml.jackson.databind.JsonNode e : root.path("copy")) {
            Path from = source.resolve(e.path("from").asText("")).normalize();
            String to = e.path("to").asText("");
            if (to.isBlank() || to.contains("..") || to.startsWith("/") || !Files.exists(from)) {
                throw new IllegalStateException(list + ": copy " + e + " needs an existing `from` and a `to` inside the package");
            }
            java.util.Set<String> leaveOut = new java.util.HashSet<>();
            e.path("leave-out").forEach(x -> leaveOut.add(x.asText()));
            List<String> sectionsOut = new ArrayList<>();
            e.path("sections-out").forEach(x -> sectionsOut.add(x.asText()));
            Path dest = stage.resolve(to);
            try (Stream<Path> s = Files.walk(from)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    Path rel = from.relativize(p);
                    boolean left = false;
                    for (Path part : rel) {
                        left |= leaveOut.contains(part.toString());
                    }
                    if (left) {
                        continue;
                    }
                    Path t = rel.toString().isEmpty() ? dest : dest.resolve(rel.toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(t);
                    } else {
                        Files.createDirectories(t.getParent());
                        if (!sectionsOut.isEmpty() && "pack.yaml".equals(p.getFileName().toString())) {
                            Files.writeString(t, withoutSections(Files.readString(p, StandardCharsets.UTF_8), sectionsOut), StandardCharsets.UTF_8);
                        } else {
                            Files.copy(p, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
        }
    }

    /** The YAML text without the named top-level sections (each runs to the next top-level key). */
    static String withoutSections(String yaml, List<String> sections) {
        StringBuilder out = new StringBuilder();
        boolean skipping = false;
        for (String line : yaml.split("\n", -1)) {
            java.util.regex.Matcher k = TOP_KEY.matcher(line);
            if (k.lookingAt()) {
                skipping = sections.contains(k.group(1));
            }
            if (!skipping) {
                out.append(line).append('\n');
            }
        }
        return out.substring(0, Math.max(0, out.length() - 1));
    }

    private static final java.util.regex.Pattern TOP_KEY = java.util.regex.Pattern.compile("([A-Za-z_][\\w-]*):");

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
