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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A provider that speaks JSON over HTTP to one vendor API through {@link JsonHttp}: the shared base of the providers added from sajhamcpserver
 * ({@code JsonHttpProvider} in the console). A subclass implements {@link #chat} (and {@link #embed} where the API has embeddings), and may override
 * {@link #check}, {@link #probe} and {@link #liveModels}.
 */
public abstract class HttpProvider extends BaseProvider {

    protected final JsonHttp http;

    protected HttpProvider(ProviderConfig c, String base, Set<Capability> defaultChatCapabilities) {
        super(c, defaultChatCapabilities);
        this.http = new JsonHttp(c, base);
    }

    protected abstract ChatResponse chat(ModelInfo info, ChatRequest request);

    protected List<float[]> embed(ModelInfo info, List<String> texts) {
        throw new LlmException(LlmException.Kind.UNSUPPORTED, "this provider type has no embeddings");
    }

    @Override
    public Optional<LlmModel> chatModel(String logicalName) {
        return find(logicalName, false).map(info -> new LlmModel() {
            @Override
            public ModelInfo info() {
                return info;
            }

            @Override
            public ChatResponse chat(ChatRequest r) {
                return HttpProvider.this.chat(info, r);
            }
        });
    }

    @Override
    public Optional<EmbeddingModel> embeddingModel(String logicalName) {
        return find(logicalName, true).map(info -> new EmbeddingModel() {
            @Override
            public ModelInfo info() {
                return info;
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                return HttpProvider.this.embed(info, texts);
            }
        });
    }

    @Override
    public String tlsNote() {
        return http.tlsNote();
    }

    @Override
    public List<Map<String, String>> tlsCertificates() {
        return http.tlsCertificates();
    }

    @Override
    public Readiness probe() {
        Readiness r = check();
        if (!r.ok()) {
            return r;
        }
        try {
            return new Readiness(true, "the model list answered (" + liveModels().size() + " models)");
        } catch (LlmException e) {
            return new Readiness(false, e.getMessage());
        }
    }

    @Override
    public void close() {
        http.close();
    }

    // ---- helpers

    protected Duration timeout(ChatRequest r) {
        return r.timeout() != null ? r.timeout() : config.timeout();
    }

    /** The non-system turns as {@code {role, content}} with the role mapped to assistant or user. */
    protected static List<ChatMessage> turns(ChatRequest r) {
        List<ChatMessage> out = new ArrayList<>();
        for (ChatMessage m : r.messages()) {
            if (m.role() != ChatMessage.Role.SYSTEM) {
                out.add(m);
            }
        }
        return out;
    }

    protected static boolean assistant(ChatMessage m) {
        return m.role() == ChatMessage.Role.ASSISTANT;
    }

    protected static JsonNode schema(ToolSpec s) {
        try {
            return JsonHttp.JSON.readTree(s.parametersJsonSchema() == null ? "{\"type\":\"object\"}" : s.parametersJsonSchema());
        } catch (IOException e) {
            throw new LlmException(LlmException.Kind.UNSUPPORTED, "tool '" + s.name() + "' has an unreadable schema");
        }
    }

    /** OpenAI-style function tools ({@code type: function}). */
    protected static ArrayNode functionTools(ChatRequest r) {
        ArrayNode tools = JsonHttp.JSON.createArrayNode();
        for (ToolSpec s : r.tools()) {
            ObjectNode f = tools.addObject().put("type", "function").putObject("function");
            f.put("name", s.name()).put("description", s.description() == null ? "" : s.description());
            f.set("parameters", schema(s));
        }
        return tools;
    }

    protected static String compact(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? "{}" : n.toString();
    }

    protected static List<float[]> vectors(JsonNode arrays, int expected) {
        List<float[]> out = new ArrayList<>();
        if (arrays != null && arrays.isArray()) {
            for (JsonNode v : arrays) {
                float[] f = new float[v.size()];
                for (int i = 0; i < f.length; i++) {
                    f[i] = (float) v.get(i).asDouble();
                }
                out.add(f);
            }
        }
        if (out.size() != expected) {
            throw new LlmException(LlmException.Kind.FAILED, "the model endpoint returned the wrong number of embeddings");
        }
        return out;
    }
}
