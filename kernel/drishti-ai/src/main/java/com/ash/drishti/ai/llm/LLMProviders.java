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
package com.ash.drishti.ai.llm;

import com.ash.drishti.ai.LlmException;
import com.ash.drishti.ai.ProviderConfig;
import com.ash.drishti.ai.ProviderConfigLoader;
import com.ash.drishti.ai.ProviderFactories;
import com.ash.drishti.ai.ProviderFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The provider registry: types to {@link LLMProviderFactory}. The shipped types ({@code openai}, {@code anthropic}, {@code mock}, and any wire-level
 * {@code ProviderFactory} found by ServiceLoader) are there from the start; {@link #register} adds or replaces one (Python's {@code register_provider}).
 * Mirrors {@code core/ai/llm/registry.py} and the factory's {@code build} step.
 */
public final class LLMProviders {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, LLMProviderFactory> FACTORIES = new ConcurrentHashMap<>();

    static {
        for (ProviderFactory f : ProviderFactories.discover(List.of())) {
            register(wrap(f, System::getenv));
        }
    }

    private LLMProviders() {}

    /** Adds or replaces the factory of {@code factory.type()}. */
    public static void register(LLMProviderFactory factory) {
        FACTORIES.put(factory.type().toLowerCase(java.util.Locale.ROOT), factory);
    }

    public static void unregister(String type) {
        FACTORIES.remove(type.toLowerCase(java.util.Locale.ROOT));
    }

    /** Type to factory, sorted. */
    public static Map<String, LLMProviderFactory> registered() {
        return new TreeMap<>(FACTORIES);
    }

    /** Python's {@code LLMProvider.from_settings(name, config)} for the type the block names ({@code type:}) or {@code type}. */
    public static LLMProvider fromSettings(String type, String name, Map<String, Object> config) {
        LLMProviderFactory f = FACTORIES.get(type == null ? "" : type.toLowerCase(java.util.Locale.ROOT));
        if (f == null) {
            throw new LlmException(LlmException.Kind.CONFIG, "no provider type '" + type + "' is installed (have: " + registered().keySet() + ")");
        }
        return f.fromSettings(name, config);
    }

    /** A wire-level factory as a canonical one; the settings block is the same one a {@code config/ai/<name>.yaml} file holds. */
    public static LLMProviderFactory wrap(ProviderFactory wire, Function<String, String> env) {
        return new LLMProviderFactory() {
            @Override
            public String type() {
                return wire.type();
            }

            @Override
            public LLMProvider fromSettings(String name, Map<String, Object> config) {
                JsonNode node = JSON.valueToTree(config == null ? Map.of() : config);
                if (!node.hasNonNull("type")) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("type", wire.type());
                }
                ProviderConfig c = ProviderConfigLoader.fromNode(name, node, env);
                return new ProviderBase(c, wire.create(c));
            }
        };
    }
}
