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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Guard G10 (I7A_IMPACT.md section 7, R4): the kernel's libraries always win. A module carrying its own copy of a library the kernel
 * ships loads, its copy is never used (the class is the kernel's), and the module is warned of it by name and both versions, in the
 * verifier's notes ({@code module verify}), the load log (WARN) and its Health row. A site jar in {@code lib/ext} wins over a module's copy;
 * a library the kernel does not ship comes from the module's own jars; a resource in both (a {@code META-INF/services} file) is the
 * kernel's first; kernel internals stay hidden.
 */
class KernelLibrariesWinTest {

    private static final String SERVICES = "META-INF/services/g10.Probe";

    @TempDir
    Path tmp;

    /** The kernel's list as the build writes it, with a newer Jackson than the module carries. */
    private static KernelLibraries fixtureList() {
        return KernelLibraries.parse("""
                # fixture
                com.fasterxml.jackson.core:jackson-databind:2.21.4 jackson-databind-2.21.4.jar com.fasterxml.jackson.databind
                org.yaml:snakeyaml:2.4 snakeyaml-2.4.jar org.yaml.snakeyaml
                """);
    }

    @Test
    void theKernelsLibrariesWinAndAModuleCopyIsNamed() throws Exception {
        Path jackson = jacksonCopy(tmp.resolve("jackson-databind-2.17.1.jar"));
        Path site = compiledJar(tmp.resolve("site"), "org/site/Shared.java",
                "package org.site; public class Shared { public String toString() { return \"site\"; } }");

        TestModule g10 = new TestModule("gten");
        g10.libJars.put("jackson-databind-2.17.1.jar", jackson);
        g10.extraJarClasses.put("org/site/Shared.java", "package org.site; public class Shared { public String toString() { return \"module\"; } }");
        g10.extraJarClasses.put("org/acme/Private.java", "package org.acme; public class Private { }");
        g10.extraJarResources.put(SERVICES, "module\n");
        Path dir = g10.install(tmp, tmp.resolve("modules"));
        Files.writeString(Files.createDirectories(tmp.resolve("kernel-res/META-INF/services")).resolve("g10.Probe"), "kernel\n");
        Files.writeString(tmp.resolve("kernel-res/META-INF/services/g10.Internal"), "# a kernel provider\n" + ModuleRuntime.class.getName() + "\n");

        KernelFacts facts = KernelFacts.ofKernel().withLibraries(fixtureList());
        String sentence = "gten 1.0.0 carries jackson-databind 2.17.1 (jackson-databind-2.17.1.jar); the kernel's jackson-databind 2.21.4 is used "
                + "instead. Remove the jar from the module and compile against the kernel's (scope provided).";

        // module verify: the verifier's notes carry the sentence
        ModuleCandidate c = new ModuleCandidate(ModuleFolder.manifest(dir), dir, tmp.resolve("modules/gten").resolve(Dmod.fileName("gten", "1.0.0")));
        assertThat(new ModuleVerifier(facts).verify(c)).contains(ModuleVerifier.LIBRARY_WARNING + sentence);

        Logger log = (Logger) LoggerFactory.getLogger(ModuleRuntime.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        log.addAppender(logged);
        try (URLClassLoader kernel = new URLClassLoader("g10-kernel", new URL[] {tmp.resolve("kernel-res").toUri().toURL()},
                ModuleRuntime.class.getClassLoader());
                URLClassLoader lib = new URLClassLoader("g10-lib", new URL[] {site.toUri().toURL()}, kernel)) {
            ModuleRuntime rt = new ModuleRuntime(facts, null, kernel, null, tmp.resolve("tombstones")).libraries(lib);
            assertThat(rt.start(List.of(tmp.resolve("modules")), Map.of(), id -> true)).extracting(ModuleHealth::state)
                    .containsExactly(ModuleHealth.State.LOADED);
            // the load log and the Health row name it
            assertThat(logged.list).anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN && e.getFormattedMessage().contains(sentence));
            assertThat(rt.health("gten").orElseThrow().notes()).contains(ModuleVerifier.LIBRARY_WARNING + sentence);
            look(rt, kernel, lib);
            assertThat(rt.unload("gten", ModuleRuntime.Leave.DISABLE, true).leakFree()).isTrue();
            rt.close();
        } finally {
            log.detachAppender(logged);
        }
    }

    /** In a frame of its own, so nothing of the module stays reachable for the leak check. */
    private static void look(ModuleRuntime rt, ClassLoader kernel, ClassLoader lib) throws Exception {
        ModuleClassLoader m = rt.loaded("gten").orElseThrow().loader();
        // the module's copy of Jackson is never used: the class is the kernel's
        assertThat(m.loadClass(ObjectMapper.class.getName())).isSameAs(ObjectMapper.class);
        // a site jar (lib/ext) wins over the module's copy
        Class<?> shared = m.loadClass("org.site.Shared");
        assertThat(shared.getClassLoader()).isSameAs(lib);
        assertThat(shared.getConstructor().newInstance()).hasToString("site");
        // a library the kernel does not ship comes from the module's own jars
        assertThat(m.loadClass("org.acme.Private").getClassLoader()).isSameAs(m);
        // kernel internals stay invisible
        assertThatThrownBy(() -> m.loadClass(ModuleRuntime.class.getName())).isInstanceOf(ClassNotFoundException.class);
        // a resource in both: the kernel's first, then the module's
        List<URL> urls = Collections.list(m.getResources(SERVICES));
        assertThat(urls).hasSize(2);
        assertThat(text(urls.get(0))).isEqualTo("kernel");
        assertThat(text(urls.get(1))).isEqualTo("module");
        assertThat(text(m.getResource(SERVICES))).isEqualTo("kernel");
        assertThat(kernel.getResource(SERVICES)).isNotNull();
        // a kernel service file naming a kernel internal is not handed on (a ServiceLoader with the module's loader could not load it)
        assertThat(kernel.getResource("META-INF/services/g10.Internal")).isNotNull();
        assertThat(Collections.list(m.getResources("META-INF/services/g10.Internal"))).isEmpty();
        assertThat(m.getResource("META-INF/services/g10.Internal")).isNull();
    }

    @Test
    void aModuleWithoutACopyHasNoWarningAndNoListWarnsOfNothing() {
        Path dir = new TestModule("clean").install(tmp, tmp.resolve("modules"));
        ModuleCandidate c = new ModuleCandidate(ModuleFolder.manifest(dir), dir, null);
        assertThat(fixtureList().warnings(c)).isEmpty();
        assertThat(KernelLibraries.none().known()).isFalse();
    }

    @Test
    void aJarWithoutCoordinatesIsMatchedByNameThenByPackage() throws Exception {
        KernelLibraries list = fixtureList();
        Path byName = Files.createDirectories(tmp.resolve("n")).resolve("snakeyaml-1.33.jar");
        jar(byName, Map.of("org/yaml/snakeyaml/Yaml.class", new byte[] {0}));
        assertThat(list.overlaps(byName)).singleElement().satisfies(o -> {
            assertThat(o.artifact()).isEqualTo("snakeyaml");
            assertThat(o.version()).isEqualTo("1.33");
            assertThat(o.kernel().version()).isEqualTo("2.4");
        });
        Path repacked = tmp.resolve("n/yaml-shaded.jar");
        jar(repacked, Map.of("org/yaml/snakeyaml/nodes/Node.class", new byte[] {0}));
        assertThat(list.overlaps(repacked)).extracting(KernelLibraries.Overlap::artifact).containsExactly("snakeyaml");
        Path own = tmp.resolve("n/own.jar");
        jar(own, Map.of("org/yamlish/Other.class", new byte[] {0}, "com/ash/drishti/compute/banking/X.class", new byte[] {0}));
        assertThat(list.overlaps(own)).isEmpty();
    }

    @Test
    void generationFromADependencyListAndTheCheckAgainstBootInfLib() throws Exception {
        Path a = tmp.resolve("repo/a-1.0.jar");
        Files.createDirectories(a.getParent());
        jar(a, Map.of("org/a/one/X.class", new byte[] {0}, "org/a/one/deep/Y.class", new byte[] {0}, "org/a/two/Z.class", new byte[] {0}));
        Path b = tmp.resolve("repo/b-2.0-linux.jar");
        jar(b, Map.of("org/b/B.class", new byte[] {0}));
        List<String> listing = List.of("", "The following files have been resolved:",
                "   org.a:a:jar:1.0:compile:" + a + "\u001B[36m -- module a\u001B[0;1;33m (auto)\u001B[m",
                "   org.b:b:jar:linux:2.0:runtime:" + b,
                "   org.c:c:jar:3.0:test:" + b);
        List<KernelLibraries.Library> libs = KernelLibraries.fromDependencyList(listing);
        assertThat(libs).extracting(KernelLibraries.Library::jar).containsExactly("a-1.0.jar", "b-2.0-linux.jar");
        assertThat(libs.get(0).prefixes()).containsExactly("org.a.one", "org.a.two");
        Path txt = tmp.resolve("kernel-libraries.txt");
        Files.writeString(txt, KernelLibraries.format(libs));
        assertThat(KernelLibraries.parse(Files.readString(txt)).all()).isEqualTo(libs);

        Path exec = tmp.resolve("server-exec.jar");
        jar(exec, Map.of("BOOT-INF/lib/a-1.0.jar", new byte[] {0}, "BOOT-INF/lib/b-2.0-linux.jar", new byte[] {0},
                "BOOT-INF/lib/spring-boot-jarmode-tools-3.5.0.jar", new byte[] {0}));
        KernelLibraries.main(new String[] {"check", exec.toString(), txt.toString()});
        Path other = tmp.resolve("other-exec.jar");
        jar(other, Map.of("BOOT-INF/lib/a-1.0.jar", new byte[] {0}, "BOOT-INF/lib/extra-1.jar", new byte[] {0}));
        assertThatThrownBy(() -> KernelLibraries.main(new String[] {"check", other.toString(), txt.toString()}))
                .hasMessageContaining("not listed [extra-1.jar]").hasMessageContaining("listed but not there [b-2.0-linux.jar]");
    }

    // ---- fixtures ----

    /** A module's own copy of Jackson: the kernel's ObjectMapper class, under jackson-databind 2.17.1's coordinates. */
    private static Path jacksonCopy(Path jar) throws IOException {
        byte[] klass;
        try (InputStream in = ObjectMapper.class.getResourceAsStream("ObjectMapper.class")) {
            klass = in.readAllBytes();
        }
        jar(jar, Map.of("META-INF/maven/com.fasterxml.jackson.core/jackson-databind/pom.properties",
                "groupId=com.fasterxml.jackson.core\nartifactId=jackson-databind\nversion=2.17.1\n".getBytes(),
                "com/fasterxml/jackson/databind/ObjectMapper.class", klass));
        return jar;
    }

    private static Path compiledJar(Path work, String path, String source) throws IOException {
        Path src = work.resolve("src").resolve(path);
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        Path classes = work.resolve("classes");
        List<String> args = new ArrayList<>(List.of("-d", classes.toString(), "-proc:none", "-nowarn", src.toString()));
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new))).isZero();
        Path jar = work.resolve("site.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar)); Stream<Path> s = Files.walk(classes)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(p).toString().replace(File.separatorChar, '/')));
                Files.copy(p, out);
                out.closeEntry();
            }
        }
        return jar;
    }

    private static void jar(Path jar, Map<String, byte[]> entries) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String n : Set.copyOf(entries.keySet()).stream().sorted().toList()) {
                out.putNextEntry(new JarEntry(n));
                out.write(entries.get(n));
                out.closeEntry();
            }
        }
    }

    private static String text(URL u) throws IOException {
        try (InputStream in = u.openStream()) {
            return new String(in.readAllBytes()).strip();
        }
    }
}
