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
package com.ash.drishti.server.playground;

import com.ash.drishti.common.Branding;
import com.ash.drishti.server.dist.XlsxWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * A playground's workbook template (docs/guides/VAR_GUIDE.md section 5), kept in the repository as text: {@code template.properties}
 * (its name, version and the cell formats by name), {@code xl/styles.xml} (the look), {@code docProps/core.xml} and {@code docProps/app.xml}
 * (the document's properties, with {@code {{PRODUCT}}}, {@code {{COPYRIGHT}}}, {@code {{OWNER}}} placeholders filled from the branding
 * configuration) and {@code explain.txt} (the words of the Explain tab). The sheets and their formulas are generated for the count asked
 * ({@link WorkbookStream}); a template holds the look and the words only.
 *
 * <p>Read from the classpath (the built-in one, {@code playground/<metric>/template/}) or from a folder (a bank's own house style,
 * {@code drishti.var.playground.template}); either must name every style the metric uses, or it is refused with the list of the missing.
 */
public final class PlaygroundTemplate {

    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->\\s*", Pattern.DOTALL);
    static final List<String> PARTS = List.of("template.properties", "xl/styles.xml", "docProps/core.xml", "docProps/app.xml", "explain.txt");

    private final String name;
    private final String version;
    private final Map<String, Integer> styles;
    private final Map<String, Integer> dxfs;
    private final Map<String, String> parts;
    private final String origin;

    private PlaygroundTemplate(String origin, Map<String, String> parts, Collection<String> requiredStyles) {
        this.origin = origin;
        this.parts = parts;
        Properties p = new Properties();
        try {
            p.load(new StringReader(parts.get("template.properties")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.name = p.getProperty("name", "playground");
        this.version = p.getProperty("version", "0");
        Map<String, Integer> s = new LinkedHashMap<>();
        Map<String, Integer> d = new LinkedHashMap<>();
        for (String k : new TreeSet<>(p.stringPropertyNames())) {
            if (k.startsWith("style.")) {
                s.put(k.substring(6), Integer.parseInt(p.getProperty(k).strip()));
            } else if (k.startsWith("dxf.")) {
                d.put(k.substring(4), Integer.parseInt(p.getProperty(k).strip()));
            }
        }
        this.styles = Map.copyOf(s);
        this.dxfs = Map.copyOf(d);
        List<String> missing = new ArrayList<>();
        for (String r : requiredStyles) {
            if (!styles.containsKey(r)) {
                missing.add("style." + r);
            }
        }
        for (String r : List.of("pass", "fail")) {
            if (!dxfs.containsKey(r)) {
                missing.add("dxf." + r);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("the playground template " + origin + " lacks " + missing + " (every name of the built-in "
                    + "template.properties must be kept)");
        }
    }

    /**
     * The built-in template of a metric, {@code playground/<metric>/template/} in the jars of the class that asks (a module's own, when the
     * metric is a module's), else the server's.
     */
    public static PlaygroundTemplate builtIn(String metric, Collection<String> requiredStyles) {
        ClassLoader caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass().getClassLoader();
        String base = "playground/" + metric + "/template/";
        Map<String, String> parts = new LinkedHashMap<>();
        for (String part : PARTS) {
            InputStream found = caller == null ? null : caller.getResourceAsStream(base + part);
            try (InputStream in = found != null ? found : PlaygroundTemplate.class.getClassLoader().getResourceAsStream(base + part)) {
                if (in == null) {
                    throw new IllegalStateException("the built-in playground template has no " + base + part);
                }
                parts.put(part, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new PlaygroundTemplate("built-in " + metric, parts, requiredStyles);
    }

    /** A template in a folder (a bank's own); refused in words when a part or a style name is missing. */
    public static PlaygroundTemplate folder(Path dir, Collection<String> requiredStyles) {
        Map<String, String> parts = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String part : PARTS) {
            Path f = dir.resolve(part);
            if (!Files.isRegularFile(f)) {
                missing.add(part);
                continue;
            }
            try {
                parts.put(part, Files.readString(f, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("the playground template " + dir + " lacks " + missing);
        }
        return new PlaygroundTemplate(dir.toString(), parts, requiredStyles);
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    /** Where it was read from ({@code built-in var} or a folder). */
    public String origin() {
        return origin;
    }

    /** A cell format's index by name. */
    public int style(String n) {
        Integer s = styles.get(n);
        if (s == null) {
            throw new IllegalArgumentException("no style " + n + " in the playground template " + origin);
        }
        return s;
    }

    /** A differential format's index ({@code pass}, {@code fail}). */
    public int dxf(String n) {
        return dxfs.get(n);
    }

    /** A part as written into the workbook: comments dropped, placeholders filled ({@code {{KEY}}} from {@code values}, XML-escaped). */
    public String part(String part, Map<String, String> values) {
        String s = COMMENT.matcher(parts.get(part)).replaceAll("");
        for (Map.Entry<String, String> e : values.entrySet()) {
            s = s.replace("{{" + e.getKey() + "}}", XlsxWriter.esc(e.getValue() == null ? "" : e.getValue()));
        }
        return s;
    }

    /** The Explain tab's steps: each {@code [title, text]} ({@code #} lines dropped). */
    public List<String[]> explain() {
        List<String[]> out = new ArrayList<>();
        String title = null;
        StringBuilder text = new StringBuilder();
        for (String line : parts.get("explain.txt").split("\n")) {
            if (line.startsWith("##")) {
                if (title != null) {
                    out.add(new String[] {title, text.toString().strip()});
                }
                title = line.substring(2).strip();
                text.setLength(0);
            } else if (!line.startsWith("#") && title != null) {
                text.append(line).append(' ');
            }
        }
        if (title != null) {
            out.add(new String[] {title, text.toString().strip()});
        }
        return out;
    }

    /** The placeholders every template part may use, from the branding configuration (never literals). */
    public Map<String, String> brandingValues(Branding b) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("PRODUCT", b.product());
        m.put("COPYRIGHT", b.copyright());
        m.put("OWNER", b.owner());
        m.put("TEMPLATE", name);
        m.put("TEMPLATE_VERSION", version);
        return m;
    }
}
