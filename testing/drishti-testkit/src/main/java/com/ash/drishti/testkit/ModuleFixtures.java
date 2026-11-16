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
package com.ash.drishti.testkit;

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
import java.util.Locale;
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
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Modules built in tests, the way a publisher builds them: a project generated from the SDK's archetype templates (as
 * {@code mvn archetype:generate} would), Java sources compiled against the test class path (the kernel API and Spring, as
 * {@code provided}) and packed into jars. The {@code .dmod} itself is packed by the module runtime's one packer.
 */
public final class ModuleFixtures {

    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z]+)(\\.toUpperCase\\(\\))?}");

    private ModuleFixtures() {
    }

    /**
     * Generates a project from the archetype's resources ({@code drishti-sdk/drishti-module-archetype/src/main/resources}) into
     * {@code project}: the descriptor's file sets, packaged Java, the properties filled ({@code groupId com.example.<id>}, the rest the
     * descriptor's defaults).
     */
    public static Path generate(Path archetype, String artifactId, Path project) {
        try {
            Map<String, String> props = new LinkedHashMap<>();
            props.put("groupId", "com.example." + artifactId);
            props.put("artifactId", artifactId);
            props.put("package", "com.example." + artifactId);
            var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(archetype.resolve("META-INF/maven/archetype-metadata.xml").toFile());
            NodeList required = doc.getElementsByTagName("requiredProperty");
            for (int i = 0; i < required.getLength(); i++) {
                Element e = (Element) required.item(i);
                if (e.getElementsByTagName("defaultValue").getLength() > 0) {
                    props.putIfAbsent(e.getAttribute("key"), e.getElementsByTagName("defaultValue").item(0).getTextContent());
                }
            }
            Path templates = archetype.resolve("archetype-resources");
            write(project.resolve("pom.xml"), fill(Files.readString(templates.resolve("pom.xml")), props));
            NodeList sets = doc.getElementsByTagName("fileSet");
            for (int i = 0; i < sets.getLength(); i++) {
                Element set = (Element) sets.item(i);
                String dir = set.getElementsByTagName("directory").item(0).getTextContent();
                boolean packaged = "true".equals(set.getAttribute("packaged"));
                try (Stream<Path> s = Files.walk(templates.resolve(dir))) {
                    for (Path f : s.filter(Files::isRegularFile).toList()) {
                        String rel = templates.resolve(dir).relativize(f).toString().replace(File.separatorChar, '/');
                        String to = dir + "/" + (packaged ? props.get("package").replace('.', '/') + "/" : "") + rel;
                        for (Map.Entry<String, String> p : props.entrySet()) {
                            to = to.replace("__" + p.getKey() + "__", p.getValue());
                        }
                        write(project.resolve(to), fill(Files.readString(f), props));
                    }
                }
            }
            return project;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            throw new IllegalStateException("the archetype does not generate: " + e.getMessage(), e);
        }
    }

    /** Compiles every Java file under {@code sources} (with {@code extraClassPath} after the test class path) into a jar. */
    public static Path jarOf(Path sources, String extraClassPath, Path work, Path jar) {
        List<String> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(sources)) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add(p.toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return compile(files, extraClassPath, work, jar);
    }

    /** Compiles the sources given as {@code path -> text} ({@code test/x/Hello.java}) into a jar. */
    public static Path jarOf(Map<String, String> sources, String extraClassPath, Path work, Path jar) {
        Path src = work.resolve("src");
        List<String> files = new ArrayList<>();
        sources.forEach((p, text) -> {
            write(src.resolve(p), text);
            files.add(src.resolve(p).toString());
        });
        return compile(files, extraClassPath, work, jar);
    }

    private static Path compile(List<String> files, String extraClassPath, Path work, Path jar) {
        Path classes = work.resolve("classes");
        try {
            Files.createDirectories(classes);
            String cp = classPath() + (extraClassPath == null || extraClassPath.isBlank() ? "" : File.pathSeparator + extraClassPath);
            List<String> args = new ArrayList<>(List.of("-d", classes.toString(), "-cp", cp, "-proc:none", "-nowarn", "--release", "21"));
            args.addAll(files);
            JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            if (javac.run(null, null, null, args.toArray(String[]::new)) != 0) {
                throw new IllegalStateException("the module's sources do not compile: " + files);
            }
            Files.createDirectories(jar.toAbsolutePath().getParent());
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar)); Stream<Path> s = Files.walk(classes)) {
                for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                    out.putNextEntry(new JarEntry(classes.relativize(p).toString().replace(File.separatorChar, '/')));
                    Files.copy(p, out);
                    out.closeEntry();
                }
            }
            return jar;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The test class path, with the manifest-only jar surefire may use expanded. */
    public static String classPath() {
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

    private static String fill(String text, Map<String, String> props) {
        Matcher m = VAR.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = props.get(m.group(1));
            if (v == null) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                continue;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(2) == null ? v : v.toUpperCase(Locale.ROOT)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Writes a text file, its folders made. */
    public static void write(Path file, String text) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
