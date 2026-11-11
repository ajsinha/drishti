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

import com.ash.drishti.api.proxy.ProxySettings;
import com.ash.drishti.api.tls.TlsHttp;
import com.ash.drishti.api.tls.TlsMaterial;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.model.NoopApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * {@code type: openai}: any OpenAI-compatible endpoint (OpenAI, Azure OpenAI, vLLM, Ollama's /v1, LiteLLM, a company gateway)
 * through Spring AI's OpenAI client. Spring AI stays an implementation detail: the interfaces above are Drishti's own.
 * Settings: {@code base-url} (default https://api.openai.com), {@code completions-path} (/v1/chat/completions),
 * {@code embeddings-path} (/v1/embeddings), {@code header.<Name>} extra headers (for example Azure's {@code api-key}), and the
 * shared {@code tls.*} keys. {@code retries} is the number of extra attempts after a failure (default 0).
 */
public final class SpringAiOpenAiProvider extends BaseProvider {

    private final OpenAiApi api;
    private final RetryTemplate retry;

    SpringAiOpenAiProvider(ProviderConfig c) {
        super(c, Set.of(Capability.TOOL_CALLS, Capability.JSON_MODE));
        String base = c.baseUrl().isEmpty() ? "https://api.openai.com" : c.baseUrl().replaceAll("/+$", "");
        ProxySettings proxy = ProxySettings.resolve(c.settings(), base);               // proxy.* or the site default; https is tunnelled with CONNECT
        HttpClient.Builder hb = proxy.apply(HttpClient.newBuilder().connectTimeout(c.timeout()).version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER));
        TlsMaterial tls = TlsHttp.material(c.settings(), base, "base-url");
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory((tls == null ? hb : TlsHttp.apply(hb, tls)).build());
        rf.setReadTimeout(c.timeout());
        var headers = new LinkedMultiValueMap<String, String>();
        c.settings().forEach((k, v) -> {
            if (k.startsWith("header.")) {
                headers.add(k.substring(7), v);
            }
        });
        try {
            this.api = OpenAiApi.builder().baseUrl(base).apiKey(c.apiKey().isEmpty() ? new NoopApiKey() : new org.springframework.ai.model.SimpleApiKey(c.apiKey()))
                    .completionsPath(c.setting("completions-path", "/v1/chat/completions"))
                    .embeddingsPath(c.setting("embeddings-path", "/v1/embeddings")).headers(headers)
                    .restClientBuilder(RestClient.builder().requestFactory(rf)).build();
        } catch (RuntimeException e) {
            throw new LlmException(LlmException.Kind.CONFIG, "the OpenAI-compatible client could not be set up (" + e.getClass().getSimpleName() + ")");
        }
        this.retry = RetryTemplate.builder().maxAttempts(c.retries() + 1).fixedBackoff(300).retryOn(org.springframework.ai.retry.TransientAiException.class).build();
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

    @Override
    public Optional<EmbeddingModel> embeddingModel(String logicalName) {
        return find(logicalName, true).map(info -> new EmbeddingModel() {
            @Override
            public ModelInfo info() {
                return info;
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                try {
                    var model = new OpenAiEmbeddingModel(api, org.springframework.ai.document.MetadataMode.EMBED,
                            OpenAiEmbeddingOptions.builder().model(info.id()).build(), retry);
                    return model.call(new EmbeddingRequest(texts, null)).getResults().stream().map(e -> e.getOutput()).toList();
                } catch (RuntimeException e) {
                    throw translate(e);
                }
            }
        });
    }

    private ChatResponse call(ModelInfo info, ChatRequest r) {
        List<Message> messages = new ArrayList<>();
        String sys = systemText(r);
        if (!sys.isEmpty()) {
            messages.add(new SystemMessage(sys));
        }
        for (ChatMessage m : r.messages()) {
            switch (m.role()) {
                case USER, TOOL -> messages.add(new UserMessage(m.content()));
                case ASSISTANT -> messages.add(new AssistantMessage(m.content()));
                default -> { }
            }
        }
        OpenAiChatOptions.Builder o = OpenAiChatOptions.builder().model(info.id()).maxTokens(maxTokens(r, info, 1024))
                .internalToolExecutionEnabled(false);
        Double t = temperature(r, info);
        if (t != null) {
            o.temperature(t);
        }
        if (r.jsonMode()) {
            o.responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
        }
        if (!r.tools().isEmpty()) {
            o.tools(r.tools().stream().map(s -> new OpenAiApi.FunctionTool(new OpenAiApi.FunctionTool.Function(s.description(), s.name(),
                    s.parametersJsonSchema() == null ? "{\"type\":\"object\"}" : s.parametersJsonSchema()))).toList());
        }
        try {
            OpenAiChatModel model = OpenAiChatModel.builder().openAiApi(api).defaultOptions(o.build()).retryTemplate(retry).build();
            var resp = model.call(new Prompt(messages));
            Generation g = resp.getResult();
            if (g == null || g.getOutput() == null) {
                throw new LlmException(LlmException.Kind.FAILED, "the model endpoint's answer has no choices");
            }
            String text = g.getOutput().getText();
            List<ToolCall> calls = g.getOutput().getToolCalls().stream().map(c -> new ToolCall(c.id(), c.name(), c.arguments())).toList();
            if ((text == null || text.isEmpty()) && calls.isEmpty()) {
                throw new LlmException(LlmException.Kind.FAILED, "the model endpoint's answer has no message content");
            }
            var u = resp.getMetadata().getUsage();
            int in = u == null || u.getPromptTokens() == null ? 0 : u.getPromptTokens();
            int out = u == null || u.getCompletionTokens() == null ? 0 : u.getCompletionTokens();
            return new ChatResponse(text, calls, resp.getMetadata().getModel(), g.getMetadata().getFinishReason(), new Usage(in, out, info.cost(in, out)));
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    /** A failure as the interface reports it: no prompt, no key, no response body in the message. */
    static LlmException translate(RuntimeException e) {
        for (Throwable x = e; x != null; x = x.getCause()) {
            if (x instanceof java.net.http.HttpTimeoutException || x instanceof java.net.SocketTimeoutException) {
                return new LlmException(LlmException.Kind.TIMEOUT, "the model endpoint did not answer in time", e);
            }
        }
        String m = e.getMessage() == null ? "" : e.getMessage();
        java.util.regex.Matcher st = java.util.regex.Pattern.compile("\\b([45]\\d\\d)\\b").matcher(m);
        return new LlmException(LlmException.Kind.FAILED, st.find() ? "the model endpoint answered HTTP " + st.group(1)
                : "the model endpoint could not be reached or answered something unreadable", e);
    }

    /** {@code type: openai}. */
    public static final class Factory implements ProviderFactory {
        @Override
        public String type() {
            return "openai";
        }

        @Override
        public LlmProvider create(ProviderConfig config) {
            return new SpringAiOpenAiProvider(config);
        }
    }
}
