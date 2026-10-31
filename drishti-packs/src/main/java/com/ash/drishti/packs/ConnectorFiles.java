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
import java.util.Optional;
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
 * Writes are atomic (a temp file moved over the target) and keep the previous text under {@code <dir>/history/}.
 */
public final class ConnectorFiles {

    /** A connector's logical name: lower case letters, digits and hyphens, starting with a letter or digit. */
    public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Set<String> KEYS = Set.of("name", "plugin", "enabled", "kinds", "description", "settings", "requires", "mode", "reads",
            "engine", "triggers", "coalesce", "cache", "limits", "history", "incremental", "writes");
    /** The keys of a computation (docs/architecture/DATA_ARCHITECTURE.md › Reactive connectors, section 5): only a connector of the {@code reactive} plugin has them. */
    public static final List<String> COMPUTE_KEYS = List.of("reads", "engine", "triggers", "coalesce", "cache", "limits", "history", "incremental");
    /** The setting a reactive connector's computation reaches its plugin in: the {@code compute} block as JSON. */
    public static final String COMPUTE_SETTING = "reactive.spec";
    /** The connector modes (DATA_ARCHITECTURE.md › Reactive connectors, section 3). */
    public static final Set<String> MODES = Set.of("static", "live", "on-demand", "reactive");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC);
    private static final int MAX_BYTES = 256 * 1024;
    static final String HISTORY = "history";
    static final String GENERATED = "generated";

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
     * @param requires the {@code requires:} block as written (the compute libraries the connector needs, docs/architecture/PYTHON_COMPUTE.md
     *        section 4), empty when none; nothing in it runs until an administrator approves it
     * @param compute a reactive connector's computation as written ({@code mode}, {@code kinds} with their {@code params}, {@code reads},
     *        {@code engine}, {@code triggers}, {@code coalesce}, {@code cache}, {@code limits}, {@code history}), empty for any other connector;
     *        it reaches the plugin as the setting {@link ConnectorFiles#COMPUTE_SETTING}
     * @param writes the {@code writes:} block as written (WRITE_BACK.md › The design and its reasons, section 5.4): named commands, each with its plugin's template, its
     *        concurrency guard and idempotency clause; empty when the connector is read-only (every shipped one). The server validates it
     *        when the connector loads; a connector writes only once an administrator also switches its writes on
     */
    public record Definition(String name, String plugin, String enabled, List<String> kinds, String description,
            Map<String, String> settings, String text, String etag, Instant modified, Map<String, Object> requires, Map<String, Object> compute,
            Map<String, Object> writes) {

        /** A definition without {@code writes:}. */
        public Definition(String name, String plugin, String enabled, List<String> kinds, String description, Map<String, String> settings,
                String text, String etag, Instant modified, Map<String, Object> requires, Map<String, Object> compute) {
            this(name, plugin, enabled, kinds, description, settings, text, etag, modified, requires, compute, Map.of());
        }

        /** A definition without {@code requires:}. */
        public Definition(String name, String plugin, String enabled, List<String> kinds, String description, Map<String, String> settings,
                String text, String etag, Instant modified) {
            this(name, plugin, enabled, kinds, description, settings, text, etag, modified, Map.of(), Map.of());
        }

        /** A definition without a computation. */
        public Definition(String name, String plugin, String enabled, List<String> kinds, String description, Map<String, String> settings,
                String text, String etag, Instant modified, Map<String, Object> requires) {
            this(name, plugin, enabled, kinds, description, settings, text, etag, modified, requires, Map.of());
        }

        public Definition {
            requires = requires == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(requires));
            compute = compute == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(compute));
            writes = writes == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(writes));
            kinds = kinds == null ? List.of() : List.copyOf(kinds);
            settings = settings == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(settings));
            description = description == null ? "" : description;
            enabled = enabled == null || enabled.isBlank() ? "true" : enabled;
        }

        /** True unless the file says {@code enabled: false} (a placeholder counts as on: it is resolved by the server). */
        public boolean isEnabled() {
            return !"false".equalsIgnoreCase(enabled.trim());
        }

        /**
         * Why {@code enabled}, with its placeholder resolved to {@code resolved}, is refused: anything but true or false in any case, said
         * as a literal value is (QA4-04: {@code ${VAR:nope}} switched the connector off without a word). Empty when it is a switch.
         */
        public Optional<String> enabledRefusal(String resolved) {
            String v = resolved == null ? "" : resolved.trim();
            if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) {
                return Optional.empty();
            }
            return Optional.of(name + ".yaml: 'enabled' is true or false (or a \"${VARIABLE:true}\" placeholder), not '" + v + "'"
                    + (enabled.contains("${") ? " (" + enabled.trim() + " resolves to it)" : ""));
        }
    }

    /** One kept earlier text of a connector file. */
    public record Revision(String id, Instant at, long bytes) {}

    private final Path dir;
    private final Path state;
    private volatile ConnectorOverlay overlay;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));
    private static final ObjectMapper REQUIRES_YAML = new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES).enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS));

    /** History and generated-file markers live beside the files (a test, a tool). */
    public ConnectorFiles(Path dir) {
        this(dir, dir);
    }

    /** The files in {@code dir} (user configuration); their edit history and generated-file markers in {@code state} ({@code <data dir>/connectors}). */
    public ConnectorFiles(Path dir, Path state) {
        this.dir = dir.toAbsolutePath().normalize();
        this.state = state.toAbsolutePath().normalize();
        for (String[] n : new String[][] {{".history", HISTORY}, {".generated", GENERATED}}) {
            com.ash.drishti.common.LegacyNames.move(this.dir.resolve(n[0]), this.state.resolve(n[1]));
            if (!this.state.equals(this.dir)) {
                com.ash.drishti.common.LegacyNames.move(this.dir.resolve(n[1]), this.state.resolve(n[1]));
            }
        }
    }

    /** Reads through (and writes to) the shared overlay from now on: edits made through Admin reach every node (DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 11). */
    public ConnectorFiles overlay(ConnectorOverlay o) {
        this.overlay = o;
        return this;
    }

    public ConnectorOverlay overlay() {
        return overlay;
    }

    /** Where a connector's text comes from: {@code file}, {@code shared} (an edit made through Admin), {@code shared-hidden} (deleted through Admin), or null. */
    public String source(String name) {
        ConnectorOverlay o = overlay;
        ConnectorOverlay.Entry e = o == null || !validName(name) ? null : o.get(name);
        if (e != null) {
            return e.hidden() ? "shared-hidden" : "shared";
        }
        return fileExists(name) ? "file" : null;
    }

    /** Whether the file of that name is on disk (the base), whatever the overlay says. */
    public boolean fileExists(String name) {
        return validName(name) && Files.isRegularFile(dir.resolve(name + ".yaml"));
    }

    /** The names that have a shared overlay (edited or hidden through Admin). */
    public Set<String> overlaid() {
        ConnectorOverlay o = overlay;
        return o == null ? Set.of() : Set.copyOf(o.all().keySet());
    }

    /** Where the edit history and generated-file markers are kept ({@code <data dir>/connectors}). */
    public Path stateDir() {
        return state;
    }

    public Path dir() {
        return dir;
    }

    /** Names the API uses for its own paths. */
    private static final Set<String> RESERVED = Set.of("plugins", "history", "generated");

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
        ConnectorOverlay o = overlay;
        ConnectorOverlay.Entry e = o == null || !validName(name) ? null : o.get(name);
        return e != null ? !e.hidden() : fileExists(name);
    }

    /** The names that have a file, sorted; files whose names break the rule are listed by {@link #misnamed()}. */
    public List<String> names() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return overlay == null ? out : withOverlay(out);
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                    .filter(f -> f.endsWith(".yaml") && validName(f.substring(0, f.length() - 5)))
                    .map(f -> f.substring(0, f.length() - 5)).sorted().forEach(out::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return overlay == null ? out : withOverlay(out);
    }

    private List<String> withOverlay(List<String> onDisk) {
        java.util.TreeSet<String> all = new java.util.TreeSet<>(onDisk);
        overlay.all().forEach((n, e) -> {
            if (e.hidden()) {
                all.remove(n);
            } else {
                all.add(n);
            }
        });
        return new ArrayList<>(all);
    }

    /** Files in the folder that are not connector files by name (a {@code .yml}, upper case, spaces): reported, never loaded. */
    public List<String> misnamed() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).filter(f -> !f.startsWith(".") && !f.endsWith("~") && !f.endsWith(".tmp"))
                    .filter(f -> !(f.endsWith(".yaml") && validName(f.substring(0, f.length() - 5)))).sorted().forEach(out::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** The raw text, or null when there is no such file. */
    public String text(String name) {
        Path f = file(name);
        ConnectorOverlay o = overlay;
        ConnectorOverlay.Entry e = o == null ? null : o.get(name);
        if (e != null) {
            return e.hidden() ? null : e.text();
        }
        return fileText(f);
    }

    /** The file's own text (the base), or null. */
    public String fileText(String name) {
        return fileText(file(name));
    }

    private static String fileText(Path f) {
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
        ConnectorOverlay o = overlay;
        ConnectorOverlay.Entry shared = o == null ? null : o.get(name);
        if (shared != null) {
            return new Definition(d.name(), d.plugin(), d.enabled(), d.kinds(), d.description(), d.settings(), text, etag(text), shared.at(),
                    d.requires(), d.compute(), d.writes());
        }
        try {
            return new Definition(d.name(), d.plugin(), d.enabled(), d.kinds(), d.description(), d.settings(), text, etag(text),
                    Files.getLastModifiedTime(file(name)).toInstant(), d.requires(), d.compute(), d.writes());
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
                throw new InvalidFile(name + ".yaml: unknown key '" + k + "' (allowed: plugin, enabled, kinds, description, settings, requires; "
                        + "a reactive connector also mode, reads, engine, triggers, coalesce, cache, limits, history; a connector that writes also writes)");
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
        // a quoted "true" or "false" is the same switch (SOAK2-02): "${DRISHTI_LAKE_ENABLED:true}" resolves to that string too, and
        // render() writes either form back as a boolean
        if (en instanceof String s && (s.trim().equalsIgnoreCase("true") || s.trim().equalsIgnoreCase("false"))) {
            en = Boolean.valueOf(s.trim());
        }
        if (en != null && !(en instanceof Boolean) && !(en instanceof String s && s.contains("${"))) {
            throw new InvalidFile(name + ".yaml: 'enabled' is true or false (or a \"${VARIABLE:true}\" placeholder), not '" + en + "'");
        }
        List<String> kinds = new ArrayList<>();
        Object k = m.get("kinds");
        boolean reactive = "reactive".equals(String.valueOf(plugin).trim());
        Map<String, Object> compute = compute(name, m, reactive);
        if (k instanceof Map<?, ?> km) {
            if (!reactive) {
                throw new InvalidFile(name + ".yaml: 'kinds' is a list of kinds (kinds with parameters are for plugin: reactive)");
            }
            km.keySet().forEach(x -> kinds.add(String.valueOf(x)));
        } else if (k instanceof List<?> l) {
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
        if (!compute.isEmpty()) {
            try {
                settings.put(COMPUTE_SETTING, JSON.writeValueAsString(compute));
            } catch (IOException e) {
                throw new InvalidFile(name + ".yaml: its computation cannot be read: " + firstLine(e.getMessage()));
            }
        }
        Object req = m.get("requires");
        if (req != null && !(req instanceof Map<?, ?>)) {
            throw new InvalidFile(name + ".yaml: 'requires' is a mapping (python, java, plugins)");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> requires = req == null ? Map.of() : (Map<String, Object>) req;
        Object wr = m.get("writes");
        if (wr != null && !(wr instanceof Map<?, ?>)) {
            throw new InvalidFile(name + ".yaml: 'writes' is a mapping of named commands (WRITE_BACK.md › The design and its reasons, section 5.4)");
        }
        if (wr != null && reactive) {
            throw new InvalidFile(name + ".yaml: a reactive connector computes; it is never a write target (no 'writes')");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> writes = wr == null ? Map.of() : (Map<String, Object>) wr;
        return new Definition(name, String.valueOf(plugin).trim(), en == null ? null : String.valueOf(en), kinds,
                m.get("description") == null ? "" : String.valueOf(m.get("description")), settings, text, etag(text), null, requires, compute, writes);
    }

    /** A pack template's computation when its plugin is {@code reactive} (empty otherwise, or when it is not usable: the file says why). */
    public static Map<String, Object> computeOf(Map<String, Object> template) {
        boolean reactive = "reactive".equals(String.valueOf(template.get("plugin")).trim());
        if (!reactive) {
            return Map.of();
        }
        try {
            return compute("template", template, true);
        } catch (InvalidFile e) {
            return Map.of();
        }
    }

    /** The setting a computation reaches its plugin in ({@link #COMPUTE_SETTING}): the block as JSON; null for none. */
    public static String computeSetting(Map<String, Object> compute) {
        if (compute == null || compute.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(compute);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The kinds a template or file names: a list, comma-separated text, or a mapping of kinds to their parameters (reactive). */
    public static List<String> kindNames(Object kinds) {
        List<String> out = new ArrayList<>();
        if (kinds instanceof Map<?, ?> m) {
            m.keySet().forEach(x -> out.add(String.valueOf(x)));
        } else if (kinds instanceof List<?> l) {
            l.forEach(x -> out.add(String.valueOf(x)));
        } else if (kinds instanceof String s) {
            for (String x : s.split(",")) {
                if (!x.isBlank()) {
                    out.add(x.trim());
                }
            }
        }
        return out;
    }

    /**
     * A reactive connector's computation as written: {@code mode}, {@code kinds} (a mapping of kinds to their {@code params:} and
     * {@code result:}) and the keys of {@link #COMPUTE_KEYS}. Refused on any other plugin (mode excepted), where they would be ignored.
     */
    private static Map<String, Object> compute(String name, Map<String, Object> m, boolean reactive) {
        Object mode = m.get("mode");
        if (mode != null && !MODES.contains(String.valueOf(mode).trim())) {
            throw new InvalidFile(name + ".yaml: 'mode' is static, live, on-demand or reactive, not '" + mode + "'");
        }
        if (reactive && mode != null && !"reactive".equals(String.valueOf(mode).trim())) {
            throw new InvalidFile(name + ".yaml: a connector of plugin reactive has mode reactive, not '" + mode + "'");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : COMPUTE_KEYS) {
            if (m.get(key) == null) {
                continue;
            }
            if (!reactive) {
                throw new InvalidFile(name + ".yaml: '" + key + "' is part of a computation: only a connector of plugin reactive has it");
            }
            out.put(key, m.get(key));
        }
        if (reactive) {
            out.put("mode", "reactive");
            if (m.get("kinds") instanceof Map<?, ?> km) {
                out.put("kinds", km);
            }
        }
        return out;
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
        write(name, text, null);
    }

    /**
     * Writes the text. With an overlay attached it is a shared edit (compare-and-set against {@code expectedEtag}, the etag of the
     * text the edit was based on, null for no check); otherwise the file, atomically, keeping the previous text in the history.
     */
    public void write(String name, String text, String expectedEtag) {
        Path f = file(name);
        ConnectorOverlay o = overlay;
        if (o != null) {
            o.put(name, text, expectedEtag, fileText(f));
            return;
        }
        try {
            Files.createDirectories(dir);
            backup(name);
            Path tmp = com.ash.drishti.common.TempFiles.createFile(dir, name + "-", ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Keeps a copy of the connector's file on disk beside it, {@code <name>.yaml.<label>-<yyyyMMdd-HHmmss>.bak} (not a {@code .yaml}, so
     * never read as a connector), before an automatic change; returns it, or null when there is no file on disk (a shared edit's earlier
     * text stays in the shared history) or it cannot be written.
     */
    public Path keepBeside(String name, String label) {
        try {
            Path f = file(name);
            if (!Files.isRegularFile(f)) {
                return null;
            }
            String at = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path b = f.resolveSibling(f.getFileName() + "." + label + "-" + at + ".bak");
            Files.copy(f, b, StandardCopyOption.REPLACE_EXISTING);
            return b;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // -- generated-file markers ------------------------------------------------------------------------------------

    /**
     * What was generated from a pack template: the etag of the text as written, the fingerprint of the template it came from and
     * its kinds. A file whose text still has {@code fileEtag} has not been edited.
     */
    public record Marker(String fileEtag, String templateFingerprint, List<String> kinds) {}

    private Path markerFile(String name) {
        requireName(name);
        return state.resolve(GENERATED).resolve(name + ".properties");
    }

    /** The marker of a generated file, or null when the file was not generated by a pack template (or before markers existed). */
    public Marker marker(String name) {
        Path f = markerFile(name);
        try {
            if (!Files.isRegularFile(f)) {
                return null;
            }
            java.util.Properties pr = new java.util.Properties();
            try (var in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                pr.load(in);
            }
            List<String> kinds = new ArrayList<>();
            for (String x : pr.getProperty("kinds", "").split(",")) {
                if (!x.isBlank()) {
                    kinds.add(x.trim());
                }
            }
            return new Marker(pr.getProperty("file", ""), pr.getProperty("template", ""), kinds);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Records that the file now on disk with this text came from a template with this fingerprint. */
    public void writeMarker(String name, String text, String templateFingerprint, List<String> kinds) {
        Path f = markerFile(name);
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, "file=" + etag(text) + "\ntemplate=" + templateFingerprint + "\nkinds=" + String.join(",", kinds) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Removes the file after keeping its text in the history. */
    public boolean delete(String name) {
        Path f = file(name);
        ConnectorOverlay o = overlay;
        if (o != null) {
            String now = text(name);
            if (now == null) {
                return false;
            }
            if (fileExists(name)) {
                o.hide(name, now);
            } else {
                o.remove(name, now);
            }
            return true;
        }
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

    /**
     * Takes a connector out for an uninstall: removes its shared overlay, if any (the text stays in the shared history), and the
     * file of that name, if any (its text kept in the file history). True when there was something to remove.
     */
    public boolean removeEverywhere(String name) {
        requireName(name);
        String now = text(name);
        Path f = file(name);
        boolean gone = false;
        try {
            if (Files.isRegularFile(f)) {
                backup(name);
                Files.delete(f);
                gone = true;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ConnectorOverlay o = overlay;
        if (o != null && o.get(name) != null) {
            o.remove(name, now == null ? "" : now);
            gone = true;
        }
        return gone;
    }

    private void backup(String name) throws IOException {
        Path f = dir.resolve(name + ".yaml");
        if (!Files.isRegularFile(f)) {
            return;
        }
        Path h = state.resolve(HISTORY);
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
        Path h = state.resolve(HISTORY);
        List<Revision> out = new ArrayList<>();
        ConnectorOverlay o = overlay;
        if (o != null) {
            out.addAll(o.history(name));
        }
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
        ConnectorOverlay o = overlay;
        String shared = o == null ? null : o.revisionText(name, id);
        if (shared != null) {
            return shared;
        }
        Path f = state.resolve(HISTORY).resolve(name + "." + id + ".yaml");
        try {
            return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Renders a connector as file text (used to generate files from templates and from the form). */
    public String render(String header, String plugin, Object enabled, List<String> kinds, String description, Map<String, ?> settings) {
        return render(header, plugin, enabled, kinds, description, settings, Map.of());
    }

    /** As above, keeping a {@code requires:} block (compute libraries) when there is one. */
    public String render(String header, String plugin, Object enabled, List<String> kinds, String description, Map<String, ?> settings,
            Map<String, Object> requires) {
        return render(header, plugin, enabled, kinds, description, settings, requires, Map.of());
    }

    /** As above, keeping a reactive connector's computation ({@code mode}, {@code kinds} with parameters, {@code reads}, ...) as written. */
    public String render(String header, String plugin, Object enabled, List<String> kinds, String description, Map<String, ?> settings,
            Map<String, Object> requires, Map<String, Object> compute) {
        return render(header, plugin, enabled, kinds, description, settings, requires, compute, Map.of());
    }

    /** As above, keeping a {@code writes:} block (WRITE_BACK.md › The design and its reasons, section 5.4) as written. */
    public String render(String header, String plugin, Object enabled, List<String> kinds, String description, Map<String, ?> settings,
            Map<String, Object> requires, Map<String, Object> compute, Map<String, Object> writes) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("plugin", plugin);
        if (compute != null && compute.get("mode") != null) {
            doc.put("mode", compute.get("mode"));
        }
        if (enabled != null && !"true".equals(String.valueOf(enabled))) {
            doc.put("enabled", enabled instanceof String s && !s.contains("${") ? Boolean.parseBoolean(s) : enabled);
        }
        if (description != null && !description.isBlank()) {
            doc.put("description", description);
        }
        if (compute != null && compute.get("kinds") instanceof Map<?, ?> km) {
            doc.put("kinds", km);
        } else if (kinds != null && !kinds.isEmpty()) {
            doc.put("kinds", kinds);
        }
        if (compute != null) {
            COMPUTE_KEYS.forEach(key -> {
                if (compute.get(key) != null) {
                    doc.put(key, compute.get(key));
                }
            });
        }
        Map<String, Object> plain = new LinkedHashMap<>(settings == null ? Map.of() : settings);
        plain.remove(COMPUTE_SETTING);                      // the computation is written as itself above, never as JSON in settings
        if (!plain.isEmpty()) {
            doc.put("settings", nest(plain));
        }
        if (writes != null && !writes.isEmpty()) {
            doc.put("writes", writes);
        }
        try {
            String text = (header == null || header.isBlank() ? "" : header.stripTrailing() + "\n") + yaml.writeValueAsString(doc);
            if (requires != null && !requires.isEmpty()) {
                // versions such as "3.10" stay text: written with every number-like string quoted
                text += REQUIRES_YAML.writeValueAsString(Map.of("requires", requires));
            }
            return text;
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
