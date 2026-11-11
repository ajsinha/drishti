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
import com.ash.drishti.ai.ProviderFactories;
import com.ash.drishti.ai.ProviderFactory;
import com.ash.drishti.ai.llm.Canonical.ChatMessage;
import com.ash.drishti.ai.llm.Canonical.FunctionDefinition;
import com.ash.drishti.ai.llm.Canonical.ResponseFormat;
import com.ash.drishti.ai.llm.Canonical.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The providers added from sajhamcpserver (OpenAI-compatible presets, Azure OpenAI, Gemini, Cohere, Ollama, Bedrock) against fake servers on loopback: the request (path,
 * authentication header, body fields), a reply, errors (401, 429, 500: the message names the cause and never carries the body), a timeout, the model list, health,
 * {@code describeConfig} with every secret masked. The console has the same tests in {@code tests/test_llm_providers.py}. No real network.
 */
class HttpProvidersTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Map<String, String> ENV = Map.of("THE_KEY", "k-123", "AKID", "AKIDEXAMPLE", "ASECRET", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "AWS_TOKEN", "tok-session");
    static final List<String> PRESETS = List.of("groq", "together", "fireworks", "deepseek", "xai", "openrouter", "perplexity", "huggingface", "vllm", "lmstudio", "mistral",
            "openai_compatible");

    static ProviderBase make(String type, String url, Map<String, Object> extra) {
        return make(type, url, extra, ENV);
    }

    static ProviderBase make(String type, String url, Map<String, Object> extra, Map<String, String> env) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", type);
        cfg.put("base-url", url);
        cfg.put("timeout", "5s");
        if (!List.of("ollama", "bedrock").contains(type)) {
            cfg.put("api-key", "${THE_KEY}");
        }
        cfg.putAll(extra);
        return build(type, cfg, env);
    }

    static ProviderBase build(String type, Map<String, Object> cfg, Map<String, String> env) {
        ProviderFactory f = ProviderFactories.discover(List.of()).stream().filter(x -> x.type().equals(type)).findFirst().orElseThrow();
        return (ProviderBase) LLMProviders.wrap(f, env::get).fromSettings("p", cfg);
    }

    static Map<String, Object> m(String name) {
        return Map.of("name", name);
    }

    static Map<String, Object> models(Map<String, Object>... ms) {
        return Map.of("catalogue", false, "models", List.of(ms));
    }

    static Map<String, Object> merge(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> out = new LinkedHashMap<>(a);
        out.putAll(b);
        return out;
    }

    static ChatCompletion ask(ProviderBase p, String text) {
        return p.chatModel("", Map.of()).chatCompletionsCreate(ChatCompletionRequest.of("m", List.of(ChatMessage.user(text))));
    }

    static JsonNode body(FakeLlmServer.Seen s) throws Exception {
        return JSON.readTree(s.body());
    }

    @SafeVarargs
    private static Map<String, Object> only(Map<String, Object>... ms) {
        return models(ms);
    }

    // ---- registry, catalogue, presets

    @Test
    void everyTypeIsRegisteredAndEachPresetHasABaseUrlAModelListAndAKeyVariable() {
        Map<String, String> urls = Map.ofEntries(Map.entry("groq", "https://api.groq.com/openai/v1"), Map.entry("together", "https://api.together.xyz/v1"),
                Map.entry("fireworks", "https://api.fireworks.ai/inference/v1"), Map.entry("deepseek", "https://api.deepseek.com/v1"), Map.entry("xai", "https://api.x.ai/v1"),
                Map.entry("openrouter", "https://openrouter.ai/api/v1"), Map.entry("perplexity", "https://api.perplexity.ai"),
                Map.entry("huggingface", "https://router.huggingface.co/v1"), Map.entry("vllm", "http://localhost:8000/v1"), Map.entry("lmstudio", "http://localhost:1234/v1"),
                Map.entry("mistral", "https://api.mistral.ai/v1"), Map.entry("gemini", "https://generativelanguage.googleapis.com"), Map.entry("cohere", "https://api.cohere.com"),
                Map.entry("ollama", "http://localhost:11434"));
        assertThat(LLMProviders.registered().keySet()).containsAll(PRESETS).contains("azure_openai", "gemini", "cohere", "ollama", "bedrock");
        urls.forEach((t, u) -> {
            ProviderBase p = build(t, Map.of("type", t), Map.of());
            assertThat(p.describeConfig()).as(t).containsEntry("baseUrl", u);
            if (!List.of("vllm", "lmstudio").contains(t)) {
                assertThat(p.models()).as(t).isNotEmpty();
            }
        });
        ProviderBase g = build("groq", Map.of("type", "groq"), Map.of("GROQ_API_KEY", "from-env"));
        assertThat(g.describeConfig()).containsEntry("apiKey", "********");
        assertThat(g.health().ok()).isTrue();
        ProviderBase none = build("groq", Map.of("type", "groq"), Map.of());
        assertThat(none.health().ok()).isFalse();
        assertThat(none.health().detail()).contains("GROQ_API_KEY");
    }

    @Test
    void aFileModelOverridesTheCatalogueAndCatalogueFalseKeepsOnlyTheFiles() {
        ProviderBase p = build("groq", Map.of("type", "groq", "models", List.of(Map.of("name", "llama-3.3-70b-versatile", "id", "custom", "cost-per-1k-input", 1))), Map.of());
        assertThat(p.models()).hasSize(4);
        assertThat(build("groq", Map.of("type", "groq", "catalogue", false, "models", List.of(m("only"))), Map.of()).models()).extracting(ModelInfo::id).containsExactly("only");
        assertThat(p.chatModel("", Map.of()).info().id()).isEqualTo("llama-3.3-70b-versatile");
    }

    @Test
    void literalSecretsAreRefused() {
        assertThatThrownBy(() -> build("groq", Map.of("type", "groq", "api-key", "gsk_literal"), Map.of())).isInstanceOf(LlmException.class);
        assertThatThrownBy(() -> build("bedrock", Map.of("type", "bedrock", "aws-secret-access-key", "literal"), Map.of())).isInstanceOf(LlmException.class)
                .hasMessageContaining("placeholder");
        assertThatThrownBy(() -> build("azure_openai", Map.of("type", "azure_openai", "base-url", "https://a", "client-secret", "literal"), Map.of()))
                .isInstanceOf(LlmException.class);
    }

    // ---- OpenAI-compatible presets

    @ParameterizedTest
    @ValueSource(strings = {"groq", "together", "fireworks", "deepseek", "xai", "openrouter", "perplexity", "huggingface", "vllm", "lmstudio", "mistral", "openai_compatible"})
    @SuppressWarnings("unchecked")
    void presetsSendTheChatCompletionsRequestWithABearerKey(String type) throws Exception {
        try (var s = FakeLlmServer.start("openai")) {
            ProviderBase p = make(type, s.url(), merge(only(Map.of("name", "m", "id", "the-model", "cost-per-1k-input", 1.0, "cost-per-1k-output", 2.0)), Map.of("default-model", "m")));
            ChatCompletion c = ask(p, "ping");
            var seen = s.seen().get(0);
            JsonNode j = body(seen);
            assertThat(seen.method()).isEqualTo("POST");
            assertThat(seen.path()).isEqualTo("/chat/completions");
            assertThat(seen.header("Authorization")).isEqualTo("Bearer k-123");
            assertThat(j.path("model").asText()).isEqualTo("the-model");
            assertThat(j.path("messages").get(0).path("content").asText()).isEqualTo("ping");
            assertThat(j.path("max_tokens").asInt()).isEqualTo(1024);
            assertThat(j.path("stream").asBoolean()).isFalse();
            assertThat(c.text()).isEqualTo("echo: ping");
            assertThat(c.usage().totalTokens()).isEqualTo(8);
            assertThat(c.drishti().costUsd()).isEqualTo(0.011, org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aToolCallComesBackAndTheToolSchemaGoesOut() throws Exception {
        try (var s = FakeLlmServer.start("openai")) {
            ProviderBase p = make("groq", s.url(), only(m("m")));
            ChatCompletionRequest req = new ChatCompletionRequest("m", List.of(ChatMessage.user("weather in Oslo")),
                    List.of(new ToolDefinition("function", new FunctionDefinition("get_weather", "d", "{\"type\":\"object\"}", null))), null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null);
            ChatCompletion c = p.chatModel("m", Map.of()).chatCompletionsCreate(req);
            assertThat(c.toolCalls().get(0).function().name()).isEqualTo("get_weather");
            assertThat(c.toolCalls().get(0).function().arguments()).isEqualTo("{\"city\":\"Oslo\"}");
            assertThat(c.finishReason()).isEqualTo("tool_calls");
            assertThat(body(s.seen().get(0)).path("tools").get(0).path("function").path("name").asText()).isEqualTo("get_weather");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theMaxTokensParamSettingChoosesTheNameOfTheLimit() throws Exception {
        try (var s = FakeLlmServer.start("openai")) {
            ask(make("openai_compatible", s.url(), merge(only(m("m")), Map.of("max-tokens-param", "max_completion_tokens"))), "x");
            assertThat(body(s.seen().get(0)).has("max_completion_tokens")).isTrue();
            assertThat(body(s.seen().get(0)).has("max_tokens")).isFalse();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void embeddingsAndTheLiveModelListOfAKeylessServer() throws Exception {
        try (var s = FakeLlmServer.start("openai")) {
            ProviderBase p = make("vllm", s.url(), only(Map.of("name", "e", "kind", "embedding", "id", "bge")));
            EmbeddingsResponse r = p.embeddingModel("e").embeddingsCreate(EmbeddingsRequest.of("e", List.of("a", "b")));
            assertThat(r.data()).hasSize(2);
            assertThat(r.vectors().get(0)).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
            assertThat(s.seen().get(0).path()).isEqualTo("/embeddings");
            List<ModelInfo> live = p.liveModels();
            assertThat(live).extracting(ModelInfo::id).containsExactly("served-model", "served-embed");
            assertThat(live.get(1).kind()).isEqualTo("embedding");
            assertThat(live.get(0).source()).isEqualTo("live");
            assertThat(p.probe().ok()).isTrue();
            assertThat(make("groq", s.url(), only(m("m"))).liveModels()).isEmpty();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void errorsSayTheCauseAndNeverCarryTheBody() throws Exception {
        try (var s = FakeLlmServer.start("openai")) {
            ProviderBase p = make("together", s.url(), only(m("m")));
            assertThatThrownBy(() -> ask(p, "boom")).isInstanceOfSatisfying(LlmException.class, e -> {
                assertThat(e.kind()).isEqualTo(LlmException.Kind.FAILED);
                assertThat(e.getMessage()).contains("HTTP 500").doesNotContain("secret");
            });
            assertThatThrownBy(() -> ask(p, "limit")).isInstanceOfSatisfying(LlmException.class, e -> {
                assertThat(e.kind()).isEqualTo(LlmException.Kind.RATE_LIMITED);
                assertThat(e.getMessage()).contains("HTTP 429");
            });
            ProviderBase bad = build("together", merge(Map.of("type", "together", "base-url", s.url(), "api-key", "${OTHER}"), only(m("m"))), Map.of("OTHER", "wrong"));
            assertThatThrownBy(() -> ask(bad, "x")).hasMessageContaining("HTTP 401").hasMessageContaining("authentication failed").hasMessageNotContaining("wrong");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSlowServerIsATimeoutAndRetriesTryAgainOnAServerError() throws Exception {
        try (var s = FakeLlmServer.start("openai")) {
            ProviderBase slow = make("xai", s.url(), merge(only(m("m")), Map.of("timeout", "300ms")));
            assertThatThrownBy(() -> ask(slow, "slow")).isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.TIMEOUT));
            int before = s.seen().size();
            ProviderBase retrying = make("groq", s.url(), merge(only(m("m")), Map.of("retries", 2)));
            assertThatThrownBy(() -> ask(retrying, "boom")).isInstanceOf(LlmException.class);
            assertThat(s.seen().size() - before).isEqualTo(3);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void describeConfigMasksEverySecretAndKeepsTheRest() {
        ProviderBase p = build("groq", Map.of("type", "groq", "api-key", "${THE_KEY}", "header", Map.of("Authorization", "${THE_KEY}", "X-Team", "red"), "max-tokens-param", "max_tokens",
                "proxy", Map.of("url", "http://proxy:3128", "username", "u", "password", "${THE_KEY}")), ENV);
        Map<String, Object> d = p.describeConfig();
        assertThat(d.toString()).doesNotContain("k-123");
        assertThat(d).containsEntry("apiKey", "********");
        Map<String, String> s = (Map<String, String>) d.get("settings");
        assertThat(s).containsEntry("header.Authorization", "********").containsEntry("header.X-Team", "red").containsEntry("max-tokens-param", "max_tokens")
                .containsEntry("proxy.password", "********").containsEntry("proxy.url", "http://proxy:3128");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theProxySettingsAreHonoured() throws Exception {
        try (var s = FakeLlmServer.start("openai")) {
            ProviderBase p = make("groq", "http://upstream.invalid", merge(only(m("m")), Map.of("proxy", Map.of("url", s.url()))));
            assertThat(ask(p, "ping").text()).isEqualTo("echo: ping");
            assertThat(s.seen().get(0).header("Host")).startsWith("upstream.invalid");
        }
    }

    // ---- Azure OpenAI

    @Test
    void azureV1StyleSendsTheApiKeyHeaderAndTheDeploymentAsTheModel() throws Exception {
        try (var s = FakeLlmServer.start("azure")) {
            ProviderBase p = make("azure_openai", s.url(), Map.of("deployments", Map.of("gpt-4o", "my-dep")));
            assertThat(p.models()).extracting(ModelInfo::id).containsExactly("gpt-4o");
            assertThat(ask(p, "ping").text()).isEqualTo("echo: ping");
            var seen = s.seen().get(0);
            assertThat(seen.path()).isEqualTo("/openai/v1/chat/completions");
            assertThat(seen.header("api-key")).isEqualTo("k-123");
            assertThat(seen.header("Authorization")).isNull();
            assertThat(body(seen).path("model").asText()).isEqualTo("my-dep");
            assertThat(body(seen).has("max_completion_tokens")).isTrue();
        }
    }

    @Test
    void azureDeploymentsStylePutsTheDeploymentAndApiVersionInTheUrl() throws Exception {
        try (var s = FakeLlmServer.start("azure")) {
            ProviderBase p = make("azure_openai", s.url(), Map.of("api-style", "deployments", "api-version", "2024-10-21", "deployments", Map.of("gpt-4o", "my-dep", "text-embedding-3", "emb")));
            p.chatModel("gpt-4o", Map.of()).chatCompletionsCreate(ChatCompletionRequest.of("gpt-4o", List.of(ChatMessage.user("ping"))));
            assertThat(s.seen().get(0).path()).isEqualTo("/openai/deployments/my-dep/chat/completions?api-version=2024-10-21");
            p.embeddingModel("text-embedding-3").embeddingsCreate(EmbeddingsRequest.of("text-embedding-3", List.of("a")));
            assertThat(s.seen().get(1).path()).isEqualTo("/openai/deployments/emb/embeddings?api-version=2024-10-21");
        }
    }

    @Test
    void azureEntraGetsATokenFromTheAuthorityCachesItAndSendsItAsBearer() throws Exception {
        try (var s = FakeLlmServer.start("azure-entra")) {
            Map<String, Object> extra = new LinkedHashMap<>(Map.of("auth", "entra", "tenant-id", "tnt", "client-id", "cid", "client-secret", "${ASECRET}", "entra-authority", s.url(),
                    "deployments", Map.of("gpt-4o", "d")));
            Map<String, Object> cfg = new LinkedHashMap<>(Map.of("type", "azure_openai", "base-url", s.url(), "timeout", "5s"));
            cfg.putAll(extra);
            ProviderBase p = build("azure_openai", cfg, ENV);
            assertThat(p.health().ok()).isTrue();
            assertThat(p.health().detail()).contains("client_secret");
            ask(p, "one");
            ask(p, "two");
            var tokens = s.seen().stream().filter(x -> x.path().endsWith("/oauth2/v2.0/token")).toList();
            assertThat(tokens).hasSize(1);
            assertThat(tokens.get(0).path()).isEqualTo("/tnt/oauth2/v2.0/token");
            String form = tokens.get(0).body();
            assertThat(form).contains("grant_type=client_credentials").contains("client_id=cid").contains("scope=https%3A%2F%2Fcognitiveservices.azure.com%2F.default")
                    .contains("client_secret=");
            var chats = s.seen().stream().filter(x -> x.path().endsWith("/chat/completions")).toList();
            assertThat(chats).allSatisfy(x -> {
                assertThat(x.header("Authorization")).isEqualTo("Bearer entra-token-1");
                assertThat(x.header("api-key")).isNull();
            });
        }
    }

    @Test
    void azureWorkloadIdentityAssertsTheFederatedTokenAndManagedIdentityIsNotBuilt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        try (var s = FakeLlmServer.start("azure-entra")) {
            java.nio.file.Path f = tmp.resolve("fed.jwt");
            java.nio.file.Files.writeString(f, "jwt-from-file\n");
            Map<String, Object> cfg = new LinkedHashMap<>(Map.of("type", "azure_openai", "base-url", s.url(), "auth", "entra", "tenant-id", "t", "client-id", "c",
                    "federated-token-file", f.toString(), "entra-authority", s.url(), "deployments", Map.of("g", "d")));
            ProviderBase p = build("azure_openai", cfg, ENV);
            assertThat(p.health().detail()).contains("workload_identity");
            ask(p, "x");
            assertThat(s.seen().get(0).body()).contains("client_assertion=jwt-from-file").contains("client_assertion_type=urn%3Aietf%3Aparams%3Aoauth%3Aclient-assertion-type%3Ajwt-bearer");
            cfg.remove("federated-token-file");
            ProviderBase mi = build("azure_openai", cfg, ENV);
            assertThat(mi.health().ok()).isFalse();
            assertThat(mi.health().detail()).contains("managed identity is not built");
        }
    }

    @Test
    void azureWithoutAKeyIsDownAndWithoutAResourceIsRefused() {
        ProviderBase p = build("azure_openai", Map.of("type", "azure_openai", "base-url", "https://r.openai.azure.com"), Map.of());
        assertThat(p.health().ok()).isFalse();
        assertThat(p.health().detail()).contains("AZURE_OPENAI_API_KEY");
        assertThatThrownBy(() -> build("azure_openai", Map.of("type", "azure_openai"), Map.of())).isInstanceOf(LlmException.class);
    }

    // ---- Gemini

    @Test
    @SuppressWarnings("unchecked")
    void geminiGenerateContentRequestAndReply() throws Exception {
        try (var s = FakeLlmServer.start("gemini")) {
            ProviderBase p = make("gemini", s.url(), only(Map.of("name", "m", "id", "gemini-2.5-flash")));
            ChatCompletionRequest req = new ChatCompletionRequest("m", List.of(ChatMessage.system("be brief"), ChatMessage.user("ping")), null, null, null, null, 0.2, null, null, 50,
                    null, null, null, null, null, null, null, null, null);
            ChatCompletion c = p.chatModel("m", Map.of()).chatCompletionsCreate(req);
            var seen = s.seen().get(0);
            JsonNode j = body(seen);
            assertThat(seen.path()).isEqualTo("/v1beta/models/gemini-2.5-flash:generateContent");
            assertThat(seen.header("x-goog-api-key")).isEqualTo("k-123");
            assertThat(j.path("systemInstruction").path("parts").get(0).path("text").asText()).isEqualTo("be brief");
            assertThat(j.path("contents").get(0).path("role").asText()).isEqualTo("user");
            assertThat(j.path("generationConfig").path("temperature").asDouble()).isEqualTo(0.2);
            assertThat(j.path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(50);
            assertThat(c.text()).isEqualTo("echo: ping");
            assertThat(c.usage().totalTokens()).isEqualTo(8);
            assertThat(c.finishReason()).isEqualTo("stop");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void geminiToolsJsonModeEmbeddingsAndTheModelList() throws Exception {
        try (var s = FakeLlmServer.start("gemini")) {
            ProviderBase p = make("gemini", s.url(), only(Map.of("name", "m", "id", "g"), Map.of("name", "e", "id", "gemini-embedding-2", "kind", "embedding")));
            ChatCompletionRequest req = new ChatCompletionRequest("m", List.of(ChatMessage.user("weather in Oslo")),
                    List.of(new ToolDefinition("function", new FunctionDefinition("get_weather", null, "{\"type\":\"object\",\"additionalProperties\":false}", null))), null, null,
                    new ResponseFormat("json_object", null), null, null, null, null, null, null, null, null, null, null, null, null, null);
            ChatCompletion c = p.chatModel("m", Map.of()).chatCompletionsCreate(req);
            JsonNode j = body(s.seen().get(0));
            assertThat(c.toolCalls().get(0).function().name()).isEqualTo("get_weather");
            assertThat(c.finishReason()).isEqualTo("tool_calls");
            assertThat(j.path("generationConfig").path("responseMimeType").asText()).isEqualTo("application/json");
            assertThat(j.path("tools").get(0).path("functionDeclarations").get(0).has("parametersJsonSchema")).isTrue();
            assertThat(p.embeddingModel("e").embeddingsCreate(EmbeddingsRequest.of("e", List.of("a", "b"))).data()).hasSize(2);
            assertThat(s.seen().get(1).path()).isEqualTo("/v1beta/models/gemini-embedding-2:batchEmbedContents");
            assertThat(body(s.seen().get(1)).path("requests").get(0).path("taskType").asText()).isEqualTo("RETRIEVAL_DOCUMENT");
            assertThat(p.liveModels()).extracting(ModelInfo::id).containsExactly("served-model", "served-embed");
            assertThat(p.probe().ok()).isTrue();
            ProviderBase o = make("gemini", s.url(), merge(only(Map.of("name", "m", "id", "g")), Map.of("schema-mode", "openapi")));
            o.chatModel("m", Map.of()).chatCompletionsCreate(req);
            JsonNode d = body(s.seen().get(s.seen().size() - 1)).path("tools").get(0).path("functionDeclarations").get(0);
            assertThat(d.path("parameters").toString()).isEqualTo("{\"type\":\"object\"}");
            assertThat(d.has("parametersJsonSchema")).isFalse();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void geminiErrorsTimeoutAndVertexIsNotBuilt() throws Exception {
        try (var s = FakeLlmServer.start("gemini")) {
            ProviderBase p = make("gemini", s.url(), merge(only(m("m")), Map.of("timeout", "300ms")));
            assertKind(() -> ask(p, "boom"), LlmException.Kind.FAILED);
            assertKind(() -> ask(p, "limit"), LlmException.Kind.RATE_LIMITED);
            assertKind(() -> ask(p, "slow"), LlmException.Kind.TIMEOUT);
            assertThatThrownBy(() -> make("gemini", s.url(), Map.of("platform", "vertex"))).hasMessageContaining("not built");
        }
    }

    static void assertKind(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, LlmException.Kind kind) {
        assertThatThrownBy(call).isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(kind));
    }

    // ---- Cohere

    @Test
    @SuppressWarnings("unchecked")
    void cohereV2ChatEmbedAndModels() throws Exception {
        try (var s = FakeLlmServer.start("cohere")) {
            ProviderBase p = make("cohere", s.url(), only(Map.of("name", "m", "id", "command-a-03-2025"), Map.of("name", "e", "id", "embed-v4.0", "kind", "embedding")));
            assertThat(ask(p, "ping").text()).isEqualTo("echo: ping");
            var seen = s.seen().get(0);
            assertThat(seen.path()).isEqualTo("/v2/chat");
            assertThat(seen.header("Authorization")).isEqualTo("Bearer k-123");
            assertThat(body(seen).path("model").asText()).isEqualTo("command-a-03-2025");
            ChatCompletionRequest req = new ChatCompletionRequest("m", List.of(ChatMessage.user("weather")),
                    List.of(new ToolDefinition("function", new FunctionDefinition("get_weather", null, "{\"type\":\"object\"}", null))), null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null);
            ChatCompletion t = p.chatModel("m", Map.of()).chatCompletionsCreate(req);
            assertThat(t.toolCalls().get(0).function().name()).isEqualTo("get_weather");
            assertThat(t.finishReason()).isEqualTo("tool_calls");
            assertThat(p.embeddingModel("e").embeddingsCreate(EmbeddingsRequest.of("e", List.of("a"))).data()).hasSize(1);
            assertThat(s.seen().get(2).path()).isEqualTo("/v2/embed");
            assertThat(body(s.seen().get(2)).path("input_type").asText()).isEqualTo("search_document");
            assertThat(p.liveModels()).extracting(ModelInfo::id).containsExactly("served-model", "served-embed");
            assertKind(() -> ask(p, "boom"), LlmException.Kind.FAILED);
            assertKind(() -> ask(p, "limit"), LlmException.Kind.RATE_LIMITED);
            assertKind(() -> ask(make("cohere", s.url(), merge(only(m("m")), Map.of("timeout", "300ms"))), "slow"), LlmException.Kind.TIMEOUT);
        }
    }

    // ---- Ollama

    @Test
    @SuppressWarnings("unchecked")
    void ollamaNeedsNoKeyAndSpeaksApiChat() throws Exception {
        try (var s = FakeLlmServer.start("ollama")) {
            ProviderBase p = make("ollama", s.url(), merge(only(Map.of("name", "m", "id", "llama3.2")), Map.of("num-ctx", 4096, "keep-alive", "5m", "options", Map.of("top_k", 20))));
            ChatCompletionRequest req = new ChatCompletionRequest("m", List.of(ChatMessage.user("ping")), null, null, null, null, 0.3, null, null, null, null, null, null, null,
                    null, null, null, null, null);
            assertThat(p.chatModel("m", Map.of()).chatCompletionsCreate(req).text()).isEqualTo("echo: ping");
            var seen = s.seen().get(0);
            JsonNode j = body(seen);
            assertThat(seen.path()).isEqualTo("/api/chat");
            assertThat(seen.header("Authorization")).isNull();
            assertThat(j.path("stream").asBoolean()).isFalse();
            assertThat(j.path("model").asText()).isEqualTo("llama3.2");
            assertThat(j.path("options").toString()).isEqualTo("{\"top_k\":20,\"num_predict\":1024,\"temperature\":0.3,\"num_ctx\":4096}");
            assertThat(j.path("keep_alive").asText()).isEqualTo("5m");
            ChatCompletion t = ask(p, "weather");
            assertThat(t.toolCalls().get(0).function().name()).isEqualTo("get_weather");
            assertThat(t.toolCalls().get(0).function().arguments()).isEqualTo("{\"city\":\"Oslo\"}");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void ollamaEmbeddingsThePulledModelsAndTheHealthProbe() throws Exception {
        try (var s = FakeLlmServer.start("ollama")) {
            ProviderBase p = make("ollama", s.url(), only(Map.of("name", "e", "id", "nomic-embed-text", "kind", "embedding"), Map.of("name", "m", "id", "llama3.2")));
            assertThat(p.embeddingModel("e").embeddingsCreate(EmbeddingsRequest.of("e", List.of("a", "b"))).data()).hasSize(2);
            assertThat(s.seen().get(0).path()).isEqualTo("/api/embed");
            assertThat(p.liveModels()).isEmpty();
            assertThat(make("ollama", s.url(), only(Map.of("name", "x", "id", "other"))).liveModels()).extracting(ModelInfo::id).containsExactly("llama3.2", "nomic-embed-text");
            assertThat(p.health().ok()).isTrue();
            assertThat(p.probe().detail()).contains("2 model(s) pulled");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void ollamaErrorsAndADownServerIsReportedNotThrown() throws Exception {
        String url;
        ProviderBase down;
        try (var s = FakeLlmServer.start("ollama")) {
            url = s.url();
            ProviderBase p = make("ollama", url, merge(only(m("m")), Map.of("timeout", "300ms")));
            assertKind(() -> ask(p, "boom"), LlmException.Kind.FAILED);
            assertKind(() -> ask(p, "limit"), LlmException.Kind.RATE_LIMITED);
            assertKind(() -> ask(p, "slow"), LlmException.Kind.TIMEOUT);
        }
        down = make("ollama", url, merge(only(m("m")), Map.of("timeout", "300ms")));
        assertThat(down.probe().ok()).isFalse();
        assertThat(down.probe().detail()).contains("not answering");
    }

    @Test
    void aBareOllamaHostGetsHttpAndThePort() {
        assertThat(com.ash.drishti.ai.OllamaProvider.normalize("gpu-box")).isEqualTo("http://gpu-box:11434");
        assertThat(com.ash.drishti.ai.OllamaProvider.normalize(":8080")).isEqualTo("http://localhost:8080");
        assertThat(com.ash.drishti.ai.OllamaProvider.normalize("http://h:1/")).isEqualTo("http://h:1");
    }

    // ---- Bedrock (AWS SDK v2; SigV4)

    private static Map<String, Object> awsKeys() {
        return Map.of("aws-access-key-id", "${AKID}", "aws-secret-access-key", "${ASECRET}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void bedrockConverseIsSignedWithSigV4AndCarriesTheSessionToken() throws Exception {
        try (var s = FakeLlmServer.start("bedrock")) {
            Map<String, Object> keys = new LinkedHashMap<>(awsKeys());
            keys.put("aws-session-token", "${AWS_TOKEN}");
            ProviderBase p = make("bedrock", s.url(), merge(merge(only(Map.of("name", "n", "id", "us.amazon.nova-pro-v1:0", "cost-per-1k-input", 1.0)), keys),
                    Map.of("region", "eu-west-1")));
            ChatCompletionRequest req = new ChatCompletionRequest("n", List.of(ChatMessage.system("be brief"), ChatMessage.user("ping")), null, null, null, null, null, null, null,
                    77, null, null, null, null, null, null, null, null, null);
            ChatCompletion c = p.chatModel("n", Map.of()).chatCompletionsCreate(req);
            var seen = s.seen().get(0);
            JsonNode j = body(seen);
            assertThat(seen.path()).isEqualTo("/model/us.amazon.nova-pro-v1%3A0/converse");
            assertThat(seen.header("Authorization")).startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/").contains("/eu-west-1/bedrock/aws4_request").contains("SignedHeaders=")
                    .contains("Signature=");
            assertThat(seen.header("X-Amz-Date")).isNotNull();
            assertThat(seen.header("X-Amz-Security-Token")).isEqualTo("tok-session");
            assertThat(j.path("system").get(0).path("text").asText()).isEqualTo("be brief");
            assertThat(j.path("messages").get(0).path("content").get(0).path("text").asText()).isEqualTo("ping");
            assertThat(j.path("inferenceConfig").path("maxTokens").asInt()).isEqualTo(77);
            assertThat(c.text()).isEqualTo("echo: ping");
            assertThat(c.usage().totalTokens()).isEqualTo(8);
            assertThat(c.drishti().costUsd()).isEqualTo(0.005, org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void bedrockToolsEmbeddingsErrorsAndTheModelList() throws Exception {
        try (var s = FakeLlmServer.start("bedrock")) {
            ProviderBase p = make("bedrock", s.url(), merge(merge(only(Map.of("name", "n", "id", "anthropic.claude-sonnet-5-5"),
                    Map.of("name", "t", "id", "amazon.titan-embed-text-v2:0", "kind", "embedding"), Map.of("name", "c", "id", "cohere.embed-english-v3", "kind", "embedding")),
                    awsKeys()), Map.of("region", "eu-west-1", "control-url", s.url())));
            ChatCompletionRequest req = new ChatCompletionRequest("n", List.of(ChatMessage.user("weather")),
                    List.of(new ToolDefinition("function", new FunctionDefinition("get_weather", null, "{\"type\":\"object\"}", null))), null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null);
            ChatCompletion t = p.chatModel("n", Map.of()).chatCompletionsCreate(req);
            assertThat(t.toolCalls().get(0).function().name()).isEqualTo("get_weather");
            assertThat(t.toolCalls().get(0).function().arguments()).isEqualTo("{\"city\":\"Oslo\"}");
            assertThat(body(s.seen().get(0)).path("toolConfig").path("tools").get(0).path("toolSpec").path("inputSchema").path("json").toString()).isEqualTo("{\"type\":\"object\"}");
            assertThat(p.embeddingModel("t").embeddingsCreate(EmbeddingsRequest.of("t", List.of("a", "b"))).data()).hasSize(2);
            assertThat(s.seen().get(1).path()).isEqualTo("/model/amazon.titan-embed-text-v2%3A0/invoke");
            assertThat(body(s.seen().get(1)).toString()).isEqualTo("{\"inputText\":\"a\"}");
            assertThat(p.embeddingModel("c").embeddingsCreate(EmbeddingsRequest.of("c", List.of("a", "b"))).data()).hasSize(2);
            assertKind(() -> ask(p, "boom"), LlmException.Kind.FAILED);
            assertKind(() -> ask(p, "limit"), LlmException.Kind.RATE_LIMITED);
            assertThat(p.liveModels()).extracting(ModelInfo::id).isEmpty();                    // both listed models are in the file's catalogue... (titan) or not:
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void bedrockListsFoundationModelsAndTimesOut() throws Exception {
        try (var s = FakeLlmServer.start("bedrock")) {
            ProviderBase p = make("bedrock", s.url(), merge(merge(only(m("n")), awsKeys()), Map.of("control-url", s.url(), "timeout", "300ms", "region", "us-west-2")));
            assertThat(p.liveModels()).extracting(ModelInfo::id).containsExactly("anthropic.claude-sonnet-5-5", "amazon.titan-embed-text-v2:0");
            assertThat(s.seen().get(0).path()).isEqualTo("/foundation-models");
            assertThat(p.probe().ok()).isTrue();
            assertKind(() -> ask(p, "slow"), LlmException.Kind.TIMEOUT);
            assertThat(p.describeConfig().toString()).doesNotContain("wJalr").contains("us-west-2");
            assertThatThrownBy(() -> make("bedrock", s.url(), Map.of("aws-access-key-id", "${AKID}"))).hasMessageContaining("go together");
        }
    }

    @Test
    void bedrockWithoutCredentialsIsDownAndNamesWhatToSet() {
        ProviderBase p = build("bedrock", Map.of("type", "bedrock", "profile", "no-such-profile-xyz"), Map.of());
        assertThat(p.health().ok()).isFalse();
        assertThat(p.health().detail()).contains("no AWS credentials");
    }
}
