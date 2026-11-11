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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.http.urlconnection.ProxyConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;

/**
 * {@code type: bedrock}: Amazon Bedrock through the AWS SDK v2 ({@code BedrockProvider} of sajhamcpserver): the Converse API for chat, InvokeModel for embeddings,
 * {@code ListFoundationModels} for the live model list. The SDK signs with SigV4 and walks the AWS credential chain: environment keys, a shared-credentials profile, SSO,
 * container and instance roles, web identity.
 *
 * <p>Settings: {@code region} ({@code AWS_REGION} / {@code AWS_DEFAULT_REGION} are read, then us-east-1), {@code profile}, {@code aws-access-key-id} /
 * {@code aws-secret-access-key} / {@code aws-session-token} as {@code ${ENV}} placeholders (never literals; without them the chain decides), {@code base-url} (an endpoint
 * override: a VPC endpoint, or a local fake in a test), {@code control-url} (the same for the control plane that lists models), {@code embedding-input-type}
 * (search_document, Cohere models), {@code proxy.*}, the shared {@code tls.*} (a CA or truststore, a client identity; {@code verify-hostname: false} is refused: the SDK
 * always checks the name), {@code timeout} and {@code retries}. Guardrails and {@code additional-model-request-fields} are not built.
 */
public final class BedrockProvider extends BaseProvider {

    private final String region;
    private final BedrockRuntimeClient runtime;
    private final java.util.function.Supplier<BedrockClient> control;
    private volatile BedrockClient controlClient;
    private final AwsCredentialsProvider credentials;
    private final TlsMaterial tls;

    BedrockProvider(ProviderConfig c) {
        super(c, Set.of(Capability.TOOL_CALLS, Capability.JSON_MODE));
        this.region = c.setting("region", "us-east-1");
        String target = c.baseUrl().isEmpty() ? "https://bedrock-runtime." + region + ".amazonaws.com" : c.baseUrl();
        this.tls = TlsHttp.material(c.settings(), target, "base-url");
        if (tls != null && !tls.settings().verifyHostname()) {
            throw new LlmException(LlmException.Kind.CONFIG,
                    "bedrock: tls.verify-hostname: false is not supported by the AWS client (it always checks the host name); give the endpoint a name the certificate carries");
        }
        this.credentials = credentials(c);
        ProxySettings proxy = ProxySettings.resolve(c.settings(), target);
        String host = URI.create(target).getHost();
        try {
            this.runtime = configure(BedrockRuntimeClient.builder(), c, proxy, host, c.baseUrl()).build();
        } catch (RuntimeException e) {
            throw new LlmException(LlmException.Kind.CONFIG, "the AWS client could not be set up (" + e.getClass().getSimpleName() + ")");
        }
        String controlUrl = c.setting("control-url", "");
        this.control = () -> configure(BedrockClient.builder(), c, proxy, controlUrl.isEmpty() ? "bedrock." + region + ".amazonaws.com" : URI.create(controlUrl).getHost(),
                controlUrl).build();
    }

    private static AwsCredentialsProvider credentials(ProviderConfig c) {
        String key = c.setting("aws-access-key-id", "");
        String secret = c.setting("aws-secret-access-key", "");
        if (!key.isEmpty() && !secret.isEmpty()) {
            String token = c.setting("aws-session-token", "");
            return StaticCredentialsProvider.create(token.isEmpty() ? AwsBasicCredentials.create(key, secret) : AwsSessionCredentials.create(key, secret, token));
        }
        if (key.isEmpty() != secret.isEmpty()) {
            throw new LlmException(LlmException.Kind.CONFIG, "aws-access-key-id and aws-secret-access-key go together (as ${ENV} placeholders)");
        }
        String profile = c.setting("profile", "");
        return profile.isEmpty() ? DefaultCredentialsProvider.builder().build() : ProfileCredentialsProvider.create(profile);
    }

    /** The SDK builder with region, credentials, HTTP client (proxy and TLS as the S3 connector does), timeouts and retries. */
    private <B extends software.amazon.awssdk.awscore.client.builder.AwsClientBuilder<B, ?> & software.amazon.awssdk.core.client.builder.SdkSyncClientBuilder<B, ?>> B configure(B b, ProviderConfig c, ProxySettings proxy, String host, String endpoint) {
        SdkHttpClient.Builder<?> http;
        Duration timeout = c.timeout();
        if (proxy.appliesTo(host)) {                                    // the JDK URL-connection client cannot answer a proxy's login when it tunnels https
            var ap = Apache5HttpClient.builder().connectionTimeout(Duration.ofSeconds(5)).socketTimeout(timeout);
            if (tls != null) {
                ap.tlsTrustManagersProvider(tls::trustManagers).tlsKeyManagersProvider(tls::keyManagers);
            }
            var pc = software.amazon.awssdk.http.apache5.ProxyConfiguration.builder().endpoint(proxy.uri()).useSystemPropertyValues(false)
                    .useEnvironmentVariableValues(false);
            if (proxy.hasCredentials()) {
                pc.username(proxy.username()).password(proxy.password()).preemptiveBasicAuthenticationEnabled(true);
            }
            http = ap.proxyConfiguration(pc.build());
        } else {
            UrlConnectionHttpClient.Builder uc = UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5)).socketTimeout(timeout);
            if (tls != null) {
                uc.tlsTrustManagersProvider(tls::trustManagers).tlsKeyManagersProvider(tls::keyManagers);
            }
            if (proxy.decided()) {
                uc.proxyConfiguration(ProxyConfiguration.builder().useSystemPropertyValues(false).useEnvironmentVariablesValues(false).build());
            }
            http = uc;
        }
        b.region(Region.of(region)).credentialsProvider(credentials).httpClientBuilder(http)
                .overrideConfiguration(o -> o.apiCallTimeout(timeout.multipliedBy(c.retries() + 1L)).apiCallAttemptTimeout(timeout)
                        .retryStrategy(AwsRetryStrategy.standardRetryStrategy().toBuilder().maxAttempts(c.retries() + 1).build()));
        if (!endpoint.isEmpty()) {
            b.endpointOverride(URI.create(endpoint));
        }
        return b;
    }

    private BedrockClient controlClient() {
        BedrockClient c = controlClient;
        if (c == null) {
            synchronized (this) {
                c = controlClient;
                if (c == null) {
                    c = controlClient = control.get();
                }
            }
        }
        return c;
    }

    // ---- errors

    static LlmException map(RuntimeException e) {
        if (e instanceof LlmException l) {
            return l;
        }
        if (e instanceof ApiCallTimeoutException || e instanceof ApiCallAttemptTimeoutException || hasCause(e, java.net.SocketTimeoutException.class)
                || hasCause(e, java.net.http.HttpTimeoutException.class)) {
            return new LlmException(LlmException.Kind.TIMEOUT, "the model endpoint did not answer in time", e);
        }
        if (e instanceof AwsServiceException a) {
            int status = a.statusCode();
            String code = a.awsErrorDetails() == null ? "" : String.valueOf(a.awsErrorDetails().errorCode());
            if (status == 429 || code.contains("Throttling") || code.contains("TooManyRequests") || code.contains("ServiceQuotaExceeded")) {
                return new LlmException(LlmException.Kind.RATE_LIMITED, "the model endpoint answered HTTP " + (status == 0 ? 429 : status) + " " + code
                        + " (rate limited: slow down, raise the Bedrock quota or add retries)", e);
            }
            if (status == 401 || status == 403) {
                return new LlmException(LlmException.Kind.FAILED, "the model endpoint answered HTTP " + status + " " + code
                        + " (authentication failed or access denied: check the credentials, the region and the model access)", e);
            }
            if (status == 404) {
                return new LlmException(LlmException.Kind.FAILED, "the model endpoint answered HTTP 404 " + code + " (not found: check the model id and the region)", e);
            }
            return new LlmException(LlmException.Kind.FAILED, "the model endpoint answered HTTP " + status + " " + code, e);
        }
        if (e instanceof SdkClientException && String.valueOf(e.getMessage()).contains("Unable to load credentials")) {
            return new LlmException(LlmException.Kind.CONFIG, "no AWS credentials: set AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY, a profile, or run with a role", e);
        }
        return new LlmException(LlmException.Kind.FAILED, "the model endpoint could not be reached or answered something unreadable", e);
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (type.isInstance(x)) {
                return true;
            }
        }
        return false;
    }

    // ---- readiness and listing

    @Override
    public Readiness check() {
        try {
            if (credentials.resolveCredentials() == null) {
                throw new IllegalStateException();
            }
        } catch (RuntimeException e) {
            return new Readiness(false, "no AWS credentials: set AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY (or aws-access-key-id / aws-secret-access-key as ${ENV}), a profile, or run with a role");
        }
        return new Readiness(true, "configured for " + region + "; no call is made to find out");
    }

    @Override
    public List<LiveModel> liveModels() {
        try {
            List<LiveModel> out = new ArrayList<>();
            controlClient().listFoundationModels(software.amazon.awssdk.services.bedrock.model.ListFoundationModelsRequest.builder().build()).modelSummaries().forEach(m -> out.add(new LiveModel(m.modelId(),
                    m.outputModalities().stream().anyMatch(x -> "EMBEDDING".equals(x.toString())) ? "embedding" : "chat")));
            return out;
        } catch (RuntimeException e) {
            throw map(e);
        }
    }

    @Override
    public Readiness probe() {
        Readiness r = check();
        if (!r.ok()) {
            return r;
        }
        try {
            return new Readiness(true, region + ": " + liveModels().size() + " foundation models listed");
        } catch (LlmException e) {
            return new Readiness(false, e.getMessage());
        }
    }

    @Override
    public String tlsNote() {
        return tls == null ? "" : tls.expiryNote(Instant.now());
    }

    @Override
    public List<Map<String, String>> tlsCertificates() {
        List<Map<String, String>> out = new ArrayList<>();
        if (tls != null) {
            tls.certificates().forEach(c -> out.add(Map.of("subject", String.valueOf(c.subject()), "source", String.valueOf(c.source()), "notAfter",
                    c.notAfter().toString().substring(0, 10))));
        }
        return out;
    }

    // ---- calls

    @Override
    public Optional<LlmModel> chatModel(String logicalName) {
        return find(logicalName, false).map(info -> new LlmModel() {
            @Override
            public ModelInfo info() {
                return info;
            }

            @Override
            public ChatResponse chat(ChatRequest r) {
                try {
                    return converse(info, r);
                } catch (RuntimeException e) {
                    throw map(e);
                }
            }
        });
    }

    private ChatResponse converse(ModelInfo info, ChatRequest r) {
        List<Message> msgs = new ArrayList<>();
        for (ChatMessage m : r.messages()) {
            if (m.role() != ChatMessage.Role.SYSTEM) {
                msgs.add(Message.builder().role(m.role() == ChatMessage.Role.ASSISTANT ? ConversationRole.ASSISTANT : ConversationRole.USER)
                        .content(ContentBlock.fromText(m.content())).build());
            }
        }
        InferenceConfiguration.Builder inf = InferenceConfiguration.builder().maxTokens(maxTokens(r, info, 1024));
        Double t = temperature(r, info);
        if (t != null) {
            inf.temperature(t.floatValue());
        }
        ConverseRequest.Builder b = ConverseRequest.builder().modelId(info.id()).messages(msgs).inferenceConfig(inf.build());
        String sys = systemText(r);
        if (!sys.isEmpty()) {
            b.system(SystemContentBlock.fromText(sys));
        }
        if (!r.tools().isEmpty()) {
            List<Tool> tools = new ArrayList<>();
            for (ToolSpec s : r.tools()) {
                ToolSpecification.Builder ts = ToolSpecification.builder().name(s.name()).inputSchema(ToolInputSchema.fromJson(Docs.of(HttpProvider.schema(s))));
                if (s.description() != null && !s.description().isEmpty()) {
                    ts.description(s.description());
                }
                tools.add(Tool.fromToolSpec(ts.build()));
            }
            b.toolConfig(ToolConfiguration.builder().tools(tools).build());
        }
        ConverseResponse resp = runtime.converse(b.build());
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (ContentBlock cb : resp.output().message().content()) {
            if (cb.text() != null) {
                text.append(cb.text());
            }
            if (cb.toolUse() != null) {
                calls.add(new ToolCall(cb.toolUse().toolUseId(), cb.toolUse().name(), Docs.json(cb.toolUse().input()).toString()));
            }
        }
        if (text.isEmpty() && calls.isEmpty()) {
            throw new LlmException(LlmException.Kind.FAILED, "the model endpoint's answer has no text content");
        }
        int in = resp.usage() == null || resp.usage().inputTokens() == null ? 0 : resp.usage().inputTokens();
        int out = resp.usage() == null || resp.usage().outputTokens() == null ? 0 : resp.usage().outputTokens();
        String stop = resp.stopReasonAsString() == null ? "end_turn" : resp.stopReasonAsString();
        String fin = switch (stop) {
            case "max_tokens" -> "length";
            case "content_filtered", "guardrail_intervened" -> "content_filter";
            default -> "stop";
        };
        return new ChatResponse(text.toString(), calls, info.id(), calls.isEmpty() ? fin : "tool_calls", new Usage(in, out, info.cost(in, out)));
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
                    return embedAll(info, texts);
                } catch (RuntimeException e) {
                    throw map(e);
                }
            }
        });
    }

    private JsonNode invoke(String modelId, ObjectNode body) {
        try {
            var resp = runtime.invokeModel(InvokeModelRequest.builder().modelId(modelId).contentType("application/json").accept("application/json")
                    .body(SdkBytes.fromUtf8String(JsonHttp.JSON.writeValueAsString(body))).build());
            return JsonHttp.JSON.readTree(resp.body().asByteArray());
        } catch (IOException e) {
            throw new LlmException(LlmException.Kind.FAILED, "the model endpoint answered something unreadable", e);
        }
    }

    private List<float[]> embedAll(ModelInfo info, List<String> texts) {
        ArrayNode arrays = JsonHttp.JSON.createArrayNode();
        if (info.id().contains("cohere")) {
            ObjectNode body = JsonHttp.JSON.createObjectNode();
            ArrayNode in = body.putArray("texts");
            texts.forEach(in::add);
            body.put("input_type", config.setting("embedding-input-type", "search_document"));
            JsonNode e = invoke(info.id(), body).path("embeddings");
            (e.isObject() ? e.path("float") : e).forEach(arrays::add);
        } else {
            for (String t : texts) {                                      // Titan: one text per call
                arrays.add(invoke(info.id(), JsonHttp.JSON.createObjectNode().put("inputText", t)).path("embedding"));
            }
        }
        return HttpProvider.vectors(arrays, texts.size());
    }

    @Override
    public void close() {
        runtime.close();
        BedrockClient c = controlClient;
        if (c != null) {
            c.close();
        }
    }

    /** Jackson to the SDK's {@link Document} and back (tool schemas out, tool inputs in). */
    static final class Docs {
        private Docs() {}

        static Document of(JsonNode n) {
            if (n == null || n.isNull()) {
                return Document.fromNull();
            }
            if (n.isObject()) {
                Map<String, Document> m = new java.util.LinkedHashMap<>();
                n.fields().forEachRemaining(e -> m.put(e.getKey(), of(e.getValue())));
                return Document.fromMap(m);
            }
            if (n.isArray()) {
                List<Document> l = new ArrayList<>();
                n.forEach(x -> l.add(of(x)));
                return Document.fromList(l);
            }
            if (n.isBoolean()) {
                return Document.fromBoolean(n.asBoolean());
            }
            if (n.isNumber()) {
                return n.isIntegralNumber() ? Document.fromNumber(n.asLong()) : Document.fromNumber(n.asDouble());
            }
            return Document.fromString(n.asText());
        }

        static JsonNode json(Document d) {
            if (d == null || d.isNull()) {
                return JsonHttp.JSON.nullNode();
            }
            if (d.isMap()) {
                ObjectNode o = JsonHttp.JSON.createObjectNode();
                d.asMap().forEach((k, v) -> o.set(k, json(v)));
                return o;
            }
            if (d.isList()) {
                ArrayNode a = JsonHttp.JSON.createArrayNode();
                d.asList().forEach(v -> a.add(json(v)));
                return a;
            }
            if (d.isBoolean()) {
                return JsonHttp.JSON.getNodeFactory().booleanNode(d.asBoolean());
            }
            if (d.isNumber()) {
                java.math.BigDecimal bd = d.asNumber().bigDecimalValue();
                return bd.scale() <= 0 ? JsonHttp.JSON.getNodeFactory().numberNode(bd.toBigInteger().longValueExact())
                        : JsonHttp.JSON.getNodeFactory().numberNode(bd.doubleValue());
            }
            return JsonHttp.JSON.getNodeFactory().textNode(d.asString());
        }
    }

    /** {@code type: bedrock}. */
    public static final class Factory implements ProviderFactory {
        @Override
        public String type() {
            return "bedrock";
        }

        @Override
        public LlmProvider create(ProviderConfig config) {
            return new BedrockProvider(config);
        }
    }
}
