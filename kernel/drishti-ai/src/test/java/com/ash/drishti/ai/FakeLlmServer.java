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

import com.ash.drishti.testkit.TestPki;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Fake HTTP(S) servers on loopback (an ephemeral port) that speak each vendor API well enough for the provider tests: no real network, ever. The rules are the console's
 * ({@code tests/llm_fakes.py}): the last user text is echoed ({@code echo: <text>}), {@code weather} asks for the tool {@code get_weather} with {@code {"city":"Oslo"}},
 * {@code boom} is HTTP 500, {@code limit} is HTTP 429, {@code slow} sleeps two seconds (a timeout test), and a request without the right credential is HTTP 401.
 */
public final class FakeLlmServer implements AutoCloseable {

    static final String KEY = "k-123";
    static final ObjectMapper JSON = new ObjectMapper();

    public record Seen(String method, String path, String body, com.sun.net.httpserver.Headers headers) {
        public String header(String name) {
            return headers.getFirst(name);
        }
    }

    private final List<Seen> seen = new CopyOnWriteArrayList<>();

    public List<Seen> seen() {
        return seen;
    }

    public static FakeLlmServer start(String api) throws IOException {
        return new FakeLlmServer(api);
    }
    final String api;
    private final HttpServer server;
    private final boolean tls;

    /** @param tlsContext null for plain HTTP; with it, {@code needClientAuth} asks for a client certificate. */
    public FakeLlmServer(String api, SSLContext tlsContext, boolean needClientAuth) throws IOException {
        this.api = api;
        this.tls = tlsContext != null;
        InetSocketAddress addr = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        if (tls) {
            HttpsServer s = HttpsServer.create(addr, 0);
            s.setHttpsConfigurator(new HttpsConfigurator(tlsContext) {
                @Override
                public void configure(HttpsParameters params) {
                    var p = tlsContext.getDefaultSSLParameters();
                    p.setNeedClientAuth(needClientAuth);
                    params.setSSLParameters(p);
                }
            });
            server = s;
        } else {
            server = HttpServer.create(addr, 0);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    public FakeLlmServer(String api) throws IOException {
        this(api, null, false);
    }

    public String url() {
        return (tls ? "https://127.0.0.1:" : "http://127.0.0.1:") + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
    }

    /** A server SSL context for {@code identity}; with {@code clientCa} non-null it also trusts client certificates from that CA. */
    public static SSLContext sslContext(TestPki.Identity identity, java.nio.file.Path dir, TestPki.Identity clientCa) throws Exception {
        java.nio.file.Path ks = dir.resolve("srv-" + System.nanoTime() + ".p12");
        identity.writeKeystore(ks, "PKCS12", "pw", "srv", "pw");
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var in = java.nio.file.Files.newInputStream(ks)) {
            keys.load(in, "pw".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, "pw".toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        if (clientCa != null) {
            trust.setCertificateEntry("ca", clientCa.cert());
        }
        tmf.init(trust);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    // ---- the rules

    private void handle(HttpExchange x) throws IOException {
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = x.getRequestURI().getRawPath();
        seen.add(new Seen(x.getRequestMethod(), x.getRequestURI().getRawPath() + (x.getRequestURI().getRawQuery() == null ? "" : "?" + x.getRequestURI().getRawQuery()),
                body, x.getRequestHeaders()));
        int status;
        JsonNode out;
        try {
            Object[] r = answer(x.getRequestMethod(), path, body, x.getRequestHeaders());
            status = (Integer) r[0];
            out = (JsonNode) r[1];
        } catch (RuntimeException | InterruptedException e) {
            status = 400;
            out = JSON.createObjectNode().put("error", "bad request");
        }
        byte[] bytes = JSON.writeValueAsBytes(out);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
        x.close();
    }

    private boolean authorised(com.sun.net.httpserver.Headers h) {
        return switch (api) {
            case "openai", "cohere" -> ("Bearer " + KEY).equals(h.getFirst("Authorization"));
            case "azure" -> KEY.equals(h.getFirst("api-key"));
            case "gemini" -> KEY.equals(h.getFirst("x-goog-api-key"));
            case "azure-entra" -> "Bearer entra-token-1".equals(h.getFirst("Authorization"));
            case "bedrock" -> h.getFirst("Authorization") != null && h.getFirst("Authorization").startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/");
            default -> true;
        };
    }

    private Object[] answer(String method, String p, String body, com.sun.net.httpserver.Headers h) throws IOException, InterruptedException {
        if (p.endsWith("/oauth2/v2.0/token")) {
            return ok(JSON.createObjectNode().put("access_token", "entra-token-1").put("expires_in", 3600).put("token_type", "Bearer"));
        }
        if (!authorised(h)) {
            return new Object[] {401, JSON.createObjectNode().set("error", JSON.createObjectNode().put("message", "bad credential"))};
        }
        JsonNode j = body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
        return switch (api) {
            case "openai", "azure", "azure-entra" -> openai(p, j);
            case "gemini" -> gemini(p, j);
            case "cohere" -> cohere(p, j);
            case "ollama" -> ollama(p, j);
            default -> bedrock(p, j);
        };
    }

    private static Object[] ok(JsonNode n) {
        return new Object[] {200, n};
    }

    private static ArrayNode vec() {
        ArrayNode a = JSON.createArrayNode();
        a.add(0.1).add(0.2).add(0.3).add(0.4);
        return a;
    }

    /** boom, limit, slow; null when the text is plain. */
    private static Object[] gate(String text) throws InterruptedException {
        if (text.contains("boom")) {
            return new Object[] {500, JSON.createObjectNode().set("error", JSON.createObjectNode().put("message", "secret prompt echo"))};
        }
        if (text.contains("limit")) {
            return new Object[] {429, JSON.createObjectNode().set("error", JSON.createObjectNode().put("message", "slow down"))};
        }
        if (text.contains("slow")) {
            Thread.sleep(2000);
        }
        return null;
    }

    private static String lastUser(JsonNode messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode m = messages.get(i);
            if ("user".equals(m.path("role").asText("user"))) {
                JsonNode c = m.path("content");
                if (c.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    c.forEach(x -> sb.append(x.path("text").asText("")));
                    return sb.toString();
                }
                return c.asText("");
            }
        }
        return "";
    }

    private static ObjectNode toolCallArgs() {
        return JSON.createObjectNode().put("city", "Oslo");
    }

    private Object[] openai(String p, JsonNode j) throws InterruptedException {
        if (p.endsWith("/models")) {
            ArrayNode d = JSON.createArrayNode();
            d.addObject().put("id", "served-model");
            d.addObject().put("id", "served-embed");
            return ok(JSON.createObjectNode().set("data", d));
        }
        if (p.endsWith("/embeddings")) {
            ArrayNode d = JSON.createArrayNode();
            for (int i = 0; i < j.path("input").size(); i++) {
                d.addObject().put("index", i).set("embedding", vec());
            }
            return ok(JSON.createObjectNode().set("data", d));
        }
        String text = lastUser(j.path("messages"));
        Object[] g = gate(text);
        if (g != null) {
            return g;
        }
        ObjectNode msg = JSON.createObjectNode().put("role", "assistant");
        String fin = "stop";
        if (text.contains("weather")) {
            msg.putNull("content");
            msg.putArray("tool_calls").addObject().put("id", "c1").put("type", "function").set("function",
                    JSON.createObjectNode().put("name", "get_weather").put("arguments", toolCallArgs().toString()));
            fin = "tool_calls";
        } else {
            msg.put("content", "echo: " + text);
        }
        ObjectNode r = JSON.createObjectNode().put("id", "x").put("model", j.path("model").asText());
        r.putArray("choices").addObject().put("index", 0).put("finish_reason", fin).set("message", msg);
        r.putObject("usage").put("prompt_tokens", 5).put("completion_tokens", 3);
        return ok(r);
    }

    private Object[] gemini(String p, JsonNode j) throws InterruptedException {
        if (p.endsWith("/models")) {
            ArrayNode m = JSON.createArrayNode();
            ObjectNode a = m.addObject().put("name", "models/served-model");
            a.putArray("supportedGenerationMethods").add("generateContent");
            ObjectNode b = m.addObject().put("name", "models/served-embed");
            b.putArray("supportedGenerationMethods").add("embedContent");
            return ok(JSON.createObjectNode().set("models", m));
        }
        if (p.endsWith(":batchEmbedContents")) {
            ArrayNode e = JSON.createArrayNode();
            j.path("requests").forEach(x -> e.addObject().set("values", vec()));
            return ok(JSON.createObjectNode().set("embeddings", e));
        }
        JsonNode contents = j.path("contents");
        StringBuilder sb = new StringBuilder();
        contents.get(contents.size() - 1).path("parts").forEach(x -> sb.append(x.path("text").asText("")));
        String text = sb.toString();
        Object[] g = gate(text);
        if (g != null) {
            return g;
        }
        ArrayNode parts = JSON.createArrayNode();
        if (text.contains("weather")) {
            parts.addObject().set("functionCall", JSON.createObjectNode().put("name", "get_weather").set("args", toolCallArgs()));
        } else {
            parts.addObject().put("text", "echo: " + text);
        }
        ObjectNode r = JSON.createObjectNode();
        ObjectNode cand = r.putArray("candidates").addObject().put("finishReason", "STOP");
        cand.putObject("content").put("role", "model").set("parts", parts);
        r.putObject("usageMetadata").put("promptTokenCount", 5).put("candidatesTokenCount", 3);
        return ok(r);
    }

    private Object[] cohere(String p, JsonNode j) throws InterruptedException {
        if (p.endsWith("/models")) {
            ArrayNode m = JSON.createArrayNode();
            m.addObject().put("name", "served-model").putArray("endpoints").add("chat");
            m.addObject().put("name", "served-embed").putArray("endpoints").add("embed");
            return ok(JSON.createObjectNode().set("models", m));
        }
        if (p.endsWith("/embed")) {
            ArrayNode f = JSON.createArrayNode();
            j.path("texts").forEach(x -> f.add(vec()));
            return ok(JSON.createObjectNode().set("embeddings", JSON.createObjectNode().set("float", f)));
        }
        String text = lastUser(j.path("messages"));
        Object[] g = gate(text);
        if (g != null) {
            return g;
        }
        ObjectNode msg = JSON.createObjectNode().put("role", "assistant");
        String fin = "COMPLETE";
        if (text.contains("weather")) {
            msg.putArray("tool_calls").addObject().put("id", "c1").put("type", "function").set("function",
                    JSON.createObjectNode().put("name", "get_weather").put("arguments", toolCallArgs().toString()));
            fin = "TOOL_CALL";
        } else {
            msg.putArray("content").addObject().put("type", "text").put("text", "echo: " + text);
        }
        ObjectNode r = JSON.createObjectNode().put("finish_reason", fin);
        r.set("message", msg);
        r.putObject("usage").putObject("tokens").put("input_tokens", 5).put("output_tokens", 3);
        return ok(r);
    }

    private Object[] ollama(String p, JsonNode j) throws InterruptedException {
        if (p.endsWith("/api/tags")) {
            ArrayNode m = JSON.createArrayNode();
            m.addObject().put("name", "llama3.2:latest");
            m.addObject().put("name", "nomic-embed-text:latest");
            return ok(JSON.createObjectNode().set("models", m));
        }
        if (p.endsWith("/api/embed")) {
            ArrayNode e = JSON.createArrayNode();
            j.path("input").forEach(x -> e.add(vec()));
            return ok(JSON.createObjectNode().set("embeddings", e));
        }
        String text = lastUser(j.path("messages"));
        Object[] g = gate(text);
        if (g != null) {
            return g;
        }
        ObjectNode msg = JSON.createObjectNode().put("role", "assistant");
        if (text.contains("weather")) {
            msg.put("content", "");
            msg.putArray("tool_calls").addObject().set("function", JSON.createObjectNode().put("name", "get_weather").set("arguments", toolCallArgs()));
        } else {
            msg.put("content", "echo: " + text);
        }
        ObjectNode r = JSON.createObjectNode().put("model", j.path("model").asText()).put("done", true).put("done_reason", "stop").put("prompt_eval_count", 5)
                .put("eval_count", 3);
        r.set("message", msg);
        return ok(r);
    }

    private Object[] bedrock(String p, JsonNode j) throws InterruptedException {
        if (p.endsWith("/foundation-models")) {
            ArrayNode m = JSON.createArrayNode();
            m.addObject().put("modelId", "anthropic.claude-sonnet-5-5").putArray("outputModalities").add("TEXT");
            m.addObject().put("modelId", "amazon.titan-embed-text-v2:0").putArray("outputModalities").add("EMBEDDING");
            return ok(JSON.createObjectNode().set("modelSummaries", m));
        }
        if (p.endsWith("/invoke")) {
            if (j.has("texts")) {
                ArrayNode f = JSON.createArrayNode();
                j.path("texts").forEach(x -> f.add(vec()));
                return ok(JSON.createObjectNode().set("embeddings", JSON.createObjectNode().set("float", f)));
            }
            return ok(JSON.createObjectNode().set("embedding", vec()));
        }
        JsonNode msgs = j.path("messages");
        StringBuilder sb = new StringBuilder();
        msgs.get(msgs.size() - 1).path("content").forEach(b -> sb.append(b.path("text").asText("")));
        String text = sb.toString();
        Object[] g = gate(text);
        if (g != null) {
            return g;
        }
        ArrayNode blocks = JSON.createArrayNode();
        String stop = "end_turn";
        if (text.contains("weather")) {
            blocks.addObject().set("toolUse", JSON.createObjectNode().put("toolUseId", "c1").put("name", "get_weather").set("input", toolCallArgs()));
            stop = "tool_use";
        } else {
            blocks.addObject().put("text", "echo: " + text);
        }
        ObjectNode r = JSON.createObjectNode().put("stopReason", stop);
        r.putObject("output").putObject("message").put("role", "assistant").set("content", blocks);
        r.putObject("usage").put("inputTokens", 5).put("outputTokens", 3);
        return ok(r);
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
