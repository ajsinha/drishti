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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;

/** Reads {@code config/mcp/*.yaml} (files ending {@code .example} are ignored); a bad file is reported and skipped. */
public final class McpServerConfigLoader {

    public record Loaded(List<McpServerConfig> servers, Map<String, String> errors) {}

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final java.util.Set<String> KNOWN = java.util.Set.of("name", "transport", "enabled", "url", "headers", "timeout", "allowed-tools");

    private McpServerConfigLoader() {}

    public static Loaded load(Path dir, Function<String, String> env) {
        List<McpServerConfig> ok = new ArrayList<>();
        Map<String, String> errors = new TreeMap<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return new Loaded(ok, errors);
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> Files.isRegularFile(p) && (p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    && !p.getFileName().toString().startsWith(".")).sorted().toList();
        } catch (IOException e) {
            errors.put(dir.toString(), "cannot read the folder");
            return new Loaded(ok, errors);
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Path f : files) {
            try {
                McpServerConfig c = parse(f, env);
                if (seen.add(c.name())) {
                    ok.add(c);
                } else {
                    errors.put(f.getFileName().toString(), "another file already defines server '" + c.name() + "'");
                }
            } catch (LlmException e) {
                errors.put(f.getFileName().toString(), e.getMessage());
            }
        }
        return new Loaded(ok, errors);
    }

    static McpServerConfig parse(Path file, Function<String, String> env) {
        String fn = file.getFileName().toString();
        JsonNode root;
        try {
            root = YAML.readTree(Files.readString(file));
        } catch (IOException | RuntimeException e) {
            throw new LlmException(LlmException.Kind.CONFIG, "not readable YAML");
        }
        if (root == null || !root.isObject()) {
            throw new LlmException(LlmException.Kind.CONFIG, "the file is empty or not a mapping");
        }
        String name = text(root, "name", fn.substring(0, fn.lastIndexOf('.')));
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) {
            throw new LlmException(LlmException.Kind.CONFIG, "name '" + name + "' must be letters, digits, '.', '_' or '-'");
        }
        String transport = text(root, "transport", "streamable-http");
        Map<String, String> headers = new LinkedHashMap<>();
        for (var it = root.path("headers").fields(); it.hasNext();) {
            var e = it.next();
            String v = header(e.getKey(), e.getValue().asText(""), env);
            headers.put(e.getKey(), v);
        }
        Map<String, String> settings = new LinkedHashMap<>();
        for (var it = root.fields(); it.hasNext();) {
            var e = it.next();
            if (!KNOWN.contains(e.getKey())) {
                ProviderConfigLoader.flatten(e.getKey(), e.getValue(), settings);
            }
        }
        ProviderConfigLoader.requireProxyPasswordReference(settings, env);
        List<String> allowed = new ArrayList<>();
        root.path("allowed-tools").forEach(n -> allowed.add(n.asText()));
        return new McpServerConfig(name, transport, root.path("enabled").asBoolean(true), text(root, "url", ""), headers,
                ProviderConfigLoader.duration(text(root, "timeout", "")), allowed, settings);
    }

    /** {@code Bearer ${TOKEN}}: every placeholder comes from the environment; a credential header with no placeholder is refused. */
    static String header(String name, String raw, Function<String, String> env) {
        if (!raw.contains("${")) {
            if (name.matches("(?i).*(auth|key|token|secret|cookie).*") && !raw.isBlank()) {
                throw new LlmException(LlmException.Kind.CONFIG, "header " + name + " must use an ${ENV_NAME} placeholder; credentials are never written into a file");
            }
            return raw;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-?([^}]*))?}").matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = env.apply(m.group(1));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(v == null || v.isBlank() ? (m.group(2) == null ? "" : m.group(2)) : v.strip()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String text(JsonNode n, String key, String dflt) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() || v.asText().isBlank() ? dflt : v.asText().strip();
    }
}
