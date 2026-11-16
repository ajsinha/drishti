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
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The curated model catalogue and the presets of the providers added after {@code openai} and {@code anthropic}
 * ({@code sajha/ai/llm/catalog.py}), read from {@code ai/llm_catalog.json} (an identical copy is in the console,
 * {@code core/ai/providers/catalog.py}). A preset is a base URL, the environment variables a key may be read from and a model
 * list; {@link #applyDefaults} fills a parsed provider block from it. Prices in the file are USD per million tokens, the layer's own
 * unit is per 1,000 tokens.
 */
public final class LlmCatalog {

    /** Settings that are secrets: an {@code ${ENV}} placeholder, resolved at load, never a literal. */
    public static final List<String> CREDENTIAL_SETTINGS = List.of("aws-access-key-id", "aws-secret-access-key", "aws-session-token", "client-secret");

    private static final JsonNode DATA = load();

    private LlmCatalog() {}

    private static JsonNode load() {
        try (InputStream in = LlmCatalog.class.getResourceAsStream("/ai/llm_catalog.json")) {
            return new ObjectMapper().readTree(in);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("ai/llm_catalog.json is missing or unreadable", e);
        }
    }

    /** The preset of a type, or null. */
    public static JsonNode preset(String type) {
        JsonNode p = DATA.path("presets").path(type == null ? "" : type.toLowerCase(Locale.ROOT));
        return p.isMissingNode() ? null : p;
    }

    public static Set<String> presetTypes() {
        Set<String> out = new LinkedHashSet<>();
        DATA.path("presets").fieldNames().forEachRemaining(out::add);
        return out;
    }

    /** A model id as a logical name (letters, digits, '.', '_', '-'; at most 64): {@code openai/gpt-oss-20b} is {@code openai_gpt-oss-20b}. */
    public static String logicalName(String id) {
        String n = id.replaceAll("[^A-Za-z0-9_.-]", "_");
        n = n.length() > 64 ? n.substring(0, 64) : n;
        return n.isEmpty() || !Character.isLetterOrDigit(n.charAt(0)) ? ("m" + n).substring(0, Math.min(64, n.length() + 1)) : n;
    }

    public static List<ProviderConfig.ModelConfig> models(String type) {
        List<ProviderConfig.ModelConfig> out = new ArrayList<>();
        for (JsonNode e : DATA.path("catalog").path(type == null ? "" : type.toLowerCase(Locale.ROOT))) {
            boolean emb = "embedding".equals(e.path("kind").asText());
            Set<Capability> caps = new LinkedHashSet<>();
            if (emb) {
                caps.add(Capability.EMBEDDINGS);
            } else {
                caps.add(Capability.CHAT);
                if (e.path("tools").asBoolean()) {
                    caps.add(Capability.TOOL_CALLS);
                }
                if (e.path("json").asBoolean()) {
                    caps.add(Capability.JSON_MODE);
                }
            }
            out.add(new ProviderConfig.ModelConfig(logicalName(e.path("id").asText()), e.path("id").asText(), emb ? "embedding" : "chat", null, null, caps,
                    e.path("input-per-mtok").asDouble() / 1000, e.path("output-per-mtok").asDouble() / 1000));
        }
        return out;
    }

    public static String defaultModel(String type, String kind) {
        String id = DATA.path("defaults").path(type == null ? "" : type.toLowerCase(Locale.ROOT)).path(kind).asText("");
        return id.isEmpty() ? "" : logicalName(id);
    }

    /** What {@link #applyDefaults} decided. */
    public record Applied(String baseUrl, String apiKey, List<ProviderConfig.ModelConfig> models, String defaultModel) {}

    /**
     * What a preset type fills in when the file leaves it out: the base URL (or {@code base-url-env}), the key from the vendor's
     * environment variable, the catalogue models (a model of the file with the same name wins; {@code catalogue: false} keeps only the
     * file's), settings from the vendor's environment, the default model. {@code settings} is changed in place.
     */
    public static Applied applyDefaults(String type, Map<String, String> settings, List<ProviderConfig.ModelConfig> fileModels, String baseUrl, String apiKey,
            String defaultModel, Function<String, String> env) {
        JsonNode pre = preset(type);
        if (pre == null) {
            return new Applied(baseUrl, apiKey, fileModels, defaultModel);
        }
        String base = !baseUrl.isEmpty() ? baseUrl : firstNonBlank(pre.path("base-url-env"), env);
        if (base.isEmpty()) {
            base = pre.path("base-url").asText("");
        }
        String key = !apiKey.isEmpty() ? apiKey : firstNonBlank(pre.path("key-env"), env);
        pre.path("settings-env").fields().forEachRemaining(e -> {
            if (settings.getOrDefault(e.getKey(), "").isBlank()) {
                String v = firstNonBlank(e.getValue(), env);
                if (!v.isEmpty()) {
                    settings.put(e.getKey(), v);
                }
            }
        });
        List<ProviderConfig.ModelConfig> models = new ArrayList<>(fileModels);
        Set<String> have = new HashSet<>();
        models.forEach(m -> have.add(m.name()));
        if (!"false".equalsIgnoreCase(settings.getOrDefault("catalogue", "true"))) {
            for (ProviderConfig.ModelConfig m : models(type)) {
                if (have.add(m.name())) {
                    models.add(m);
                }
            }
        }
        if ("azure_openai".equals(type.toLowerCase(Locale.ROOT))) {                  // deployments.<model id>: <deployment name> are this resource's models
            settings.keySet().stream().filter(k -> k.startsWith("deployments.")).sorted().toList().forEach(k -> {
                String id = k.substring("deployments.".length());
                if (have.add(logicalName(id))) {
                    boolean emb = id.contains("embed");
                    models.add(new ProviderConfig.ModelConfig(logicalName(id), id, emb ? "embedding" : "chat", null, null,
                            emb ? Set.of(Capability.EMBEDDINGS) : Set.of(Capability.CHAT, Capability.TOOL_CALLS, Capability.JSON_MODE), 0, 0));
                }
            });
        }
        String dflt = !defaultModel.isEmpty() ? defaultModel : defaultModel(type, "chat");
        String chosen = dflt;
        return new Applied(base, key, models, !chosen.isEmpty() && models.stream().noneMatch(m -> m.name().equals(chosen)) ? "" : chosen);
    }

    private static String firstNonBlank(JsonNode names, Function<String, String> env) {
        for (JsonNode n : names) {
            String v = env.apply(n.asText());
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return "";
    }
}
