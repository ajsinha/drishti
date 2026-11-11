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
package com.ash.drishti.server.modules.deploy;

import com.ash.drishti.module.runtime.Dmod;
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
 * The runtime tests' small module (drishti-module-runtime's TestModule), grown to pass the fifteen conformance checks so the module store
 * takes it: a README, Help, a guide and a tutorial, a contract per kind, a page per route, and an {@code @ModuleApi} controller that answers
 * {@code GET /api/v1/<id>/hello/{name}} with its version.
 */
final class DeployTestModule {

    final String id;
    final String version;
    final String word;

    DeployTestModule(String id, String version, String word) {
        this.id = id;
        this.version = version;
        this.word = word;
    }

    /** Builds and packs the module; returns the .dmod. */
    Path pack(Path work) {
        Path src = work.resolve("src-" + id + "-" + version);
        String pkg = "test." + id.replace('-', '_');
        Dmod.write(src.resolve("module.yaml"), """
                format: 1
                id: %1$s
                version: %2$s
                title: Test %1$s
                about: A module built in the deployment tests
                domain: testing
                publisher: {name: Drishti tests, id: drishti-tests}
                licence: {feature: %1$s}
                kernel: ">=0.1 <99.0"
                rachana: ">=1.0"
                contracts: ">=1.0"
                plugin-api: ">=2.0"
                elements: ">=1.4 <2"
                provides: {kinds: {%1$s-pack: [greeting-%1$s]}}
                api: {prefix: %1$s}
                console: {plugin: console/plugin.yaml}
                data: {needs: data/needs.yaml}
                functions: [{word: %3$s}]
                resources: {memory-mb: 64}
                docs: {readme: docs/README.md, help: docs/help.md, guides: [docs/guide.md], tutorials: [docs/tutorial.md]}
                java: {jars: [%1$s.jar], spring: [%4$s.HelloConfig]}
                """.formatted(id, version, word, pkg));
        Dmod.write(src.resolve("console/plugin.yaml"), """
                format: 2
                routes:
                  - {id: greet, path: "/greet/{name}", page: greet.html, function: %s}
                """.formatted(word));
        Dmod.write(src.resolve("console/pages/greet.html"), "<section><h1>Greeting</h1></section>\n");
        Dmod.write(src.resolve("data/needs.yaml"), "needs:\n  - {kind: greeting-" + id + ", contract: \">=1.0 <2\"}\n");
        Dmod.write(src.resolve("packs/" + id + "-pack/contracts/greeting-" + id + ".yaml"),
                "contract-format: 1\nkind: greeting-" + id + "\nbinding-hint: any table of greetings\n");
        Dmod.write(src.resolve("docs/README.md"), "---\ntitle: Test " + id + "\ndomain: testing\nsummary: a test module\n---\n\n# Test " + id + "\n");
        Dmod.write(src.resolve("docs/help.md"), "# Help\n\nSays hello.\n");
        Dmod.write(src.resolve("docs/guide.md"), "# Guide\n\nSays hello.\n");
        Dmod.write(src.resolve("docs/tutorial.md"), "# Tutorial\n\nSay hello.\n");
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(pkg.replace('.', '/') + "/HelloConfig.java", """
                package %s;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                @Configuration
                public class HelloConfig {
                    @Bean public HelloApi helloApi() { return new HelloApi(); }
                }
                """.formatted(pkg));
        sources.put(pkg.replace('.', '/') + "/HelloApi.java", """
                package %1$s;
                import com.ash.drishti.kernel.api.module.ModuleApi;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                @ModuleApi("%2$s")
                public class HelloApi {
                    @GetMapping("/hello/{name}")
                    public java.util.Map<String, Object> hello(@PathVariable("name") String name) {
                        return java.util.Map.of("module", "%2$s", "version", "%3$s", "hello", name);
                    }
                }
                """.formatted(pkg, id, version));
        jar(work.resolve("java-" + id + "-" + version), sources, src.resolve("java/" + id + ".jar"));
        return Dmod.pack(src, work.resolve(Dmod.fileName(id, version)));
    }

    private static void jar(Path srcDir, Map<String, String> sources, Path jar) {
        Path classes = srcDir.resolve("classes");
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
