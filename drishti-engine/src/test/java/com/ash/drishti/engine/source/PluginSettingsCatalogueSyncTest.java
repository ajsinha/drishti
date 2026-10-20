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
package com.ash.drishti.engine.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.SettingSpec;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The connector settings catalogue ({@code plugin-settings.yaml}) cannot drift from the plugin code. Connector plugins have no
 * declared-settings API (the catalogue is that declaration), so the plugin sources are scanned: every key a plugin reads through
 * {@code ctx.setting("key", ...)}, {@code settings.get("key")}, {@code settings.getOrDefault(...)} or a {@code k.startsWith("prefix.")}
 * loop must be declared (an undeclared key draws an "unknown setting" warning in Admin), and a setting declared {@code required} must
 * have no default in the code. The shared {@code tls.*} and {@code proxy.*} sections are generated from their own classes and checked
 * elsewhere.
 */
class PluginSettingsCatalogueSyncTest {

    /** The plugin source folders (relative to the repository root); a messaging connector also runs the shared base class. */
    private static final Map<String, List<String>> SOURCES = Map.ofEntries(
            Map.entry("jdbc", List.of("plugins/drishti-plugin-jdbc")), Map.entry("duckdb", List.of("plugins/drishti-plugin-duckdb")),
            Map.entry("delta", List.of("plugins/drishti-plugin-delta")), Map.entry("iceberg", List.of("plugins/drishti-plugin-iceberg")),
            Map.entry("aerospike", List.of("plugins/drishti-plugin-aerospike")), Map.entry("cassandra", List.of("plugins/drishti-plugin-cassandra")),
            Map.entry("mongodb", List.of("plugins/drishti-plugin-mongodb")),
            Map.entry("redis", List.of("plugins/drishti-plugin-redis")), Map.entry("file", List.of("plugins/drishti-plugin-file")),
            Map.entry("demo", List.of("plugins/drishti-plugin-demo")), Map.entry("feed", List.of("plugins/drishti-plugin-feeds")),
            Map.entry("news", List.of("plugins/drishti-plugin-news")), Map.entry("yfinance", List.of("plugins/drishti-plugin-yfinance")),
            Map.entry("rest", List.of("plugins/drishti-plugin-rest")), Map.entry("s3", List.of("plugins/drishti-plugin-s3")),
            Map.entry("kafka", List.of("plugins/drishti-plugin-kafka")),
            Map.entry("activemq", List.of("plugins/drishti-plugin-activemq", "drishti-messaging")),
            Map.entry("rabbitmq", List.of("plugins/drishti-plugin-rabbitmq", "drishti-messaging")));

    /** Keys the scan meets that are not connector settings (a document field, a loader command line option). */
    private static final Set<String> NOT_SETTINGS = Set.of("name", "https", "http", "id", "doc", "keep-days");

    /** Defaults the code applies without a literal the scan can see (kafka: a blank bootstrap-servers becomes localhost:9092). */
    private static final Map<String, Set<String>> DEFAULTED_ELSEWHERE = Map.of("kafka", Set.of("bootstrap-servers"));

    /** A default the code names but never reaches: mongodb start() refuses a blank uri before the later fallback is read. */
    private static final Map<String, Set<String>> DEFAULT_UNREACHED = Map.of("mongodb", Set.of("uri"));

    private static final Pattern READ = Pattern.compile(
            "\\b(?:ctx|context|settings|s)(?:\\.settings\\(\\))?\\.(setting|get|getOrDefault|containsKey)\\(\\s*\"([a-z][a-z0-9.-]*)\"\\s*([,)])");
    private static final Pattern PREFIX = Pattern.compile("\\b(?:k|key)\\.startsWith\\(\"([a-z][a-z0-9-]*\\.)\"\\)");
    private static final Pattern SWITCH_CASE = Pattern.compile("case \"([a-z][a-z-]*)\"\\s*->");

    private static final class Named implements SourcePlugin {
        private final String name;

        Named(String name) {
            this.name = name;
        }

        @Override
        public PluginManifest manifest() {
            return new PluginManifest(name, "t", Set.of("x"), SourceCapabilities.FETCH_ONLY);
        }

        @Override
        public void start(SourceContext context) {}

        @Override
        public Optional<EntityDocument> fetch(EntityRef ref) {
            return Optional.empty();
        }
    }

    private static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("plugins"))) {
            p = p.getParent();
        }
        assertThat(p).as("the repository root (a folder with plugins/)").isNotNull();
        return p;
    }

    private static List<Path> javaFiles(Path module) {
        try (Stream<Path> w = Files.walk(module.resolve("src/main/java"))) {
            return w.filter(f -> f.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The source without comments, which name settings too. */
    private static String code(Path f) {
        try {
            return Files.readString(f).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static SourcePlugin[] all() {
        return SOURCES.keySet().stream().map(Named::new).toArray(SourcePlugin[]::new);
    }

    @Test
    void everySettingAPluginReadsIsDeclaredInTheCatalogue() {
        Path root = root();
        SettingCatalogue cat = new SettingCatalogue(List.of(all()));
        List<String> missing = new ArrayList<>();
        for (var e : SOURCES.entrySet()) {
            SettingCatalogue.PluginSpec spec = cat.of(e.getKey());
            Set<String> keys = new TreeSet<>();
            for (String module : e.getValue()) {
                for (Path f : javaFiles(root.resolve(module))) {
                    String src = code(f);
                    Matcher m = READ.matcher(src);
                    while (m.find()) {
                        keys.add(m.group(2));
                    }
                    m = PREFIX.matcher(src);
                    while (m.find()) {
                        keys.add(m.group(1) + "x");
                    }
                    if (src.contains("switch (p[0])")) {                     // the jdbc query-mode keys: query.*, ids.*, columns.* ...
                        int from = src.indexOf("switch (p[0])");
                        m = SWITCH_CASE.matcher(src.substring(from, src.indexOf("default ->", from)));
                        while (m.find()) {
                            keys.add(m.group(1) + ".x");
                        }
                    }
                }
            }
            for (String k : keys) {
                if (NOT_SETTINGS.contains(k) || k.startsWith("tls.") || k.startsWith("proxy.")) {
                    continue;
                }
                if (spec.find(k) == null) {
                    missing.add(e.getKey() + ": " + (k.endsWith(".x") ? k.substring(0, k.length() - 1) + "*" : k));
                }
            }
        }
        assertThat(missing).as("settings the plugin code reads that plugin-settings.yaml does not declare (an 'unknown setting' warning)").isEmpty();
    }

    @Test
    void aSettingDeclaredRequiredHasNoDefaultInTheCode() {
        Path root = root();
        SettingCatalogue cat = new SettingCatalogue(List.of(all()));
        List<String> wrong = new ArrayList<>();
        for (var e : SOURCES.entrySet()) {
            for (SettingSpec s : cat.of(e.getKey()).settings()) {
                if (!s.required()) {
                    continue;
                }
                if (DEFAULTED_ELSEWHERE.getOrDefault(e.getKey(), Set.of()).contains(s.name())) {
                    wrong.add(e.getKey() + "." + s.name() + " is required in the catalogue but the code defaults it");
                    continue;
                }
                if (DEFAULT_UNREACHED.getOrDefault(e.getKey(), Set.of()).contains(s.name())) {
                    continue;
                }
                Pattern withDefault = Pattern.compile("\\.(?:setting|getOrDefault)\\(\\s*\"" + Pattern.quote(s.name()) + "\"\\s*,\\s*([^,)]+)");
                for (String module : e.getValue()) {
                    for (Path f : javaFiles(root.resolve(module))) {
                        Matcher m = withDefault.matcher(code(f));
                        while (m.find()) {
                            String dflt = m.group(1).trim();
                            if (!dflt.equals("\"\"") && !dflt.equals("null")) {
                                wrong.add(e.getKey() + "." + s.name() + " is required in the catalogue but " + f.getFileName() + " defaults it to " + dflt);
                            }
                        }
                    }
                }
            }
        }
        assertThat(wrong).as("required settings the plugin defaults").isEmpty();
    }
}
