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
package com.ash.drishti.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads {@code config/ai/*.yaml}, one provider per file. A file that cannot be used is reported (file name to reason) and
 * skipped; the others still load. The API key must be an {@code ${ENV_NAME}} placeholder: a literal key in a file is refused.
 */
public final class ProviderConfigLoader {

    /** What a folder held. */
    public record Loaded(List<ProviderConfig> providers, Map<String, String> errors) {}

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern ENV = Pattern.compile("^\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-?([^}]*))?}$");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    private ProviderConfigLoader() {}

    public static Loaded load(Path dir, Function<String, String> env) {
        List<ProviderConfig> ok = new ArrayList<>();
        Map<String, String> errors = new TreeMap<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return new Loaded(ok, errors);
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> {
                String n = p.getFileName().toString();
                return Files.isRegularFile(p) && (n.endsWith(".yaml") || n.endsWith(".yml")) && !n.startsWith(".");
            }).sorted().toList();
        } catch (IOException e) {
            errors.put(dir.toString(), "cannot read the folder (" + e.getClass().getSimpleName() + ")");
            return new Loaded(ok, errors);
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Path f : files) {
            String file = f.getFileName().toString();
            try {
                ProviderConfig c = parse(f, env);
                if (!seen.add(c.name())) {
                    errors.put(file, "another file already defines provider '" + c.name() + "'");
                } else {
                    ok.add(c);
                }
            } catch (LlmException e) {
                errors.put(file, e.getMessage());
            }
        }
        return new Loaded(ok, errors);
    }

    static ProviderConfig parse(Path file, Function<String, String> env) {
        String fileName = file.getFileName().toString();
        String base = fileName.substring(0, fileName.lastIndexOf('.'));
        JsonNode root;
        try {
            root = YAML.readTree(Files.readString(file));
        } catch (IOException | RuntimeException e) {
            throw new LlmException(LlmException.Kind.CONFIG, "not readable YAML");
        }
        if (root == null || !root.isObject()) {
            throw new LlmException(LlmException.Kind.CONFIG, "the file is empty or not a mapping");
        }
        String name = text(root, "name", base);
        if (!NAME.matcher(name).matches()) {
            throw new LlmException(LlmException.Kind.CONFIG, "name '" + name + "' must be letters, digits, '.', '_' or '-'");
        }
        String type = text(root, "type", "");
        if (type.isEmpty()) {
            throw new LlmException(LlmException.Kind.CONFIG, "type is missing (openai, anthropic, ...)");
        }
        Map<String, String> settings = new LinkedHashMap<>();
        for (var it = root.fields(); it.hasNext();) {
            var e = it.next();
            String k = e.getKey();
            if (java.util.List.of("name", "type", "enabled", "base-url", "api-key", "timeout", "retries", "default-model", "models").contains(k)) {
                continue;
            }
            flatten(k, e.getValue(), settings);
        }
        requireProxyPasswordReference(settings, env);
        settings.replaceAll((k, v) -> k.startsWith("header.") ? McpServerConfigLoader.header(k.substring(7), v, env) : v);
        List<ProviderConfig.ModelConfig> models = new ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (JsonNode m : root.path("models")) {
            String mn = text(m, "name", "");
            if (!NAME.matcher(mn).matches()) {
                throw new LlmException(LlmException.Kind.CONFIG, "a model needs a logical name (letters, digits, '.', '_', '-')");
            }
            if (!names.add(mn)) {
                throw new LlmException(LlmException.Kind.CONFIG, "model '" + mn + "' is listed twice");
            }
            Set<Capability> caps = new java.util.LinkedHashSet<>();
            for (JsonNode c : m.path("capabilities")) {
                try {
                    caps.add(Capability.of(c.asText()));
                } catch (IllegalArgumentException e) {
                    throw new LlmException(LlmException.Kind.CONFIG, "model '" + mn + "': unknown capability '" + c.asText() + "'");
                }
            }
            models.add(new ProviderConfig.ModelConfig(mn, text(m, "id", mn), text(m, "kind", "chat"),
                    m.hasNonNull("max-tokens") ? m.get("max-tokens").asInt() : null,
                    m.hasNonNull("temperature") ? m.get("temperature").asDouble() : null, caps,
                    m.path("cost-per-1k-input").asDouble(0), m.path("cost-per-1k-output").asDouble(0)));
        }
        return new ProviderConfig(name, type, root.path("enabled").asBoolean(true), text(root, "base-url", ""),
                resolveKey(text(root, "api-key", ""), env), duration(text(root, "timeout", "")), root.path("retries").asInt(0),
                text(root, "default-model", ""), settings, models);
    }

    /** The proxy password is an {@code ${ENV}} reference or a {@code proxy.password-file}, never the value itself. */
    static void requireProxyPasswordReference(Map<String, String> settings, Function<String, String> env) {
        String pw = settings.get("proxy.password");
        if (pw != null && !pw.isBlank() && !pw.strip().matches("\\$\\{[A-Za-z_][A-Za-z0-9_]*(:-?[^}]*)?}")) {
            throw new LlmException(LlmException.Kind.CONFIG,
                    "proxy.password must be an ${ENV_NAME} placeholder (or use proxy.password-file); passwords are never written into a file");
        }
        if (pw != null && !pw.isBlank()) {
            settings.put("proxy.password", McpServerConfigLoader.header("password", pw.strip(), env));       // resolved here, like the api-key
        }
    }

    /** {@code ${NAME}} or {@code ${NAME:-fallback}} read from the environment; anything else is a literal and refused. */
    public static String resolveKey(String raw, Function<String, String> env) {
        if (raw.isEmpty()) {
            return "";
        }
        Matcher m = ENV.matcher(raw.strip());
        if (!m.matches()) {
            throw new LlmException(LlmException.Kind.CONFIG,
                    "api-key must be an ${ENV_NAME} placeholder; keys are never written into a file");
        }
        String v = env.apply(m.group(1));
        return v == null || v.isBlank() ? (m.group(2) == null ? "" : m.group(2)) : v.strip();
    }

    static Duration duration(String s) {
        if (s.isEmpty()) {
            return null;
        }
        try {
            String t = s.strip().toLowerCase(java.util.Locale.ROOT);
            if (t.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(t.substring(0, t.length() - 2).strip()));
            }
            if (t.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(t.substring(0, t.length() - 1).strip()));
            }
            if (t.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(t.substring(0, t.length() - 1).strip()));
            }
            return Duration.ofSeconds(Long.parseLong(t));
        } catch (NumberFormatException e) {
            throw new LlmException(LlmException.Kind.CONFIG, "timeout '" + s + "' is not a duration such as 30s");
        }
    }

    static void flatten(String prefix, JsonNode v, Map<String, String> out) {
        if (v.isObject()) {
            v.fields().forEachRemaining(e -> flatten(prefix + "." + e.getKey(), e.getValue(), out));
        } else if (v.isArray()) {
            for (int i = 0; i < v.size(); i++) {
                flatten(prefix + "." + i, v.get(i), out);
            }
        } else if (v.isValueNode() && !v.isNull()) {
            out.put(prefix, v.asText());
        }
    }

    private static String text(JsonNode n, String key, String dflt) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() || v.asText().isBlank() ? dflt : v.asText().strip();
    }
}
