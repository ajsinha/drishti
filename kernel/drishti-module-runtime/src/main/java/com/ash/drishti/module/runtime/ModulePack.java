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

import com.ash.drishti.api.resolution.ModuleLayout;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * The ONE module packager (docs/architecture/KERNEL_AND_MODULES.md section 3.1): a module folder into {@code <id>-<version>.dmod}, byte for
 * byte the same every time it runs on the same input; {@code first-party.yaml} of a folder of packages; the freshness rule (R2: a package
 * whose folder changed since it was built, or that was built for another kernel); and the install of packages and the server jar into an
 * install's {@code modules} and {@code lib} folders. The command line is {@code module pack|first-party|fresh|install|install-jar} of the
 * server jar (ModuleCli); the Maven build (packaging/dist) runs the same commands. The verifier is {@link ModuleVerifier} and {@link Dmod}.
 *
 * <p>The package is a zip ({@link DmodZip}): {@code module.yaml} first and STORED, every other file sorted by name (Java's String order) and
 * deflated (level 6), {@code META-INF/DMOD.MF} last: {@code # DMOD.MF format 1: sha-256 of every entry} and one {@code <sha-256 hex>  <path>}
 * line per entry ({@code module.yaml} included, the trust files not). Every entry carries the time 1980-01-01 00:00 and the mode 0644.
 *
 * <p>What goes in ({@link ModuleLayout}, the one list): the names of its {@code content} at the module's top, as they are; from {@code java/}
 * only the jars directly in it; what only builds the module and anything else at the top is left out, the strays said in words.
 * {@code __pycache__} folders and {@code .pyc} files are never packed. A {@code content.yaml} brings in content from elsewhere in the
 * repository ({@code copy: [{from, to, leave-out}]}), trims the module's own folders ({@code trim: [{path, leave-out}]}) and names the files a
 * licence keeps out ({@code restricted: [{path, reason}]}). A module whose {@code module.yaml} names a {@code kernel-internals:} file is a
 * first-party module built for ONE kernel build: the list is written into the packaged module.yaml and {@code kernel-build:} stamped (the
 * build given, else the build of the kernel this code runs in).
 */
public final class ModulePack {

    public static final String MANIFEST = "module.yaml";
    public static final String CONTENT_YAML = "content.yaml";
    public static final String FIRST_PARTY = "first-party.yaml";
    public static final String PACKAGE = ".dmod";
    /** The header of the hash list. */
    public static final String HASHES_HEAD = "# DMOD.MF format 1: sha-256 of every entry\n";
    /**
     * What a pack serves at run time and content.yaml may never leave out: an install answers what the source tree answers (a pack's samples
     * are the records a read falls back to when no lake is built).
     */
    public static final Set<String> SERVED = Set.of("samples", "sutras", "config", "contracts", "pack.yaml");

    private static final String SKIP_DIR = "__pycache__";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern COMMENT = Pattern.compile("(?U)\\s+#.*$");

    private ModulePack() {
    }

    /** A module that cannot be packed, in one line of plain words. */
    public static final class PackException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public PackException(String message) {
            super(message);
        }
    }

    /** A package written: the file and the notes (strays left out). */
    public record Packed(Path dmod, List<String> notes) {
    }

    /** The id and version of a module folder's module.yaml (both are required). */
    public record Ident(String id, String version) {
        public String fileName() {
            return id + "-" + version + PACKAGE;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ manifest

    /** module.yaml of a module folder, as a map (id and version are required). */
    public static Map<String, Object> readManifest(Path folder) {
        Path f = folder.resolve(MANIFEST);
        if (!Files.isRegularFile(f)) {
            throw new PackException("there is no " + MANIFEST + " in " + folder + ": a module's files start with it");
        }
        Map<String, Object> m = yamlMap(f);
        if (m == null || blank(m.get("id")) || blank(m.get("version"))) {
            throw new PackException(f + ": module.yaml needs an id and a version");
        }
        return m;
    }

    /** The id and version of a module folder. */
    public static Ident ident(Path folder) {
        Map<String, Object> m = readManifest(folder);
        return new Ident(String.valueOf(m.get("id")), String.valueOf(m.get("version")));
    }

    private static boolean blank(Object o) {
        return o == null || o instanceof Boolean b && !b || String.valueOf(o).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yamlMap(Path f) {
        try {
            Object o = YAML.readValue(f.toFile(), Object.class);
            return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        } catch (IOException e) {
            throw new PackException(f + ": " + firstLine(e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yamlMap(byte[] data, String label) {
        try {
            Object o = YAML.readValue(data, Object.class);
            return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        } catch (IOException e) {
            throw new PackException(label + ": " + firstLine(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ kernel build

    /** {@code <version>+<time>} of one named server jar, or a refusal that says what was wrong. */
    public static String kernelBuildOfJar(Path jar) {
        if (!Files.isRegularFile(jar)) {
            throw new PackException("--kernel-jar " + jar + " is not a file");
        }
        String build = buildOf(jar);
        if (build == null) {
            throw new PackException("--kernel-jar " + jar + " holds no build-info.properties of drishti-server: give the server jar");
        }
        return build;
    }

    /**
     * The build the server jar says: a thin jar holds META-INF/build-info.properties; the executable jar holds the thin one as
     * BOOT-INF/lib/drishti-server-VERSION.jar. Null when it says none or does not read as a jar.
     */
    static String buildOf(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (String name : List.of("BOOT-INF/classes/META-INF/build-info.properties", "META-INF/build-info.properties")) {
                ZipEntry e = z.getEntry(name);
                if (e != null) {
                    String b = serverBuild(z.getInputStream(e));
                    if (b != null) {
                        return b;
                    }
                }
            }
            TreeSet<String> nested = new TreeSet<>();
            z.stream().map(ZipEntry::getName).filter(n -> n.startsWith("BOOT-INF/lib/drishti-server-") && n.endsWith(".jar")).forEach(nested::add);
            for (String n : nested) {
                try (ZipInputStream inner = new ZipInputStream(z.getInputStream(z.getEntry(n)))) {
                    for (ZipEntry e; (e = inner.getNextEntry()) != null; ) {
                        if ("META-INF/build-info.properties".equals(e.getName())) {
                            String b = serverBuild(new ByteArrayInputStream(inner.readAllBytes()));
                            if (b != null) {
                                return b;
                            }
                            break;
                        }
                    }
                }
            }
            return null;
        } catch (ZipException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String serverBuild(InputStream in) throws IOException {
        Properties p = new Properties();
        try (in) {
            p.load(in);
        }
        return "drishti-server".equals(p.getProperty("build.artifact")) ? p.getProperty("build.version") + "+" + p.getProperty("build.time") : null;
    }

    // ------------------------------------------------------------------------------------------------------------------ staging

    /** An entry's content: a file, or bytes made here (a stamped module.yaml, a trimmed catalogue). */
    private sealed interface Source permits FileSource, Bytes {
        byte[] read() throws IOException;
    }

    private record FileSource(Path path) implements Source {
        public byte[] read() throws IOException {
            return Files.readAllBytes(path);
        }
    }

    private record Bytes(byte[] data) implements Source {
        public byte[] read() {
            return data;
        }
    }

    /** The layout's content names at the module's top; from java/ only the jars directly in it. */
    private static void stageTree(Path src, Map<String, Source> entries) throws IOException {
        for (Path p : sortedChildren(src)) {
            String n = name(p);
            if (!ModuleLayout.CONTENT.contains(n)) {
                continue;
            }
            if (isDir(p)) {
                if (n.equals(SKIP_DIR)) {
                    continue;
                }
                if (n.equals("java")) {
                    if (Files.isSymbolicLink(p)) {
                        continue;
                    }
                    for (Path j : sortedChildren(p)) {
                        if (!isDir(j) && name(j).endsWith(".jar")) {
                            entries.put("java/" + name(j), new FileSource(j));
                        }
                    }
                    continue;
                }
                walk(p, n, Set.of(), entries);
            } else if (!n.endsWith(".pyc")) {
                entries.put(n, new FileSource(p));
            }
        }
    }

    /** Every file under {@code dir} as {@code prefix/...}: no __pycache__, no .pyc, nothing named in {@code leave}; symbolic links to folders are not followed. */
    private static void walk(Path dir, String prefix, Set<String> leave, Map<String, Source> entries) throws IOException {
        if (Files.isSymbolicLink(dir)) {
            return;
        }
        for (Path p : sortedChildren(dir)) {
            String n = name(p);
            if (leave.contains(n)) {
                continue;
            }
            if (isDir(p)) {
                if (!n.equals(SKIP_DIR)) {
                    walk(p, prefix + "/" + n, leave, entries);
                }
            } else if (!n.endsWith(".pyc")) {
                entries.put(prefix + "/" + n, new FileSource(p));
            }
        }
    }

    private static List<Path> sortedChildren(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            s.forEach(out::add);
        }
        out.sort((a, b) -> name(a).compareTo(name(b)));
        return out;
    }

    /** The path with its symbolic links resolved where it exists, else made absolute and normal. */
    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    private static boolean isDir(Path p) {
        return Files.isDirectory(p);
    }

    private static String name(Path p) {
        return p.getFileName().toString();
    }

    private static List<String> strays(Path src) {
        String label = src.getParent() != null && "modules".equals(name(src.getParent())) ? "modules/" + name(src) : src.toString();
        return ModuleLayout.strays(src, label);
    }

    /** content.yaml: trim the module's own folders, leave out the restricted files, then copy repository content in. */
    @SuppressWarnings("unchecked")
    private static void bringContent(Path src, Map<String, Source> entries) throws IOException {
        Path f = src.resolve(CONTENT_YAML);
        if (!Files.isRegularFile(f)) {
            return;
        }
        Map<String, Object> spec = yamlMap(f);
        if (spec == null) {
            spec = Map.of();
        }
        for (Map<String, Object> e : (List<Map<String, Object>>) list(spec, "trim")) {
            String p = strip(str(e.get("path")), '/');
            if (p.isEmpty() || List.of(p.split("/")).contains("..") || entries.keySet().stream().noneMatch(k -> k.startsWith(p + "/"))) {
                throw new PackException(f + ": trim " + pyRepr(e) + " needs a `path` of a folder inside the package");
            }
            for (Object x : list(e, "leave-out")) {
                neverServed(f, e, x);
                String gone = p + "/" + strip(String.valueOf(x), '/');
                entries.keySet().removeIf(k -> k.equals(gone) || k.startsWith(gone + "/"));
            }
        }
        for (Object e : list(spec, "restricted")) {
            leaveRestricted(f, (Map<String, Object>) e, entries);
        }
        for (Map<String, Object> e : (List<Map<String, Object>>) list(spec, "copy")) {
            Path from = real(src.resolve(str(e.get("from"))));
            String rawTo = str(e.get("to"));
            String to = strip(rawTo, '/');
            if (to.isEmpty() || List.of(to.split("/")).contains("..") || rawTo.startsWith("/") || !Files.exists(from)) {
                throw new PackException(f + ": copy " + pyRepr(e) + " needs an existing `from` and a `to` inside the package");
            }
            Set<String> leave = new HashSet<>();
            for (Object x : list(e, "leave-out")) {
                leave.add(String.valueOf(x));
            }
            for (String x : leave) {
                neverServed(f, e, x);
            }
            if (Files.isRegularFile(from)) {
                entries.put(to, new FileSource(from));
                continue;
            }
            walk(from, to, leave, entries);
        }
    }

    /** {@code restricted:}: a file the module's folder holds but its package may never carry (a licence says so), named with the reason. */
    private static void leaveRestricted(Path f, Map<String, Object> e, Map<String, Source> entries) throws IOException {
        Map<String, Object> m = e == null ? Map.of() : e;
        String p = strip(str(m.get("path")), '/');
        if (p.isEmpty() || List.of(p.split("/")).contains("..") || pyStrip(str(m.get("reason"))).isEmpty()) {
            throw new PackException(f + ": restricted " + pyRepr(e) + " needs a `path` of a file in the module and a `reason` (the licence)");
        }
        if (!entries.containsKey(p)) {
            throw new PackException(f + ": restricted " + p + " is not a file of the package's layout (a wrong name would restrict nothing)");
        }
        entries.remove(p);
        trimCatalogue(p, entries);
    }

    /** With a kind's samples restricted, the package carries the samples catalogue without that kind's entries. */
    private static void trimCatalogue(String p, Map<String, Source> entries) throws IOException {
        int slash = p.lastIndexOf('/');
        String folder = slash < 0 ? "" : p.substring(0, slash);
        String fileName = p.substring(slash + 1);
        String cat = folder + "/catalog.jsonl";
        if (!folder.endsWith("/samples") || fileName.equals("catalog.jsonl") || !entries.containsKey(cat)) {
            return;
        }
        String kind = fileName.substring(0, fileName.length() - ".jsonl".length());
        String text = new String(entries.get(cat).read(), StandardCharsets.UTF_8);
        List<String> keep = new ArrayList<>();
        for (String ln : pySplitLines(text)) {
            if (!pyStrip(ln).isEmpty()) {
                JsonNode k = JSON.readTree(ln).get("kind");
                if (!(k != null && k.isTextual() && k.asText().equals(kind))) {
                    keep.add(ln);
                }
            }
        }
        entries.put(cat, new Bytes((String.join("\n", keep) + "\n").getBytes(StandardCharsets.UTF_8)));
    }

    /** The files content.yaml of {@code folder} restricts, package-relative. */
    @SuppressWarnings("unchecked")
    public static List<String> restrictedPaths(Path folder) {
        Path f = folder.resolve(CONTENT_YAML);
        Map<String, Object> spec = Files.isRegularFile(f) ? yamlMap(f) : null;
        List<String> out = new ArrayList<>();
        for (Object e : list(spec == null ? Map.of() : spec, "restricted")) {
            out.add(strip(String.valueOf(((Map<String, Object>) e).get("path")), '/'));
        }
        return out;
    }

    /** A leave-out never names what a pack serves ({@link #SERVED}): what works from the source folder works from the package. */
    private static void neverServed(Path f, Map<String, Object> e, Object x) {
        String[] parts = strip(String.valueOf(x), '/').split("/", -1);
        if (SERVED.contains(parts[parts.length - 1])) {
            throw new PackException(f + ": " + pyRepr(e) + " leaves out " + x + ", which a pack serves at run time (an installed server would not have "
                    + "it): only what a module does not carry (tests, tools) is left out");
        }
    }

    /** The module's jars by the names module.yaml gives (java.jars into java/, java.lib into lib/); every named jar must be there. */
    @SuppressWarnings("unchecked")
    private static void placeJars(Map<String, Object> m, List<Path> jars, Map<String, Source> entries) {
        Object java = m.get("java");
        if (blank(java) || java instanceof Map<?, ?> jm && jm.isEmpty()) {
            if (!jars.isEmpty()) {
                throw new PackException(m.get("id") + ": jars are given but module.yaml has no java: section");
            }
            return;
        }
        Map<String, Object> j = (Map<String, Object>) java;
        List<String> names = list(j, "jars").stream().map(String::valueOf).toList();
        List<String> libs = list(j, "lib").stream().map(String::valueOf).toList();
        if (names.isEmpty()) {
            throw new PackException("java.jars must name at least one jar, the project's own first (third-party jars go in java.lib)");
        }
        for (String n : concat(names, libs)) {
            if (n.contains("..") || n.contains("\\") || !n.endsWith(".jar") || n.contains("/")) {
                throw new PackException("java: " + n + " is not a jar name (java.jars: file names in java/, java.lib: file names in lib/)");
            }
        }
        boolean ownPlaced = false;
        for (Path jar : jars) {
            if (!Files.isRegularFile(jar)) {
                throw new PackException(jar + " is not a file");
            }
            String n = name(jar);
            if (names.contains(n)) {
                entries.put("java/" + n, new FileSource(jar));
                ownPlaced |= n.equals(names.get(0));
            } else if (libs.contains(n)) {
                entries.put("lib/" + n, new FileSource(jar));
            } else if (!ownPlaced) {
                entries.put("java/" + names.get(0), new FileSource(jar));             // the module's own jar, whatever the build called it
                ownPlaced = true;
            } else {
                throw new PackException(m.get("id") + ": " + n + " is given but module.yaml names it in neither java.jars nor java.lib");
            }
        }
        List<String> rels = new ArrayList<>();
        names.forEach(n -> rels.add("java/" + n));
        libs.forEach(n -> rels.add("lib/" + n));
        for (String rel : rels) {
            if (!entries.containsKey(rel)) {
                throw new PackException(m.get("id") + ": module.yaml names " + rel + ", which is neither in the folder nor given (--jar)");
            }
        }
    }

    /** kernel-internals: FILE becomes the package list; kernel-build: is the build's own stamp (any older one is replaced). */
    private static String stampAllowance(Path src, String text, BuildSource build) throws IOException {
        List<String> out = new ArrayList<>();
        boolean allowance = false;
        for (String line : pySplitLines(text)) {
            if (line.startsWith("kernel-build:")) {
                continue;
            }
            if (line.startsWith("kernel-internals:")) {
                allowance = true;
                String v = pyStrip(line.substring("kernel-internals:".length()));
                if (!v.isEmpty() && !v.startsWith("[") && !v.startsWith("#")) {
                    Path lst = real(src.resolve(v));
                    if (!Files.isRegularFile(lst)) {
                        throw new PackException(src.resolve(MANIFEST) + ": kernel-internals names " + v + ", which is not a file");
                    }
                    out.add("kernel-internals:");
                    for (String p : pySplitLines(Files.readString(lst, StandardCharsets.UTF_8))) {
                        if (!pyStrip(p).isEmpty() && !pyStrip(p).startsWith("#")) {
                            out.add("- " + pyStrip(COMMENT.matcher(p).replaceAll("")));
                        }
                    }
                    continue;
                }
            }
            out.add(line);
        }
        if (allowance) {
            String b = build.get();
            if (b == null) {
                throw new PackException(src.resolve(MANIFEST) + ": a module with a kernel-internals allowance is packed for one kernel build: give it "
                        + "(--kernel-build or --kernel-jar), or run the packer of that kernel's server jar");
            }
            out.add("kernel-build: '" + b + "'");
        }
        return String.join("\n", out) + "\n";
    }

    /** The kernel build a stamp takes, asked for only when a module has an allowance. */
    @FunctionalInterface
    private interface BuildSource {
        String get();
    }

    private static BuildSource buildSource(String kernelBuild) {
        return () -> kernelBuild != null ? kernelBuild : KernelFacts.thisBuild();
    }

    // ------------------------------------------------------------------------------------------------------------------ pack

    /** The entries as the package holds them, in package order (module.yaml first), with their bytes, and the DMOD.MF text. */
    private record Collected(Map<String, Object> manifest, List<String> notes, LinkedHashMap<String, byte[]> data, String hashes) {
    }

    private static Collected collect(Path src, List<Path> jars, String kernelBuild, boolean withJars) throws IOException {
        Map<String, Object> m = readManifest(src);
        List<String> notes = strays(src);
        Map<String, Source> entries = new LinkedHashMap<>();
        stageTree(src, entries);
        bringContent(src, entries);
        if (withJars) {
            placeJars(m, jars, entries);
        }
        String text = Files.readString(src.resolve(MANIFEST), StandardCharsets.UTF_8);
        if (text.contains("kernel-internals:") || text.contains("kernel-build:")) {
            entries.put(MANIFEST, new Bytes(stampAllowance(src, text, buildSource(kernelBuild)).getBytes(StandardCharsets.UTF_8)));
        } else {
            entries.put(MANIFEST, new FileSource(src.resolve(MANIFEST)));
        }
        LinkedHashMap<String, byte[]> data = new LinkedHashMap<>();
        data.put(MANIFEST, entries.get(MANIFEST).read());
        for (String n : new TreeSet<>(entries.keySet())) {
            if (!n.equals(MANIFEST)) {
                data.put(n, entries.get(n).read());
            }
        }
        StringBuilder mf = new StringBuilder(HASHES_HEAD);
        new TreeMap<>(data).forEach((n, b) -> mf.append(sha256(b)).append("  ").append(n).append('\n'));
        return new Collected(m, notes, data, mf.toString());
    }

    /**
     * Packs a module folder into {@code out/<id>-<version>.dmod} (and its {@code .sha256}); {@code out} defaults to
     * {@code <folder>/target/dmod}. {@code kernelBuild} is stamped in a module with a kernel-internals allowance (null: this kernel's).
     */
    public static Packed pack(Path folder, List<Path> jars, Path out, String kernelBuild) {
        Path src = realFolder(folder, "pack");
        try {
            Collected c = collect(src, jars, kernelBuild, true);
            Path dir = (out != null ? out : src.resolve("target").resolve("dmod")).toAbsolutePath().normalize();
            Path target = dir.resolve(c.manifest().get("id") + "-" + c.manifest().get("version") + PACKAGE);
            Files.createDirectories(dir);
            Path part = dir.resolve(name(target) + ".part");
            try (DmodZip z = new DmodZip(part)) {
                c.data().forEach((n, b) -> z.put(n, b, !n.equals(MANIFEST)));
                z.put(Dmod.HASHES, c.hashes().getBytes(StandardCharsets.UTF_8), true);
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            String sum;
            try (InputStream in = Files.newInputStream(target)) {
                sum = Dmod.sha256(in);
            }
            Files.writeString(dir.resolve(name(target) + ".sha256"), sum + "  " + name(target) + "\n", StandardCharsets.UTF_8);
            return new Packed(target, c.notes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The content lines of the DMOD.MF a package of {@code folder} would carry, computed from the SOURCE (R2, the stale check): what the
     * folder holds and content.yaml brings in, never a time. A module's own jars are the build's, not the source's: they are not in it.
     */
    public static String digest(Path folder, String kernelBuild) {
        try {
            return collect(realFolder(folder, "digest"), List.of(), kernelBuild, false).hashes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The entries of {@code folder}'s source that differ from the package {@code dmod} (changed, new or gone; the jars are not compared),
     * sorted, or none when the package is current. The kernel build of an allowance is the package's own.
     */
    public static List<String> stale(Path folder, Path dmod) {
        Map<String, String> held;
        String build = null;
        try (ZipFile z = new ZipFile(dmod.toFile())) {
            ZipEntry hashes = z.getEntry(Dmod.HASHES);
            if (hashes == null) {
                throw new PackException(name(dmod) + ": " + Dmod.HASHES + " (the hash list) is missing");
            }
            held = hashLines(new String(z.getInputStream(hashes).readAllBytes(), StandardCharsets.UTF_8));
            ZipEntry man = z.getEntry(MANIFEST);
            if (man == null) {
                throw new PackException(name(dmod) + ": " + MANIFEST + " is missing");
            }
            for (String line : pySplitLines(new String(z.getInputStream(man).readAllBytes(), StandardCharsets.UTF_8))) {
                if (line.startsWith("kernel-build:")) {
                    build = strip(strip(pyStrip(line.split(":", 2)[1]), '\''), '"');
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> now = hashLines(digest(folder, build));
        TreeSet<String> all = new TreeSet<>(held.keySet());
        all.addAll(now.keySet());
        List<String> out = new ArrayList<>();
        for (String n : all) {
            boolean built = (n.startsWith("java/") || n.startsWith("lib/")) && n.endsWith(".jar");      // the build's, not the source's
            if (!built && !java.util.Objects.equals(held.get(n), now.get(n))) {
                out.add(n);
            }
        }
        return out;
    }

    private static Map<String, String> hashLines(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : pySplitLines(text)) {
            if (!line.isEmpty() && line.charAt(0) != '#') {
                int i = line.indexOf("  ");
                if (i > 0) {
                    out.put(line.substring(i + 2), line.substring(0, i));
                }
            }
        }
        return out;
    }

    private static Path realFolder(Path folder, String what) {
        Path src = folder.toAbsolutePath().normalize();
        if (!Files.isDirectory(src)) {
            throw new PackException(folder + " is not a folder: " + what + " takes the module's folder (module.yaml at its root)");
        }
        try {
            return src.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ first-party

    /**
     * first-party.yaml of every .dmod in {@code folder}: id, version and the SHA-256 of its META-INF/DMOD.MF, under the repository's licence
     * header (config/license-header.txt of the nearest folder above that has one). Default: {@code <folder>/first-party.yaml}.
     */
    public static Path firstParty(Path folder, Path out) {
        try {
            TreeMap<String, String> rows = new TreeMap<>();
            List<Path> dmods = new ArrayList<>();
            if (Files.isDirectory(folder)) {
                for (Path p : sortedChildren(folder)) {
                    if (name(p).endsWith(PACKAGE) && !isDir(p)) {
                        dmods.add(p);
                    }
                }
            }
            for (Path d : dmods) {
                try (ZipFile z = new ZipFile(d.toFile())) {
                    ZipEntry hashes = z.getEntry(Dmod.HASHES);
                    if (hashes == null) {
                        throw new PackException(name(d) + ": " + Dmod.HASHES + " (the hash list) is missing");
                    }
                    ZipEntry man = z.getEntry(MANIFEST);
                    if (man == null) {
                        throw new PackException(name(d) + ": " + MANIFEST + " is missing");
                    }
                    Map<String, Object> m = yamlMap(z.getInputStream(man).readAllBytes(), name(d));
                    String sum;
                    try (InputStream in = z.getInputStream(hashes)) {
                        sum = Dmod.sha256(in);
                    }
                    rows.put(String.valueOf(m.get("id")), "  - {id: " + m.get("id") + ", version: " + m.get("version") + ", sha256: " + sum + "}\n");
                }
            }
            Path target = out != null ? out : folder.resolve(FIRST_PARTY);
            StringBuilder text = new StringBuilder(legalHeader(target.toAbsolutePath().getParent()));
            text.append("# The product's own modules (KERNEL_AND_MODULES.md section 3.3): a .dmod is first-party only when its id and\n")
                    .append("# the SHA-256 of its META-INF/DMOD.MF are listed here. Written by the build, never by hand.\nmodules:\n");
            rows.values().forEach(text::append);
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.writeString(target, text, StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** config/license-header.txt of the nearest folder at or above {@code from}, as YAML comment lines and a blank line; empty when none. */
    static String legalHeader(Path from) throws IOException {
        for (Path p = from; p != null; p = p.getParent()) {
            Path head = p.resolve("config").resolve("license-header.txt");
            if (Files.isRegularFile(head)) {
                StringBuilder sb = new StringBuilder();
                for (String l : pySplitLines(pyStrip(Files.readString(head, StandardCharsets.UTF_8)))) {
                    sb.append(stripTrailing("# " + l)).append('\n');
                }
                return sb.append('\n').toString();
            }
        }
        return "";
    }

    // ------------------------------------------------------------------------------------------------------------------ freshness (R2)

    /**
     * What is wrong with the packages of a modules folder, in words, each with the command that mends it; empty when every module folder
     * under it has a current package. Only folders that are there are judged: an install holds packages and no sources, and is never stale.
     * With {@code jar}, a package stamped for another kernel build than that jar's is named too.
     */
    public static List<String> fresh(Path modules, Path jar) {
        List<String> out = new ArrayList<>();
        String now = null;
        boolean nowKnown = false;
        for (Path folder : moduleFolders(modules)) {
            Ident m = ident(folder);
            String fix = "python tools/drishti.py build modules " + m.id();
            Path dmod = modules.resolve(m.fileName());
            String label = "modules/" + name(folder);
            if (!Files.isRegularFile(dmod)) {
                out.add(label + " has no package (" + m.fileName() + "): " + fix);
                continue;
            }
            List<String> changed;
            try {
                changed = stale(folder, dmod);
            } catch (PackException | UncheckedIOException e) {
                out.add(label + ": " + m.fileName() + " does not compare (" + e.getMessage() + "): " + fix);
                continue;
            }
            if (!changed.isEmpty()) {
                String more = changed.size() > 1 ? " and " + (changed.size() - 1) + " more" : "";
                out.add(label + " changed since " + m.fileName() + " was built (" + changed.get(0) + more + "): " + fix);
                continue;
            }
            String built = stampOf(dmod);
            if (built != null && jar != null) {
                if (!nowKnown) {
                    nowKnown = true;
                    now = Files.isRegularFile(jar) ? buildOf(jar) : null;
                }
                if (now != null && !now.equals(built)) {
                    out.add(m.fileName() + " was built for the kernel " + built + " and the server jar is " + now + ": " + fix);
                }
            }
        }
        return out;
    }

    /** The module source folders under a modules folder (a folder with module.yaml at its top), sorted. */
    public static List<Path> moduleFolders(Path modules) {
        if (!Files.isDirectory(modules)) {
            return List.of();
        }
        try {
            return sortedChildren(modules).stream().filter(d -> isDir(d) && Files.isRegularFile(d.resolve(MANIFEST))).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The kernel-build: stamp of a package, or null. */
    public static String stampOf(Path dmod) {
        try (ZipFile z = new ZipFile(dmod.toFile())) {
            ZipEntry man = z.getEntry(MANIFEST);
            if (man == null) {
                return null;
            }
            for (String line : pySplitLines(new String(z.getInputStream(man).readAllBytes(), StandardCharsets.UTF_8))) {
                if (line.startsWith("kernel-build:")) {
                    return strip(strip(pyStrip(line.split(":", 2)[1]), '\''), '"');
                }
            }
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ install

    /**
     * The packages of {@code dmods} into an install's modules folder ({@code only}: those ids; empty: all): each through a {@code .part} file
     * and one replace, an older file of the same module removed; then first-party.yaml of the modules folder is written. Returns the names.
     */
    public static List<String> install(Path dmods, Path modules, Set<String> only) {
        try {
            Files.createDirectories(modules);
            List<String> done = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Path d : sortedChildren(dmods)) {
                if (!name(d).endsWith(PACKAGE) || isDir(d)) {
                    continue;
                }
                String id;
                try (ZipFile z = new ZipFile(d.toFile())) {
                    ZipEntry man = z.getEntry(MANIFEST);
                    if (man == null) {
                        throw new PackException(name(d) + ": " + MANIFEST + " is missing");
                    }
                    id = String.valueOf(yamlMap(z.getInputStream(man).readAllBytes(), name(d)).get("id"));
                }
                seen.add(id);
                if (!only.isEmpty() && !only.contains(id)) {
                    continue;
                }
                put(d, modules);
                done.add(name(d));
            }
            List<String> unknown = only.stream().filter(i -> !seen.contains(i)).sorted().toList();
            if (!unknown.isEmpty()) {
                throw new PackException("no package of " + String.join(", ", unknown) + " in " + dmods + " (packages of: " + String.join(", ", new TreeSet<>(seen)) + ")");
            }
            firstParty(modules, null);
            return done;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One package into the modules folder: through a .part file and one replace; an older file of the same module is removed. */
    static void put(Path dmod, Path modules) throws IOException {
        String file = name(dmod);
        String stem = file.substring(0, file.lastIndexOf('-'));
        for (Path old : sortedChildren(modules)) {
            String n = name(old);
            if (n.startsWith(stem + "-") && n.endsWith(PACKAGE) && !n.equals(file) && n.length() > stem.length() + 1
                    && Character.isDigit(n.charAt(stem.length() + 1))) {                // banking-1.0.0, never banking-risk-1.0.0
                Files.delete(old);
            }
        }
        Path part = modules.resolve(file + ".part");
        Files.copy(dmod, part, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        Files.move(part, modules.resolve(file), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * The server's executable jar into an install's lib folder: through a .part file and one replace; any other drishti-server-*-exec.jar
     * there removed (one server jar in lib; lib/ext, the site's jars, is never touched). Nothing to do when the jar already is the one in lib.
     */
    public static Path installJar(Path jar, Path lib) {
        try {
            Files.createDirectories(lib);
            Path target = lib.resolve(name(jar));
            if (Files.exists(target) && Files.isSameFile(jar, target)) {
                return target;
            }
            for (Path old : sortedChildren(lib)) {
                String n = name(old);
                if (n.startsWith("drishti-server-") && n.endsWith("-exec.jar") && !n.equals(name(jar)) && !isDir(old)) {
                    Files.delete(old);
                }
            }
            Path part = lib.resolve(name(jar) + ".part");
            Files.copy(jar, part, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ helpers

    /** The SHA-256 of bytes, lower-case hex. */
    public static String sha256(byte[] data) {
        try {
            return Dmod.sha256(new ByteArrayInputStream(data));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<?> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof List<?> l ? l : List.of();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** A map as words in a refusal: {k: v, ...}. */
    private static String pyRepr(Object o) {
        return String.valueOf(o).replace("=", ": ");
    }

    private static String strip(String s, char c) {
        int a = 0;
        int b = s.length();
        while (a < b && s.charAt(a) == c) {
            a++;
        }
        while (b > a && s.charAt(b - 1) == c) {
            b--;
        }
        return s.substring(a, b);
    }

    private static boolean space(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u0085';
    }

    /** Whitespace stripped from both ends, as the text tools of the format strip it (Unicode white space). */
    static String pyStrip(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && space(s.charAt(a))) {
            a++;
        }
        while (b > a && space(s.charAt(b - 1))) {
            b--;
        }
        return s.substring(a, b);
    }

    private static String stripTrailing(String s) {
        int b = s.length();
        while (b > 0 && space(s.charAt(b - 1))) {
            b--;
        }
        return s.substring(0, b);
    }

    /** Lines split at every line boundary (\n, \r\n, \r, \u000b, \u000c, \u001c-\u001e, \u0085,  ,  ), no terminators, no trailing empty line. */
    static List<String> pySplitLines(String s) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            boolean br = c == '\n' || c == '\r' || c == '\u000b' || c == '\u000c' || c == '\u001c' || c == '\u001d' || c == '\u001e'
                    || c == '\u0085' || c == ' ' || c == ' ';
            if (br) {
                out.add(s.substring(start, i));
                i += c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n' ? 2 : 1;
                start = i;
            } else {
                i++;
            }
        }
        if (start < s.length()) {
            out.add(s.substring(start));
        }
        return out;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "does not read";
        }
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
