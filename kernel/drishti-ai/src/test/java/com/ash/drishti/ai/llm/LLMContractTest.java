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

import com.ash.drishti.ai.CallContext;
import com.ash.drishti.ai.LlmException;
import com.ash.drishti.ai.LlmGateway;
import com.ash.drishti.ai.LlmPolicy;
import com.ash.drishti.ai.LlmRegistry;
import com.ash.drishti.ai.ProviderConfigLoader;
import com.ash.drishti.ai.ProviderFactories;
import com.ash.drishti.ai.llm.Canonical.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The shared LLM contract ({@code contract/llm_contract.json}) against the mock provider, through the canonical abstractions ({@link LLMProvider}, {@link LLMModel}).
 * The console runs the same file in {@code tests/test_llm_contract.py}; if the two layers drift, one of the tests fails.
 */
class LLMContractTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static JsonNode contract;
    static LLMProvider provider;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws Exception {
        try (InputStream in = LLMContractTest.class.getResourceAsStream("/contract/llm_contract.json")) {
            contract = JSON.readTree(in);
        }
        JsonNode p = contract.get("provider");
        provider = LLMProviders.fromSettings(p.get("type").asText(), p.get("name").asText(), JSON.convertValue(p.get("config"), Map.class));
    }

    static List<ChatMessage> messages(JsonNode m) {
        List<ChatMessage> out = new ArrayList<>();
        m.forEach(x -> out.add(new ChatMessage(x.get(0).asText(), x.get(1).asText(), null, null, null, null)));
        return out;
    }

    @Test
    void theCatalogueListsTheModelsWithTheirKindAndQualifiedId() {
        assertThat(provider.name()).isEqualTo("contract");
        assertThat(provider.active()).isTrue();
        assertThat(provider.models()).hasSameSizeAs(contract.get("catalogue"));
        int i = 0;
        for (JsonNode want : contract.get("catalogue")) {
            ModelInfo got = provider.models().get(i++);
            assertThat(got.id()).isEqualTo(want.get("id").asText());
            assertThat(got.kind()).isEqualTo(want.get("kind").asText());
            assertThat(got.qualifiedId()).isEqualTo(want.get("qualified_id").asText());
        }
        assertThat(provider.health().ok()).isTrue();
        assertThat(provider.describeConfig()).containsEntry("type", "mock").containsEntry("apiKey", "");
    }

    @Test
    void chatScenariosGiveTheSameAnswersAsInTheConsole() {
        LLMModel model = provider.chatModel("");                                     // the provider's default
        assertThat(model.info().id()).isEqualTo("chat");
        for (JsonNode s : contract.get("chat")) {
            JsonNode want = s.get("expect");
            ChatCompletionRequest req = ChatCompletionRequest.of("chat", messages(s.get("messages")));
            if (want.has("error")) {
                assertThatThrownBy(() -> model.chatCompletionsCreate(req)).as(s.get("name").asText()).isInstanceOf(LlmException.class)
                        .satisfies(e -> assertThat(((LlmException) e).kind().name()).isEqualTo(want.get("error").asText()));
                continue;
            }
            ChatCompletion c = model.chatCompletionsCreate(req);
            assertThat(c.finishReason()).as(s.get("name").asText()).isEqualTo(want.get("finish_reason").asText());
            if (want.has("text")) {
                assertThat(c.text()).as(s.get("name").asText()).isEqualTo(want.get("text").asText());
            }
            if (want.has("tool")) {
                assertThat(c.toolCalls()).hasSize(1);
                assertThat(c.toolCalls().get(0).function().name()).isEqualTo(want.get("tool").asText());
                assertThat(c.toolCalls().get(0).function().arguments()).isEqualTo(want.get("tool_arguments").asText());
            }
            assertThat(c.usage().totalTokens()).isPositive();
            assertThat(c.drishti().qualifiedModel()).isEqualTo("contract/chat");
        }
    }

    @Test
    void aStreamIsTheCompletionInChunks() {
        JsonNode s = contract.get("stream");
        LLMModel model = provider.model("chat", "chat");
        Iterator<ChatCompletionChunk> it = model.chatCompletionsStream(ChatCompletionRequest.of("chat", messages(s.get("messages"))));
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        it.forEachRemaining(chunks::add);
        assertThat(chunks).hasSize(s.get("chunks").asInt());
        assertThat(String.join("", chunks.stream().map(ChatCompletionChunk::delta).toList())).isEqualTo(s.get("joined").asText());
        assertThat(chunks.get(chunks.size() - 1).choices().get(0).finishReason()).isEqualTo(s.get("last_finish_reason").asText());
    }

    @Test
    void embeddingsGiveOneVectorPerInputOfTheConfiguredSize() {
        JsonNode e = contract.get("embeddings");
        List<String> in = new ArrayList<>();
        e.get("input").forEach(x -> in.add(x.asText()));
        EmbeddingsResponse r = provider.embeddingModel("vec").embeddingsCreate(EmbeddingsRequest.of("vec", in));
        assertThat(r.data()).hasSize(e.get("count").asInt());
        assertThat(r.vectors().get(0)).hasSize(e.get("dimensions").asInt());
        assertThat(provider.model("vec", "").info().kind()).isEqualTo("embedding");
    }

    @Test
    void refusalsCarryTheSameKindsAsInTheConsole() {
        for (JsonNode s : contract.get("refusals")) {
            String model = s.get("model").asText();
            assertThatThrownBy(() -> {
                LLMModel m = provider.model(model, "");
                if ("chat".equals(s.get("call").asText())) {
                    m.chatCompletionsCreate(ChatCompletionRequest.of(model, List.of(ChatMessage.user("x"))));
                } else {
                    m.embeddingsCreate(EmbeddingsRequest.of(model, List.of("x")));
                }
            }).as(s.get("name").asText()).isInstanceOf(LlmException.class)
                    .satisfies(x -> assertThat(((LlmException) x).kind().name()).isEqualTo(s.get("error").asText()));
        }
    }

    @Test
    void theGovernedFactoryAppliesThePolicyToCanonicalCalls() {
        List<String> seen = new ArrayList<>();
        LlmPolicy policy = new LlmPolicy() {
            @Override public com.ash.drishti.ai.ChatRequest before(CallContext ctx, com.ash.drishti.ai.ModelInfo model, com.ash.drishti.ai.ChatRequest request) {
                seen.add(ctx.feature() + ":" + request.messages().get(0).content());
                return request.withText(request.system(), List.of(new com.ash.drishti.ai.ChatMessage(com.ash.drishti.ai.ChatMessage.Role.USER, "masked")));
            }

            @Override public void after(CallContext ctx, com.ash.drishti.ai.ModelInfo model, com.ash.drishti.ai.ChatRequest request, com.ash.drishti.ai.ChatResponse response,
                    LlmException failure, long millis) {}
        };
        JsonNode p = contract.get("provider");
        LlmRegistry reg = new LlmRegistry(ProviderFactories.discover(List.of()), () -> new ProviderConfigLoader.Loaded(
                List.of(ProviderConfigLoader.fromNode("contract", JSON.valueToTree(withType(p)), System::getenv)), Map.of()), "contract/chat");
        reg.reload();
        LLMFactory factory = new LLMFactory(new LlmGateway(reg, policy), null);
        LLMModel governed = factory.model("contract/chat", "chat", new CallContext("ann", java.util.Set.of(), "unit"));
        ChatCompletion c = governed.chatCompletionsCreate(ChatCompletionRequest.of("contract/chat", List.of(ChatMessage.user("secret ping"))));
        assertThat(seen).containsExactly("unit:secret ping");
        assertThat(c.text()).as("the policy's masked prompt is what the model saw").isEqualTo("echo: masked");
        assertThat(factory.providerNames()).containsExactly("contract");
        assertThat(LLMFactory.providerTypes()).contains("mock", "openai", "anthropic");
    }

    private static Map<String, Object> withType(JsonNode p) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = new java.util.LinkedHashMap<>(JSON.convertValue(p.get("config"), Map.class));
        m.put("type", p.get("type").asText());
        return m;
    }
}
