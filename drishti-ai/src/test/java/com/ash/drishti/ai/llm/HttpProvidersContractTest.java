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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.ai.FakeLlmServer;
import com.ash.drishti.ai.LlmException;
import com.ash.drishti.ai.llm.Canonical.ChatMessage;
import com.ash.drishti.ai.llm.Canonical.FunctionDefinition;
import com.ash.drishti.ai.llm.Canonical.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The shared contract of the HTTP providers ({@code contract/llm_contract_http.json}) against each of five providers (an OpenAI-compatible preset, Ollama, Gemini, Cohere and
 * Bedrock), each talking to a fake server of its own API on loopback. The console runs the same file in {@code tests/test_llm_providers.py}; if the layers drift, one fails.
 */
class HttpProvidersContractTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static JsonNode contract;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = HttpProvidersContractTest.class.getResourceAsStream("/contract/llm_contract_http.json")) {
            contract = JSON.readTree(in);
        }
    }

    static Stream<JsonNode> cases() throws Exception {
        try (InputStream in = HttpProvidersContractTest.class.getResourceAsStream("/contract/llm_contract_http.json")) {
            List<JsonNode> out = new ArrayList<>();
            JSON.readTree(in).get("providers").forEach(out::add);
            return out.stream();
        }
    }

    static List<ChatMessage> messages(JsonNode m) {
        List<ChatMessage> out = new ArrayList<>();
        m.forEach(x -> out.add(new ChatMessage(x.get(0).asText(), x.get(1).asText(), null, null, null, null)));
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @SuppressWarnings("unchecked")
    void theSharedContractAgainstEachHttpProvider(JsonNode c) throws Exception {
        try (FakeLlmServer fake = FakeLlmServer.start(c.get("fake").asText())) {
            Map<String, Object> cfg = new LinkedHashMap<>(JSON.convertValue(c.get("config"), Map.class));
            cfg.put("type", c.get("type").asText());
            cfg.put("base-url", fake.url());
            cfg.put("timeout", "5s");
            ProviderBase p = HttpProvidersTest.build(c.get("type").asText(), cfg, HttpProvidersTest.ENV);
            assertThat(p.models()).hasSameSizeAs(contract.get("catalogue"));
            int i = 0;
            for (JsonNode want : contract.get("catalogue")) {
                ModelInfo got = p.models().get(i++);
                assertThat(got.id()).isEqualTo(want.get("id").asText());
                assertThat(got.kind()).isEqualTo(want.get("kind").asText());
                assertThat(got.qualifiedId()).isEqualTo("p/" + want.get("id").asText());
            }
            LLMModel model = p.chatModel("", Map.of());
            assertThat(model.info().id()).isEqualTo("chat");
            for (JsonNode s : contract.get("chat")) {
                JsonNode want = s.get("expect");
                List<ToolDefinition> tools = want.has("tool")
                        ? List.of(new ToolDefinition("function", new FunctionDefinition("get_weather", null, "{\"type\":\"object\"}", null))) : null;
                ChatCompletionRequest req = new ChatCompletionRequest("chat", messages(s.get("messages")), tools, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null);
                if (want.has("error")) {
                    assertThatThrownBy(() -> model.chatCompletionsCreate(req)).as(s.get("name").asText()).isInstanceOf(LlmException.class)
                            .satisfies(e -> assertThat(((LlmException) e).kind().name()).isEqualTo(want.get("error").asText()));
                    continue;
                }
                ChatCompletion got = model.chatCompletionsCreate(req);
                assertThat(got.finishReason()).as(s.get("name").asText()).isEqualTo(want.get("finish_reason").asText());
                if (want.has("text")) {
                    assertThat(got.text()).as(s.get("name").asText()).isEqualTo(want.get("text").asText());
                }
                if (want.has("tool")) {
                    assertThat(got.toolCalls()).hasSize(1);
                    assertThat(got.toolCalls().get(0).function().name()).isEqualTo(want.get("tool").asText());
                    assertThat(got.toolCalls().get(0).function().arguments()).isEqualTo(want.get("tool_arguments").asText());
                }
                assertThat(got.usage().totalTokens()).isPositive();
                assertThat(got.drishti().qualifiedModel()).isEqualTo("p/chat");
            }
            JsonNode st = contract.get("stream");
            List<ChatCompletionChunk> chunks = new ArrayList<>();
            model.chatCompletionsStream(ChatCompletionRequest.of("chat", messages(st.get("messages")))).forEachRemaining(chunks::add);
            assertThat(chunks).hasSize(st.get("chunks").asInt());
            assertThat(String.join("", chunks.stream().map(ChatCompletionChunk::delta).toList())).isEqualTo(st.get("joined").asText());
            assertThat(chunks.get(chunks.size() - 1).choices().get(0).finishReason()).isEqualTo(st.get("last_finish_reason").asText());
            JsonNode e = contract.get("embeddings");
            List<String> in = new ArrayList<>();
            e.get("input").forEach(x -> in.add(x.asText()));
            EmbeddingsResponse r = p.embeddingModel("").embeddingsCreate(EmbeddingsRequest.of("", in));
            assertThat(r.data()).hasSize(e.get("count").asInt());
            assertThat(r.vectors().get(0)).hasSize(e.get("dimensions").asInt());
            for (JsonNode ref : contract.get("refusals")) {
                String name = ref.get("model").asText();
                assertThatThrownBy(() -> {
                    LLMModel m = p.model(name, "");
                    if ("chat".equals(ref.get("call").asText())) {
                        m.chatCompletionsCreate(ChatCompletionRequest.of(name, List.of(ChatMessage.user("x"))));
                    } else {
                        m.embeddingsCreate(EmbeddingsRequest.of(name, List.of("x")));
                    }
                }).as(ref.get("name").asText()).isInstanceOf(LlmException.class)
                        .satisfies(x -> assertThat(((LlmException) x).kind().name()).isEqualTo(ref.get("error").asText()));
            }
        }
    }

    @org.junit.jupiter.api.Test
    void theTwoCatalogueCopiesAreIdentical() throws Exception {
        java.nio.file.Path console = java.nio.file.Path.of("..", "drishti-console", "core", "ai", "providers", "llm_catalog.json");
        try (InputStream in = getClass().getResourceAsStream("/ai/llm_catalog.json")) {
            assertThat(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(java.nio.file.Files.readString(console));
        }
    }
}
