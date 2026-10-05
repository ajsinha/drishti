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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The administrator's override of a pack's data source: one file per pack, {@code <dir>/<pack>.yaml}, written from
 * Admin → Packs → Data source and read at every load. The pack's own files are never touched, so a redeploy of the pack
 * keeps the override.
 *
 * <pre>
 * connectors:
 *   risk-store:
 *     enabled: true                        # optional
 *     settings:                            # flat keys, as the connector reads them; each one replaces the pack's value
 *       root: /mnt/lake
 *       password: ${RISK_DB_PASSWORD}      # a secret is only ever an environment reference
 * </pre>
 *
 * Precedence, highest first: environment variables and site configuration (for example
 * {@code DRISHTI_SOURCES_CONNECTORS_RISK_STORE_SETTINGS_ROOT}), this file, the pack's own values.
 */
public final class PackSettings {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}");
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(password|passwd|secret|token|api[-_.]?key|access[-_.]?key|private[-_.]?key|credential).*");
    private static final Pattern ENV_ONLY = Pattern.compile("\\$\\{[A-Za-z_][A-Za-z0-9_]*}");
    private static final Pattern URL_PASSWORD = Pattern.compile("(?i)(password|pwd)=(?!\\$\\{)[^&;]+|://[^/@\\s:]+:(?!\\$\\{)[^/@\\s]+@");
    private static final int MAX_VALUE = 2000;

    private final Path dir;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));

    public PackSettings(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    public Path dir() {
        return dir;
    }

    /** The override file of a pack (it may not exist). */
    public Path file(String pack) {
        if (!NAME.matcher(pack).matches()) {
            throw new IllegalArgumentException("not a pack name: " + pack);
        }
        return dir.resolve(pack + ".yaml");
    }

    /** True for a setting that holds a credential, whatever the plugin: it may only name an environment variable. */
    public static boolean secret(String key) {
        return SECRET_KEY.matcher(key).matches();
    }

    /** The whole override document of a pack: {@code connectors → name → {enabled, settings}}; empty when none. */
    public Map<String, Object> read(String pack) {
        Path f = file(pack);
        if (!Files.isRegularFile(f)) {
            return Map.of();
        }
        try {
            Map<String, Object> m = yaml.readValue(f.toFile(), new TypeReference<Map<String, Object>>() {});
            return m == null ? Map.of() : m;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + f, e);
        }
    }

    /** The override of one connector of a pack: {@code enabled} (optional) and {@code settings} (flat, as strings). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> connector(String pack, String connector) {
        Object cs = read(pack).get("connectors");
        if (cs instanceof Map<?, ?> m && m.get(connector) instanceof Map<?, ?> c) {
            return (Map<String, Object>) c;
        }
        return Map.of();
    }

    /** The raw text of the file, or null when the pack has none (kept to put it back after a failed change). */
    public String text(String pack) {
        Path f = file(pack);
        try {
            return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes the text atomically ({@code null} removes the file). */
    public void putText(String pack, String text) {
        Path f = file(pack);
        try {
            if (text == null) {
                Files.deleteIfExists(f);
                return;
            }
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "." + pack + "-", ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes an override document (already validated); an empty one removes the file. */
    public void write(String pack, Map<String, Object> doc) {
        try {
            putText(pack, doc.isEmpty() ? null : "# Data source override, written from Admin → Packs → Data source. The pack's own files are unchanged;\n"
                    + "# delete this file (or press Reset) to use the pack's defaults again.\n" + yaml.writeValueAsString(doc));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The problems of an override document against the connectors the pack declares (empty when it is fine): unknown
     * connectors, anything but {@code enabled} and {@code settings}, bad keys, long or multi-line values, and credentials
     * that are not an environment reference ({@code ${NAME}}).
     */
    public static List<String> validate(Map<String, Object> doc, Map<String, Object> declared) {
        List<String> out = new ArrayList<>();
        for (String top : doc.keySet()) {
            if (!"connectors".equals(top)) {
                out.add("unknown section '" + top + "' (only 'connectors')");
            }
        }
        Object cs = doc.get("connectors");
        if (cs == null) {
            return out;
        }
        if (!(cs instanceof Map<?, ?> conns)) {
            out.add("'connectors' must be a mapping");
            return out;
        }
        for (Map.Entry<?, ?> e : conns.entrySet()) {
            String name = String.valueOf(e.getKey());
            if (!declared.containsKey(name)) {
                out.add("connector '" + name + "' is not one the pack declares (" + String.join(", ", declared.keySet()) + ")");
                continue;
            }
            if (!(e.getValue() instanceof Map<?, ?> c)) {
                out.add(name + ": must be a mapping with 'enabled' and/or 'settings'");
                continue;
            }
            for (Object k : c.keySet()) {
                if (!"enabled".equals(k) && !"settings".equals(k)) {
                    out.add(name + ": unknown key '" + k + "' (only 'enabled' and 'settings'; kinds and plugin belong to the pack)");
                }
            }
            if (c.get("enabled") != null && !(c.get("enabled") instanceof Boolean)) {
                out.add(name + ".enabled: true or false");
            }
            Object s = c.get("settings");
            if (s == null) {
                continue;
            }
            if (!(s instanceof Map<?, ?> st)) {
                out.add(name + ".settings: must be a mapping");
                continue;
            }
            Object plugin = declared.get(name) instanceof Map<?, ?> dm ? dm.get("plugin") : null;
            for (Map.Entry<?, ?> kv : st.entrySet()) {
                String key = String.valueOf(kv.getKey());
                String where = name + ".settings." + key;
                if (!KEY.matcher(key).matches()) {
                    out.add(where + ": not a valid setting name");
                    continue;
                }
                if (kv.getValue() instanceof Map<?, ?> || kv.getValue() instanceof List<?>) {
                    out.add(where + ": a single value (use dotted names such as a.b instead of nesting)");
                    continue;
                }
                String v = kv.getValue() == null ? "" : String.valueOf(kv.getValue());
                if (v.length() > MAX_VALUE || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
                    out.add(where + ": one line of at most " + MAX_VALUE + " characters");
                } else if (secret(key) && !v.isEmpty() && !ENV_ONLY.matcher(v).matches()) {
                    out.add(where + ": a credential is never stored here; write an environment reference such as ${" + envName(key) + "}");
                } else if (URL_PASSWORD.matcher(v).find()) {
                    out.add(where + ": a password inside a URL is never stored here; give it as a separate setting that is an environment reference");
                } else if ("jdbc".equals(plugin) && "url".equals(key) && !v.startsWith("jdbc:") && !v.contains("${")) {
                    out.add(where + ": a JDBC URL starts with jdbc:");
                } else if (("root".equals(key) || "url".equals(key)) && v.isBlank()) {
                    out.add(where + ": cannot be empty");
                }
            }
        }
        return out;
    }

    private static String envName(String key) {
        return key.replaceAll("[^A-Za-z0-9]+", "_").toUpperCase(java.util.Locale.ROOT);
    }

    /** The same setting names as the pack declares them: flat, dotted for nesting (used to show pack default against override). */
    public static Map<String, String> flat(Map<String, Object> settings) {
        Map<String, Object> out = new LinkedHashMap<>();
        PackLoader.flatten("", settings == null ? Map.of() : settings, out);
        Map<String, String> r = new LinkedHashMap<>();
        out.forEach((k, v) -> r.put(k, String.valueOf(v)));
        return r;
    }
}
