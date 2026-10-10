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

import com.ash.drishti.api.tls.TlsHttp;
import com.ash.drishti.api.tls.TlsMaterial;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code type: anthropic}: the Anthropic Messages API over the JDK HTTP client (the existing Ask provider, ported behind the
 * interface; Spring AI's Anthropic starter is not used, to keep one HTTP stack with the shared {@code tls.*} module).
 * Settings: {@code base-url} (default https://api.anthropic.com), {@code messages-path} (/v1/messages), {@code anthropic-version}.
 */
public final class AnthropicProvider extends BaseProvider {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BODY = 4_000_000;

    private final HttpClient client;
    private final URI uri;

    AnthropicProvider(ProviderConfig c) {
        super(c, Set.of(Capability.TOOL_CALLS));
        String base = c.baseUrl().isEmpty() ? "https://api.anthropic.com" : c.baseUrl();
        try {
            this.uri = URI.create(base.replaceAll("/+$", "") + c.setting("messages-path", "/v1/messages"));
        } catch (IllegalArgumentException e) {
            throw new LlmException(LlmException.Kind.CONFIG, "base-url is not a valid URL");
        }
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(c.timeout()).followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1);
        TlsMaterial tls = TlsHttp.material(c.settings(), uri.toString(), "base-url");
        this.client = (tls == null ? b : TlsHttp.apply(b, tls)).build();
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
                return call(info, r);
            }
        });
    }

    private ChatResponse call(ModelInfo info, ChatRequest r) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", info.id());
        body.put("max_tokens", maxTokens(r, info, 1024));
        Double t = temperature(r, info);
        if (t != null) {
            body.put("temperature", t);
        }
        String sys = systemText(r);
        if (!sys.isEmpty()) {
            body.put("system", sys);
        }
        ArrayNode msgs = body.putArray("messages");
        for (ChatMessage m : r.messages()) {
            if (m.role() != ChatMessage.Role.SYSTEM) {
                msgs.addObject().put("role", m.role() == ChatMessage.Role.ASSISTANT ? "assistant" : "user").put("content", m.content());
            }
        }
        if (!r.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec s : r.tools()) {
                ObjectNode o = tools.addObject();
                o.put("name", s.name()).put("description", s.description());
                try {
                    o.set("input_schema", JSON.readTree(s.parametersJsonSchema() == null ? "{\"type\":\"object\"}" : s.parametersJsonSchema()));
                } catch (IOException e) {
                    throw new LlmException(LlmException.Kind.UNSUPPORTED, "tool '" + s.name() + "' has an unreadable schema");
                }
            }
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(r.timeout() != null ? r.timeout() : config.timeout())
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .header("anthropic-version", config.setting("anthropic-version", "2023-06-01"));
        if (!config.apiKey().isEmpty()) {
            b.header("x-api-key", config.apiKey());
        }
        config.settings().forEach((k, v) -> {
            if (k.startsWith("header.")) {
                b.header(k.substring(7), v);
            }
        });
        try {
            b.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
            HttpResponse<InputStream> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream in = resp.body()) {
                bytes = in.readNBytes(MAX_BODY);
            }
            if (resp.statusCode() / 100 != 2) {
                throw new LlmException(LlmException.Kind.FAILED, "the model endpoint answered HTTP " + resp.statusCode());
            }
            return parse(info, JSON.readTree(bytes));
        } catch (java.net.http.HttpTimeoutException | java.net.SocketTimeoutException e) {
            throw new LlmException(LlmException.Kind.TIMEOUT, "the model endpoint did not answer in time", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.FAILED, "the model call was interrupted", e);
        } catch (IOException e) {
            throw new LlmException(LlmException.Kind.FAILED, "the model endpoint could not be reached or answered something unreadable", e);
        }
    }

    private static ChatResponse parse(ModelInfo info, JsonNode j) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode block : j.path("content")) {
            String type = block.path("type").asText();
            if ("text".equals(type) && block.path("text").isTextual()) {
                text.append(block.path("text").asText());
            } else if ("tool_use".equals(type)) {
                calls.add(new ToolCall(block.path("id").asText(), block.path("name").asText(), block.path("input").toString()));
            }
        }
        if (text.isEmpty() && calls.isEmpty()) {
            throw new LlmException(LlmException.Kind.FAILED, "the model endpoint's answer has no text content");
        }
        int in = j.path("usage").path("input_tokens").asInt(0);
        int out = j.path("usage").path("output_tokens").asInt(0);
        return new ChatResponse(text.toString(), calls, j.path("model").asText(info.id()), j.path("stop_reason").asText(""),
                new Usage(in, out, info.cost(in, out)));
    }

    /** {@code type: anthropic}. */
    public static final class Factory implements ProviderFactory {
        @Override
        public String type() {
            return "anthropic";
        }

        @Override
        public LlmProvider create(ProviderConfig config) {
            return new AnthropicProvider(config);
        }
    }
}
