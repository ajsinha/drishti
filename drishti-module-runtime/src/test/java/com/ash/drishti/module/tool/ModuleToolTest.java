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

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.module.runtime.KernelFacts;
import com.ash.drishti.module.runtime.ModuleHealth;
import com.ash.drishti.module.runtime.ModuleRuntime;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The Module SDK end to end, without Maven in the loop: a module generated from the archetype's own templates (as {@code mvn
 * archetype:generate} would: the descriptor's file sets, packaged Java, the properties), compiled, packed by the one packer (the Maven
 * plugin's and the tool's), put through the fifteen conformance checks, then loaded into the module runtime: load, route, unload. A second
 * case builds a module with javac and a folder only and takes it through the standalone tool (pack, check, inspect) into the runtime. A
 * third breaks the generated module in the ways the checks exist for and reads their plain words.
 */
class ModuleToolTest {

    @TempDir
    Path tmp;

    @Test
    void aModuleFromTheArchetypeBuildsPassesConformanceAndLoadsRoutesAndUnloads() throws Exception {
        Path project = generate("hello");
        Path jar = build(project, "hello");
        Path source = project.resolve("src/dmod");
        assertThat(ModuleAssembler.validate(source, "hello", "0.1.0")).isEmpty();
        assertThat(ModuleAssembler.validate(source, "hello", "0.2.0")).containsExactly(
                "module.yaml says version 0.1.0 but the project is 0.2.0: make them the same");

        Path dmod = ModuleAssembler.assemble(source, jar, project.resolve("target"));
        assertThat(dmod.getFileName()).hasToString("hello-0.1.0.dmod");
        assertThat(dmod.resolveSibling("hello-0.1.0.dmod.sha256")).isRegularFile();
        assertThat(source.resolve("META-INF")).doesNotExist();                         // nothing written into the sources

        List<ModuleConformance.Check> checks = ModuleConformance.run(dmod, project, tmp.resolve("conformance"), 3);
        assertThat(checks).extracting(ModuleConformance.Check::id).containsExactly("C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9",
                "C10", "C11", "C12", "C13", "C14", "C15");
        assertThat(checks).allSatisfy(c -> assertThat(c.status()).as(c.line()).isEqualTo(ModuleConformance.Status.PASS));

        // load -> route -> unload, as the kernel will
        Path modules = tmp.resolve("kernel/modules");
        com.ash.drishti.module.runtime.Dmod.unpack(dmod, modules.resolve("hello/0.1.0"));
        Files.copy(dmod, modules.resolve("hello").resolve(dmod.getFileName()));
        try (ModuleRuntime rt = new ModuleRuntime(KernelFacts.ofKernel(), null, getClass().getClassLoader(), null, tmp.resolve("tombstones"))) {
            assertThat(rt.start(List.of(modules), Map.of(), id -> true)).extracting(ModuleHealth::state).containsExactly(ModuleHealth.State.LOADED);
            assertThat(greeting(rt)).isEqualTo("Hello, Asha, from the hello module");
            assertThat(rt.routes().lookup("/m/hello/hello/Asha").orElseThrow().status()).isEqualTo(200);
            assertThat(rt.routes().lookup("/m/hello/hello/Asha").orElseThrow().route().function()).isEqualTo("HELLO");
            ModuleRuntime.UnloadReport r = rt.unload("hello", ModuleRuntime.Leave.REMOVE, true);
            assertThat(r.failures()).isEmpty();
            assertThat(r.leakFree()).isTrue();
            assertThat(rt.routes().lookup("/m/hello/hello/Asha").orElseThrow().status()).isEqualTo(410);
            assertThat(rt.loaded("hello")).isEmpty();
        }
    }

    @Test
    void theVisualTemplateAddsAKindAsAnElementsExtensionAndThePackageStillPasses() throws Exception {
        Path project = generate("tracks");
        Path jar = build(project, "tracks");
        Path source = project.resolve("src/dmod");
        assertThat(VisualTemplate.add(source, "tracks", "gene-track", "Gene track")).extracting(p -> source.relativize(p).toString().replace('\\', '/'))
                .containsExactly("elements/drishti-tracks/drishti_tracks.js", "console/plugin.yaml");
        String js = Files.readString(source.resolve("elements/drishti-tracks/drishti_tracks.js"));
        assertThat(js).contains("registerKind(KIND", "'tracks.gene-track'", "C.buildSeriesChart", "Copyright").doesNotContain("<canvas", "<svg", "#");
        assertThat(Files.readString(source.resolve("console/plugin.yaml"))).contains("visuals:", "kind: tracks.gene-track", "library: drishti-tracks");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> VisualTemplate.add(source, "tracks", "gene-track", null))
                .hasMessageContaining("declared already");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> VisualTemplate.add(source, "tracks", "Bad Name", null))
                .hasMessageContaining("lower-case");
        Path dmod = ModuleAssembler.assemble(source, jar, project.resolve("target"));
        List<ModuleConformance.Check> checks = ModuleConformance.run(dmod, project, tmp.resolve("conformance-visual"), 2);
        assertThat(checks).allSatisfy(c -> assertThat(c.status()).as(c.line()).isEqualTo(ModuleConformance.Status.PASS));
    }

    @Test
    void aModuleBuiltWithJavacAndAFolderIsPackedCheckedAndInspectedByTheToolAndLoads() throws Exception {
        Path project = generate("plain");
        Path jar = build(project, "plain");
        // any build: the module's files in the .dmod layout, its jar under java/ by the name module.yaml gives
        Path folder = tmp.resolve("plain-module");
        ModuleAssembler.copyTree(project.resolve("src/dmod"), folder);
        Files.createDirectories(folder.resolve("java"));
        Files.copy(jar, folder.resolve("java/plain.jar"));

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        java.io.PrintStream o = new java.io.PrintStream(out, true, StandardCharsets.UTF_8);
        java.io.PrintStream e = new java.io.PrintStream(err, true, StandardCharsets.UTF_8);
        Path dist = tmp.resolve("dist");
        assertThat(ModuleTool.run(new String[] {"pack", folder.toString(), "--out", dist.toString()}, o, e)).as(err.toString()).isZero();
        Path dmod = dist.resolve("plain-0.1.0.dmod");
        assertThat(dmod).isRegularFile();
        assertThat(ModuleTool.run(new String[] {"check", dmod.toString(), "--project", project.toString(), "--cycles", "2"}, o, e))
                .as(out.toString()).isZero();
        assertThat(out.toString()).contains("C1 No kernel internals: passed", "C15 Class loader unloads: passed",
                "all 15 conformance checks passed");
        assertThat(ModuleTool.run(new String[] {"check", folder.toString(), "--cycles", "1"}, o, e)).isZero();   // a folder: packed first
        out.reset();
        assertThat(ModuleTool.run(new String[] {"inspect", dmod.toString()}, o, e)).isZero();
        assertThat(out.toString()).contains("plain 0.1.0", "java:      [plain.jar] entry com.example.plain.HelloModule",
                "every file matches META-INF/DMOD.MF");
        assertThat(ModuleTool.run(new String[] {"pack"}, o, e)).isEqualTo(2);
        assertThat(ModuleTool.run(new String[] {"pack", folder.toString(), "--bogus", "x"}, o, e)).isEqualTo(2);

        Path modules = tmp.resolve("plain-kernel/modules");
        com.ash.drishti.module.runtime.Dmod.unpack(dmod, modules.resolve("plain/0.1.0"));
        Files.copy(dmod, modules.resolve("plain").resolve(dmod.getFileName()));
        try (ModuleRuntime rt = new ModuleRuntime(KernelFacts.ofKernel(), null, getClass().getClassLoader(), null, tmp.resolve("plain-tomb"))) {
            assertThat(rt.start(List.of(modules), Map.of(), id -> true)).extracting(ModuleHealth::state).containsExactly(ModuleHealth.State.LOADED);
            assertThat(rt.routes().lookup("/m/plain/hello/x").orElseThrow().status()).isEqualTo(200);
            assertThat(rt.unload("plain", ModuleRuntime.Leave.REMOVE, true).leakFree()).isTrue();
        }
    }

    @Test
    void aModuleThatBreaksTheRulesFailsTheChecksInPlainWords() throws Exception {
        Path project = generate("rogue");
        Path java = project.resolve("src/main/java/com/example/rogue");
        Files.writeString(java.resolve("Leaky.java"), """
                /*
                 * Copyright (c) the publisher of rogue. All rights reserved.
                 */
                package com.example.rogue;
                public class Leaky {
                    public static java.util.Map<String, String> lastAnswer = new java.util.HashMap<>();
                    public String internals() { return com.ash.drishti.module.runtime.Dmod.fileName("a", "b"); }
                }
                """);
        Path src = project.resolve("src/dmod");
        Files.writeString(src.resolve("console/pages/hello.html"), "<table><tr><td onclick=\"go()\">x</td></tr></table><script>go()</script>\n");
        Files.writeString(src.resolve("console/static/drishti_module.css"), "/* Copyright x */\n.a { color: #ff0000; }\n");
        Files.writeString(src.resolve("console/static/rogue.js"), "import x from 'https://cdn.example.com/lib.js';\n");
        Files.writeString(src.resolve("data/source.yaml"), "connector: risklake\npassword: hunter2\n");
        Files.delete(src.resolve("packs/rogue-pack/contracts/rogue-greeting.yaml"));
        Files.delete(src.resolve("docs/tutorial.md"));
        Path dmod = ModuleAssembler.assemble(src, build(project, "rogue"), project.resolve("target"));

        Map<String, ModuleConformance.Check> byId = new LinkedHashMap<>();
        ModuleConformance.run(dmod, project, tmp.resolve("rogue-conformance"), 1).forEach(c -> byId.put(c.id(), c));
        assertThat(byId.get("C1").problems()).anyMatch(p -> p.contains("com.example.rogue.Leaky uses com.ash.drishti.module.runtime.Dmod, a kernel internal"));
        assertThat(byId.get("C2").problems()).anyMatch(p -> p.startsWith("console/pages/hello.html draws its own <table>"))
                .anyMatch(p -> p.contains("imports https://cdn.example.com/lib.js"));
        assertThat(byId.get("C3").problems()).anyMatch(p -> p.contains("#ff0000") && p.contains("theme tokens"));
        assertThat(byId.get("C4").problems()).anyMatch(p -> p.contains("console/static/rogue.js must be named drishti_rogue.js"));
        assertThat(byId.get("C5").problems()).anyMatch(p -> p.contains("inline <script>")).anyMatch(p -> p.contains("onclick="));
        assertThat(byId.get("C7").problems()).anyMatch(p -> p.contains("docs/tutorial.md, which is not in the package"));
        assertThat(byId.get("C8").problems()).anyMatch(p -> p.startsWith("kind rogue-greeting has no contract"));
        assertThat(byId.get("C9").problems()).anyMatch(p -> p.startsWith("data/source.yaml has \"connector:\""));
        assertThat(byId.get("C10").problems()).anyMatch(p -> p.startsWith("console/static/rogue.js has no licence header"));
        assertThat(byId.get("C12").problems()).anyMatch(p -> p.startsWith("com.example.rogue.Leaky.lastAnswer is a static field that is not final"));
        for (String passing : List.of("C6", "C11", "C13", "C14", "C15")) {
            assertThat(byId.get(passing).status()).as(byId.get(passing).line()).isEqualTo(ModuleConformance.Status.PASS);
        }
    }

    /** Calls the module's function bean through its own class loader (a frame of its own, so nothing of the module stays reachable). */
    private static String greeting(ModuleRuntime rt) throws Exception {
        Object bean = rt.loaded("hello").orElseThrow().context().getBean("greeting");
        return (String) bean.getClass().getMethod("answer", String.class).invoke(bean, "Asha");
    }

    // ---- the archetype, as archetype:generate applies it ------------------------------------------------------------------------------

    private Path generate(String artifactId) throws Exception {
        Path archetype = Path.of(System.getProperty("drishti.sdk.archetype"));
        Map<String, String> props = new LinkedHashMap<>();
        props.put("groupId", "com.example." + artifactId);
        props.put("artifactId", artifactId);
        props.put("package", "com.example." + artifactId);
        props.put("drishtiVersion", System.getProperty("drishti.sdk.version"));
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(archetype.resolve("META-INF/maven/archetype-metadata.xml").toFile());
        NodeList required = doc.getElementsByTagName("requiredProperty");
        for (int i = 0; i < required.getLength(); i++) {
            Element e = (Element) required.item(i);
            props.putIfAbsent(e.getAttribute("key"), e.getElementsByTagName("defaultValue").item(0).getTextContent());
        }
        Path templates = archetype.resolve("archetype-resources");
        Path project = tmp.resolve(artifactId);
        write(project.resolve("pom.xml"), fill(Files.readString(templates.resolve("pom.xml")), props));
        NodeList sets = doc.getElementsByTagName("fileSet");
        for (int i = 0; i < sets.getLength(); i++) {
            Element set = (Element) sets.item(i);
            String dir = set.getElementsByTagName("directory").item(0).getTextContent();
            boolean packaged = "true".equals(set.getAttribute("packaged"));
            try (Stream<Path> s = Files.walk(templates.resolve(dir))) {
                for (Path f : s.filter(Files::isRegularFile).toList()) {
                    String rel = templates.resolve(dir).relativize(f).toString();
                    String to = dir + "/" + (packaged ? props.get("package").replace('.', '/') + "/" : "") + rel;
                    for (Map.Entry<String, String> p : props.entrySet()) {
                        to = to.replace("__" + p.getKey() + "__", p.getValue());
                    }
                    write(project.resolve(to), fill(Files.readString(f), props));
                }
            }
        }
        try (Stream<Path> s = Files.walk(project)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                assertThat(Files.readString(f)).as(f.toString()).doesNotContain("${");
            }
        }
        return project;
    }

    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z]+)(\\.toUpperCase\\(\\))?}");

    private static String fill(String text, Map<String, String> props) {
        Matcher m = VAR.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = props.get(m.group(1));
            if (v == null) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                continue;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(2) == null ? v : v.toUpperCase(java.util.Locale.ROOT)));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ---- the build: compile against the SDK (the kernel API and Spring, as `provided`), test sources too, then the jar ----------------

    private Path build(Path project, String artifactId) throws IOException {
        Path classes = project.resolve("target/classes");
        compile(project.resolve("src/main/java"), classes, classPath());
        compile(project.resolve("src/test/java"), project.resolve("target/test-classes"), classPath() + File.pathSeparator + classes);
        Path jar = project.resolve("target/" + artifactId + "-0.1.0.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar)); Stream<Path> s = Files.walk(classes)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(p).toString().replace(File.separatorChar, '/')));
                Files.copy(p, out);
                out.closeEntry();
            }
        }
        return jar;
    }

    private static void compile(Path sources, Path classes, String classPath) throws IOException {
        List<String> args = new ArrayList<>(List.of("-d", classes.toString(), "-cp", classPath, "-proc:none", "-nowarn", "--release", "21"));
        try (Stream<Path> s = Files.walk(sources)) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString()));
        }
        Files.createDirectories(classes);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertThat(javac.run(null, null, null, args.toArray(String[]::new))).as("the generated module compiles: " + sources).isZero();
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The test class path, with the manifest-only jar surefire may use expanded. */
    private static String classPath() {
        List<String> out = new ArrayList<>();
        for (String e : System.getProperty("java.class.path").split(File.pathSeparator)) {
            out.add(e);
            if (e.endsWith(".jar") && e.contains("surefire")) {
                try (JarFile j = new JarFile(e)) {
                    String cp = j.getManifest() == null ? null : j.getManifest().getMainAttributes().getValue("Class-Path");
                    if (cp != null) {
                        for (String u : cp.split(" ")) {
                            if (!u.isBlank()) {
                                out.add(Path.of(URI.create(u)).toString());
                            }
                        }
                    }
                } catch (IOException | IllegalArgumentException ignored) {
                    // not a manifest-only jar
                }
            }
        }
        return String.join(File.pathSeparator, out);
    }
}
