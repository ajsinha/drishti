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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * A small module built in tests: a manifest, a jar compiled from Java source at test time (a Spring configuration with one bean, using
 * the kernel API), a console plugin of format 2 with two routes, data needs; packed to a {@code .dmod} and installed (unpacked into
 * {@code <modules>/<id>/<version>/} with the package beside it).
 */
final class TestModule {

    final String id;
    String version = "1.0.0";
    String kernel = ">=0.1 <1.0";
    String prefix;
    String requires = "";
    String conflicts = "";
    String extraManifest = "";
    String functions = "[{word: HELLO}]";
    boolean java = true;
    String spring;
    String alias;
    final Map<String, String> extraFiles = new LinkedHashMap<>();
    final Map<String, String> extraJarClasses = new LinkedHashMap<>();

    TestModule(String id) {
        this.id = id;
        this.prefix = id;
        this.alias = "/" + id + "-short";
    }

    /** Builds the module folder, packs the .dmod, installs it under the modules folder; returns the installed folder. */
    Path install(Path work, Path modules) {
        Path src = work.resolve("src-" + id + "-" + version);
        Dmod.write(src.resolve("module.yaml"), manifest());
        Dmod.write(src.resolve("console/plugin.yaml"), """
                format: 2
                routes:
                  - {id: greet, path: "/greet/{name}", page: greet.html, function: HELLO, alias: "%s"}
                  - {id: old, path: /old, moved: greet}
                """.formatted(alias));
        Dmod.write(src.resolve("data/needs.yaml"), "needs:\n  - {kind: greeting, contract: \">=1.0\"}\n");
        extraFiles.forEach((p, text) -> Dmod.write(src.resolve(p), text));
        if (java) {
            jar(work, src.resolve("java/" + id + ".jar"));
        }
        Path dmod = Dmod.pack(src, work.resolve(Dmod.fileName(id, version)));
        Path dir = modules.resolve(id).resolve(version);
        Dmod.unpack(dmod, dir);
        try {
            Files.copy(dmod, modules.resolve(id).resolve(dmod.getFileName()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return dir;
    }

    String manifest() {
        String pkg = "test." + id.replace('-', '_');
        return """
                format: 1
                id: %s
                version: %s
                title: Test %s
                about: A module built in tests
                domain: testing
                publisher: {name: Drishti tests, id: drishti-tests}
                licence: {feature: %s}
                kernel: "%s"
                rachana: ">=1.0"
                contracts: ">=1.0"
                plugin-api: ">=2.0"
                elements: ">=1.4 <2"
                requires: {%s}
                conflicts: [%s]
                provides: {kinds: {%s-pack: [greeting-%s]}}
                api: {prefix: %s}
                console: {plugin: console/plugin.yaml}
                data: {needs: data/needs.yaml}
                functions: %s
                resources: {memory-mb: 64}
                """.formatted(id, version, id, id, kernel, requires, conflicts, id, id, prefix, functions)
                + (java ? "java: {jars: [" + id + ".jar], spring: [" + (spring == null ? pkg + ".HelloConfig" : spring) + "]}\n" : "") + extraManifest;
    }

    private void jar(Path work, Path jar) {
        String pkg = "test." + id.replace('-', '_');
        Path srcDir = work.resolve("java-" + id + "-" + version);
        Path classes = srcDir.resolve("classes");
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(pkg.replace('.', '/') + "/HelloConfig.java", """
                package %s;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import com.ash.drishti.kernel.api.modules.ApiAlias;
                @Configuration
                public class HelloConfig {
                    @Bean public String greeting() { return "hello from %s"; }
                    @Bean public ApiAlias helloAlias() { return new ApiAlias("/api/v1/old-%s", "/api/v1/%s"); }
                    @Bean public Greeter greeter() { return new Greeter(); }
                }
                """.formatted(pkg, id, id, id));
        sources.put(pkg.replace('.', '/') + "/Greeter.java", """
                package %s;
                public class Greeter implements java.util.function.Supplier<String> {
                    public String get() { return "greeter of %s"; }
                }
                """.formatted(pkg, id));
        sources.putAll(extraJarClasses);
        List<String> files = new ArrayList<>();
        sources.forEach((p, text) -> {
            Dmod.write(srcDir.resolve(p), text);
            files.add(srcDir.resolve(p).toString());
        });
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<String> args = new ArrayList<>(List.of("-d", classes.toString(), "-cp", classPath(), "-proc:none", "-nowarn"));
        args.addAll(files);
        if (javac.run(null, null, null, args.toArray(String[]::new)) != 0) {
            throw new IllegalStateException("the test module's sources do not compile");
        }
        Dmod.write(jar, "");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar)); Stream<Path> s = Files.walk(classes)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(p).toString().replace(File.separatorChar, '/')));
                Files.copy(p, out);
                out.closeEntry();
            }
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
