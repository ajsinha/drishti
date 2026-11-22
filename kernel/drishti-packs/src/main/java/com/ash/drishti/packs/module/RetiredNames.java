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
package com.ash.drishti.packs.module;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The one place a retired setting name stops the server: a site that still sets a name the product no longer reads (an engine's old switch, a module
 * setting's old server key) is told, at start, which new key replaces it. Nothing is read from the old name and nothing is warned about and carried on.
 *
 * <p>{@link #in} is the one rule of where a site sets a name, for every reader of retired names: every settings source the engines and modules
 * read counts (the command line, system properties, the environment, application.local.yaml, the packs overlay, the edition, the packs, the
 * site's {@code config/engines/<engine>.yaml}) except the product's own copies (the jar's {@code application.yaml} and engine files, a module's
 * shipped engine files) and Spring's attached view over all of them. An environment variable counts under each name Spring binds to the old key
 * ({@code DRISHTI_VAR_RANK_RULE}, {@code DRISHTI_VAR_RANKRULE}) unless a product or site file names it as a placeholder
 * ({@code ${DRISHTI_VAR_SCENARIO_WINDOW:250}}): then it sets the new key, by that file.
 */
public final class RetiredNames {

    private RetiredNames() {
    }

    /** One line of the refusal: the retired name, where it is set, and the key to use instead. */
    public static String line(String oldName, String where, String newKey) {
        return "  " + oldName + (where == null || where.isBlank() ? "" : " (set in " + where + ")") + " is retired: use " + newKey;
    }

    /** The refusal's text, or null when no name is retired. */
    public static String message(List<String> lines) {
        return lines.isEmpty() ? null : "the server cannot start: settings names that were retired are still set; rename each one:\n" + String.join("\n", lines);
    }

    /** Stops the start (IllegalStateException with {@link #message}) when {@code lines} is not empty. */
    public static void refuse(List<String> lines) {
        String m = message(lines);
        if (m != null) {
            throw new IllegalStateException(m);
        }
    }

    /** The prefix of a module's shipped engine file as the module host adds it to the settings (EngineSwitches.moduleCopy). */
    public static final String MODULE_COPY = "module shipped copy ";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)[:}]");
    private static final Pattern ENGINE_ENTRY = Pattern.compile("(BOOT-INF/classes/)?engines/[a-z0-9-]+\\.ya?ml");

    /** The product's own files: the jar's application.yaml and engine files, a module's shipped engine files. Never the site's. */
    public static boolean shipped(PropertySource<?> ps) {
        String n = ps.getName();
        return n.startsWith("Config resource") && n.contains("class path resource") || n.startsWith(MODULE_COPY);
    }

    /** The check of where a site sets a retired name, over {@code env} as it is now, with the placeholder names of the modules' shipped engine files in {@code moduleDirs}. */
    public static Check in(ConfigurableEnvironment env, Collection<Path> moduleDirs) {
        return new Check(env, () -> placeholders(env, moduleDirs));
    }

    /** {@link #in} with no module folder beyond the settings already in {@code env}. */
    public static Check in(ConfigurableEnvironment env) {
        return in(env, List.of());
    }

    /** Where a site sets a retired name: the source's name, or {@code environment NAME}. */
    public static final class Check {
        private final ConfigurableEnvironment env;
        private final java.util.function.Supplier<Set<String>> read;
        private Set<String> placeholders;      // read once, and only when an environment variable could be an old name

        Check(ConfigurableEnvironment env, java.util.function.Supplier<Set<String>> placeholders) {
            this.env = env;
            this.read = placeholders;
        }

        private Set<String> placeholders() {
            if (placeholders == null) {
                placeholders = read.get();
            }
            return placeholders;
        }

        /** The name of the first source that sets {@code key} (a site's, not the product's), or null. */
        public String whereSet(String key) {
            for (PropertySource<?> ps : env.getPropertySources()) {
                String n = ps.getName();
                if ("configurationProperties".equals(n) || n.startsWith(ModuleSettingsEnvironmentPostProcessor.SOURCE) || shipped(ps)) {
                    continue;
                }
                if (StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME.equals(n)) {
                    String v = environment(ps, key);
                    if (v != null) {
                        return "environment " + v;
                    }
                    continue;
                }
                Object v = ps.containsProperty(key) ? ps.getProperty(key) : null;
                if (v != null && !v.toString().isBlank()) {
                    return n;
                }
            }
            return null;
        }

        /** The environment variable that Spring binds to {@code key} and that no file names as a placeholder, or null. */
        private String environment(PropertySource<?> ps, String key) {
            if (!(ps.getSource() instanceof Map<?, ?> vars)) {
                return null;
            }
            String upper = key.toUpperCase(Locale.ROOT);
            for (String name : List.of(upper.replace('.', '_').replace('-', '_'), upper.replace("-", "").replace('.', '_'))) {
                Object v = vars.get(name);
                if (v != null && !v.toString().isBlank() && !placeholders().contains(name)) {
                    return name;
                }
            }
            return null;
        }
    }

    /** Every placeholder name the settings' own sources use, and the modules' shipped engine files (in their jars). */
    static Set<String> placeholders(ConfigurableEnvironment env, Collection<Path> moduleDirs) {
        Set<String> out = new HashSet<>();
        for (PropertySource<?> ps : env.getPropertySources()) {
            String n = ps.getName();
            if ("configurationProperties".equals(n) || StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME.equals(n)
                    || !(ps instanceof EnumerablePropertySource<?> e)) {
                continue;
            }
            for (String k : e.getPropertyNames()) {
                Object v = e.getProperty(k);
                if (v != null) {
                    names(v.toString(), out);
                }
            }
        }
        for (Path dir : moduleDirs) {
            if (dir == null || !Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(dir, 3)) {
                for (Path jar : s.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
                    try (ZipFile z = new ZipFile(jar.toFile())) {
                        for (ZipEntry en : java.util.Collections.list(z.entries())) {
                            if (ENGINE_ENTRY.matcher(en.getName()).matches()) {
                                try (InputStream in = z.getInputStream(en)) {
                                    names(new String(in.readAllBytes(), StandardCharsets.UTF_8), out);
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("the module folder " + dir + " cannot be read for its engine files: " + e.getMessage(), e);
            }
        }
        return out;
    }

    private static void names(String text, Set<String> out) {
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
    }
}
