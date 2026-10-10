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
package com.ash.drishti.packs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The folder of connector files: {@code <dir>/<name>.yaml}, one per connector, the file name (without {@code .yaml}) being
 * the connector's logical name. A pack refers to a connector by that name; the file says how to reach the system behind it.
 *
 * <pre>
 * plugin: jdbc                    # required: which source plugin
 * enabled: true                   # optional (default true); may be ${ENV_VAR:true}
 * kinds: [trade]                  # optional: limit the kinds served (default: what the pack's routes send here)
 * description: Trading database   # optional
 * settings:                       # the plugin's settings; nesting is flattened to dotted keys (tls: {truststore: {path: x}} is tls.truststore.path)
 *   url: jdbc:postgresql://db:5432/trading
 *   user: ${TRADING_DB_USER}
 *   password: ${TRADING_DB_PASSWORD}     # a credential is only ever an environment or file: reference
 * </pre>
 *
 * Writes are atomic (a temp file moved over the target) and keep the previous text under {@code <dir>/.history/}.
 */
public final class ConnectorFiles {

    /** A connector's logical name: lower case letters, digits and hyphens, starting with a letter or digit. */
    public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Set<String> KEYS = Set.of("name", "plugin", "enabled", "kinds", "description", "settings");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC);
    private static final int MAX_BYTES = 256 * 1024;
    static final String HISTORY = ".history";

    /** A file that cannot be used; the message says why, in words for the person who edits it. */
    public static final class InvalidFile extends IllegalArgumentException {
        public InvalidFile(String message) {
            super(message);
        }
    }

    /**
     * One connector as the file says it.
     *
     * @param name the logical name (the file name)
     * @param plugin the plugin
     * @param enabled "true", "false" or a {@code ${ENV}} placeholder, as written
     * @param kinds kinds served; empty means whatever the plugin and the pack routes say
     * @param description free text
     * @param settings flattened settings as written (placeholders unresolved)
     * @param text the file's text
     * @param etag version of the text, for optimistic concurrency
     * @param modified when the file was last written (null before it exists)
     */
    public record Definition(String name, String plugin, String enabled, List<String> kinds, String description,
            Map<String, String> settings, String text, String etag, Instant modified) {

        public Definition {
            kinds = kinds == null ? List.of() : List.copyOf(kinds);
            settings = settings == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(settings));
            description = description == null ? "" : description;
            enabled = enabled == null || enabled.isBlank() ? "true" : enabled;
        }

        /** True unless the file says {@code enabled: false} (a placeholder counts as on: it is resolved by the server). */
        public boolean isEnabled() {
            return !"false".equalsIgnoreCase(enabled.trim());
        }
    }

    /** One kept earlier text of a connector file. */
    public record Revision(String id, Instant at, long bytes) {}

    private final Path dir;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));

    public ConnectorFiles(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    public Path dir() {
        return dir;
    }

    /** Names the API uses for its own paths. */
    private static final Set<String> RESERVED = Set.of("plugins", "history");

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches() && !RESERVED.contains(name);
    }

    private static void requireName(String name) {
        if (!validName(name)) {
            throw new InvalidFile("'" + name + "' is not a connector name: lower case letters, digits and hyphens, starting with a letter or digit (for example trading-lake)");
        }
    }

    /** The file of a connector (it may not exist). */
    public Path file(String name) {
        requireName(name);
        return dir.resolve(name + ".yaml");
    }

    public boolean exists(String name) {
        return validName(name) && Files.isRegularFile(dir.resolve(name + ".yaml"));
    }

    /** The names that have a file, sorted; files whose names break the rule are listed by {@link #misnamed()}. */
    public List<String> names() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                    .filter(f -> f.endsWith(".yaml") && validName(f.substring(0, f.length() - 5)))
                    .map(f -> f.substring(0, f.length() - 5)).sorted().forEach(out::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** Files in the folder that are not connector files by name (a {@code .yml}, upper case, spaces): reported, never loaded. */
    public List<String> misnamed() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).filter(f -> !f.startsWith(".") && !f.endsWith("~"))
                    .filter(f -> !(f.endsWith(".yaml") && validName(f.substring(0, f.length() - 5)))).sorted().forEach(out::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** The raw text, or null when there is no such file. */
    public String text(String name) {
        Path f = file(name);
        try {
            if (!Files.isRegularFile(f)) {
                return null;
            }
            if (Files.size(f) > MAX_BYTES) {
                throw new InvalidFile(f.getFileName() + " is larger than " + MAX_BYTES / 1024 + " KB");
            }
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads and checks one file; {@code null} when it does not exist, {@link InvalidFile} when it cannot be used. */
    public Definition read(String name) {
        String text = text(name);
        if (text == null) {
            return null;
        }
        Definition d = parse(name, text);
        try {
            return new Definition(d.name(), d.plugin(), d.enabled(), d.kinds(), d.description(), d.settings(), text, etag(text),
                    Files.getLastModifiedTime(file(name)).toInstant());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Checks the text as the connector's file: shape only (the plugin, the secrets and the settings are the server's
     * checks). The text may name the connector in {@code name:}, which must agree with the file name.
     */
    public Definition parse(String name, String text) {
        requireName(name);
        Map<String, Object> m;
        try {
            m = text.isBlank() ? Map.of() : yaml.readValue(text, new TypeReference<Map<String, Object>>() {});
        } catch (IOException | RuntimeException e) {
            throw new InvalidFile(name + ".yaml is not valid YAML: " + firstLine(e.getMessage()));
        }
        if (m == null) {
            m = Map.of();
        }
        for (String k : m.keySet()) {
            if (!KEYS.contains(k)) {
                throw new InvalidFile(name + ".yaml: unknown key '" + k + "' (allowed: plugin, enabled, kinds, description, settings)");
            }
        }
        if (m.get("name") != null && !name.equals(String.valueOf(m.get("name")))) {
            throw new InvalidFile(name + ".yaml says name '" + m.get("name") + "' but the file name is the connector's name; rename the file or remove 'name'");
        }
        Object plugin = m.get("plugin");
        if (plugin == null || String.valueOf(plugin).isBlank()) {
            throw new InvalidFile(name + ".yaml: 'plugin' is required (jdbc, delta, kafka, ...)");
        }
        Object en = m.get("enabled");
        if (en != null && !(en instanceof Boolean) && !(en instanceof String s && s.contains("${"))) {
            throw new InvalidFile(name + ".yaml: 'enabled' is true or false");
        }
        List<String> kinds = new ArrayList<>();
        Object k = m.get("kinds");
        if (k instanceof List<?> l) {
            l.forEach(x -> kinds.add(String.valueOf(x)));
        } else if (k instanceof String s) {
            for (String x : s.split(",")) {
                if (!x.isBlank()) {
                    kinds.add(x.trim());
                }
            }
        } else if (k != null) {
            throw new InvalidFile(name + ".yaml: 'kinds' is a list of kinds");
        }
        Object st = m.get("settings");
        if (st != null && !(st instanceof Map<?, ?>)) {
            throw new InvalidFile(name + ".yaml: 'settings' is a mapping");
        }
        Map<String, Object> flat = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = st == null ? Map.of() : (Map<String, Object>) st;
        PackLoader.flatten("", raw, flat);
        Map<String, String> settings = new LinkedHashMap<>();
        flat.forEach((key, v) -> {
            if (!KEY.matcher(key).matches()) {
                throw new InvalidFile(name + ".yaml: '" + key + "' is not a valid setting name");
            }
            settings.put(key, v == null ? "" : String.valueOf(v));
        });
        return new Definition(name, String.valueOf(plugin).trim(), en == null ? null : String.valueOf(en), kinds,
                m.get("description") == null ? "" : String.valueOf(m.get("description")), settings, text, etag(text), null);
    }

    /** Reads every file; the ones that cannot be used are left out and their problems returned in {@code problems} (name to message). */
    public Map<String, Definition> readAll(Map<String, String> problems) {
        Map<String, Definition> out = new LinkedHashMap<>();
        for (String n : names()) {
            try {
                out.put(n, read(n));
            } catch (InvalidFile | UncheckedIOException e) {
                problems.put(n, e.getMessage());
            }
        }
        return out;
    }

    /** The version of a text: stable for equal text, different for different text. */
    public static String etag(String text) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Writes the text atomically, keeping the previous text in the history; creates the folder on first write. */
    public void write(String name, String text) {
        Path f = file(name);
        try {
            Files.createDirectories(dir);
            backup(name);
            Path tmp = Files.createTempFile(dir, "." + name + "-", ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Removes the file after keeping its text in the history. */
    public boolean delete(String name) {
        Path f = file(name);
        try {
            if (!Files.isRegularFile(f)) {
                return false;
            }
            backup(name);
            Files.delete(f);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void backup(String name) throws IOException {
        Path f = dir.resolve(name + ".yaml");
        if (!Files.isRegularFile(f)) {
            return;
        }
        Path h = dir.resolve(HISTORY);
        Files.createDirectories(h);
        String stamp = STAMP.format(Instant.now());
        Path to = h.resolve(name + "." + stamp + ".yaml");
        for (int i = 1; Files.exists(to); i++) {
            to = h.resolve(name + "." + stamp + "-" + i + ".yaml");
        }
        Files.copy(f, to);
    }

    /** The kept earlier texts of a connector, newest first. */
    public List<Revision> history(String name) {
        requireName(name);
        Path h = dir.resolve(HISTORY);
        List<Revision> out = new ArrayList<>();
        if (!Files.isDirectory(h)) {
            return out;
        }
        Pattern p = Pattern.compile(Pattern.quote(name) + "\\.(\\d{8}T\\d{9}(?:-\\d+)?)\\.yaml");
        try (Stream<Path> s = Files.list(h)) {
            for (Path x : s.toList()) {
                var m = p.matcher(x.getFileName().toString());
                if (m.matches()) {
                    out.add(new Revision(m.group(1), Files.getLastModifiedTime(x).toInstant(), Files.size(x)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort((a, b) -> b.id().compareTo(a.id()));
        return out;
    }

    /** The text of one kept revision, or null. */
    public String revisionText(String name, String id) {
        requireName(name);
        if (!id.matches("\\d{8}T\\d{9}(-\\d+)?")) {
            return null;
        }
        Path f = dir.resolve(HISTORY).resolve(name + "." + id + ".yaml");
        try {
            return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Renders a connector as file text (used to generate files from templates and from the form). */
    public String render(String header, String plugin, Object enabled, List<String> kinds, String description, Map<String, ?> settings) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("plugin", plugin);
        if (enabled != null && !"true".equals(String.valueOf(enabled))) {
            doc.put("enabled", enabled instanceof String s && !s.contains("${") ? Boolean.parseBoolean(s) : enabled);
        }
        if (description != null && !description.isBlank()) {
            doc.put("description", description);
        }
        if (kinds != null && !kinds.isEmpty()) {
            doc.put("kinds", kinds);
        }
        if (settings != null && !settings.isEmpty()) {
            doc.put("settings", nest(settings));
        }
        try {
            return (header == null || header.isBlank() ? "" : header.stripTrailing() + "\n") + yaml.writeValueAsString(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Dotted keys back to nesting where that reads better ({@code tls.truststore.path}); every other key stays flat. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> nest(Map<String, ?> flat) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : flat.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("tls.")) {
                String[] parts = k.split("\\.");
                Map<String, Object> cur = out;
                boolean clash = false;
                for (int i = 0; i < parts.length - 1; i++) {
                    Object next = cur.computeIfAbsent(parts[i], x -> new LinkedHashMap<String, Object>());
                    if (!(next instanceof Map)) {
                        clash = true;
                        break;
                    }
                    cur = (Map<String, Object>) next;
                }
                if (!clash && !(cur.get(parts[parts.length - 1]) instanceof Map)) {
                    cur.put(parts[parts.length - 1], e.getValue());
                    continue;
                }
            }
            out.put(k, e.getValue());
        }
        return out;
    }

    /** The Spring properties a set of definitions contributes ({@code drishti.sources.connectors.<name>.*}). */
    public static Map<String, Object> properties(Map<String, Definition> defs) {
        Map<String, Object> p = new LinkedHashMap<>();
        defs.forEach((name, d) -> {
            String base = "drishti.sources.connectors." + name;
            p.put(base + ".plugin", d.plugin());
            p.put(base + ".enabled", d.enabled());
            for (int i = 0; i < d.kinds().size(); i++) {
                p.put(base + ".kinds[" + i + "]", d.kinds().get(i));
            }
            d.settings().forEach((k, v) -> p.put(base + ".settings." + k, v));
        });
        return p;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
