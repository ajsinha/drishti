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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code transport: streamable-http}: the MCP Streamable HTTP transport. Every message is a POST of one JSON-RPC message to the
 * server's single endpoint; the answer is JSON or a Server-Sent-Events stream carrying it. The session id the server returns on
 * initialize is sent back on every later call. (The older HTTP+SSE transport is not implemented.)
 */
public final class StreamableHttpMcpClient implements McpClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROTOCOL = "2025-11-25";
    private static final int MAX_BODY = 8_000_000;

    private final McpServerConfig cfg;
    private final HttpClient http;
    private final ProxySettings proxy;
    private final URI uri;
    private final AtomicLong ids = new AtomicLong();
    private volatile boolean connected;
    private volatile String session;
    private volatile String protocol = PROTOCOL;

    StreamableHttpMcpClient(McpServerConfig cfg) {
        this.cfg = cfg;
        try {
            this.uri = URI.create(cfg.url());
        } catch (IllegalArgumentException e) {
            throw new LlmException(LlmException.Kind.CONFIG, "url is not a valid URL");
        }
        this.proxy = ProxySettings.resolve(cfg.settings(), cfg.url());             // proxy.* or the site default; https is tunnelled with CONNECT
        HttpClient.Builder b = proxy.apply(HttpClient.newBuilder().connectTimeout(cfg.timeout()).version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER));
        TlsMaterial tls = TlsHttp.material(cfg.settings(), cfg.url(), "url");
        this.http = (tls == null ? b : TlsHttp.apply(b, tls)).build();
    }

    @Override
    public String server() {
        return cfg.name();
    }

    @Override
    public synchronized void connect() {
        if (connected) {
            return;
        }
        ObjectNode p = JSON.createObjectNode();
        p.put("protocolVersion", PROTOCOL);
        p.putObject("capabilities");
        p.putObject("clientInfo").put("name", "drishti").put("version", "1");
        JsonNode r = rpc("initialize", p, true);
        protocol = r.path("protocolVersion").asText(PROTOCOL);
        post(note("notifications/initialized"), false);
        connected = true;
    }

    @Override
    public List<McpTypes.Tool> tools() {
        List<McpTypes.Tool> out = new ArrayList<>();
        paged("tools/list", "tools", n -> out.add(new McpTypes.Tool(n.path("name").asText(), n.path("description").asText(""),
                n.path("inputSchema").toString())));
        return out.stream().filter(t -> cfg.toolAllowed(t.name())).toList();
    }

    @Override
    public List<McpTypes.Resource> resources() {
        List<McpTypes.Resource> out = new ArrayList<>();
        paged("resources/list", "resources", n -> out.add(new McpTypes.Resource(n.path("uri").asText(), n.path("name").asText(""),
                n.path("description").asText(""), n.path("mimeType").asText(""))));
        return out;
    }

    @Override
    public List<McpTypes.Prompt> prompts() {
        List<McpTypes.Prompt> out = new ArrayList<>();
        paged("prompts/list", "prompts", n -> out.add(new McpTypes.Prompt(n.path("name").asText(), n.path("description").asText(""))));
        return out;
    }

    @Override
    public McpTypes.Result callTool(String tool, Map<String, Object> arguments) {
        if (!cfg.toolAllowed(tool)) {
            throw new LlmException(LlmException.Kind.POLICY, "tool '" + tool + "' is not in allowed-tools of " + cfg.name());
        }
        connect();
        ObjectNode p = JSON.createObjectNode();
        p.put("name", tool);
        p.set("arguments", JSON.valueToTree(arguments == null ? Map.of() : arguments));
        JsonNode r = rpc("tools/call", p, false);
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : r.path("content")) {
            if ("text".equals(c.path("type").asText()) || c.has("text")) {
                sb.append(sb.isEmpty() ? "" : "\n").append(c.path("text").asText());
            }
        }
        if (sb.isEmpty() && r.has("structuredContent")) {
            sb.append(r.get("structuredContent").toString());
        }
        return new McpTypes.Result(sb.toString(), r.path("isError").asBoolean(false));
    }

    @Override
    public String readResource(String uriText) {
        connect();
        ObjectNode p = JSON.createObjectNode();
        p.put("uri", uriText);
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : rpc("resources/read", p, false).path("contents")) {
            sb.append(sb.isEmpty() ? "" : "\n").append(c.path("text").asText(""));
        }
        return sb.toString();
    }

    @Override
    public synchronized void close() {
        String s = session;
        connected = false;
        session = null;
        if (s != null) {
            try {
                http.send(headers(HttpRequest.newBuilder(uri).timeout(cfg.timeout()).DELETE(), s).build(), HttpResponse.BodyHandlers.discarding());
            } catch (IOException | RuntimeException e) {
                // the server may not support session termination
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void paged(String method, String field, java.util.function.Consumer<JsonNode> each) {
        connect();
        String cursor = null;
        for (int page = 0; page < 100; page++) {
            ObjectNode p = JSON.createObjectNode();
            if (cursor != null) {
                p.put("cursor", cursor);
            }
            JsonNode r = rpc(method, p, false);
            r.path(field).forEach(each);
            cursor = r.path("nextCursor").asText(null);
            if (cursor == null || cursor.isEmpty()) {
                return;
            }
        }
    }

    private ObjectNode note(String method) {
        ObjectNode n = JSON.createObjectNode();
        n.put("jsonrpc", "2.0").put("method", method);
        return n;
    }

    private JsonNode rpc(String method, ObjectNode params, boolean init) {
        long id = ids.incrementAndGet();
        ObjectNode m = note(method);
        m.put("id", id);
        m.set("params", params);
        JsonNode reply = post(m, true);
        if (reply == null) {
            throw new LlmException(LlmException.Kind.FAILED, "the MCP server sent no answer to " + method);
        }
        if (reply.has("error")) {
            throw new LlmException(LlmException.Kind.FAILED, "the MCP server refused " + method + ": " + reply.path("error").path("message").asText("error"));
        }
        return reply.path("result");
    }

    private HttpRequest.Builder headers(HttpRequest.Builder b, String sessionId) {
        b.header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", protocol);
        if (sessionId != null) {
            b.header("Mcp-Session-Id", sessionId);
        }
        cfg.headers().forEach((k, v) -> {
            if (!v.isEmpty()) {
                b.header(k, v);
            }
        });
        return b;
    }

    /** Sends one message; for a request returns the JSON-RPC answer found in the JSON body or the event stream. */
    private JsonNode post(ObjectNode message, boolean wantAnswer) {
        try {
            HttpRequest req = headers(HttpRequest.newBuilder(uri).timeout(cfg.timeout()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(message), StandardCharsets.UTF_8)), session).build();
            HttpResponse<InputStream> resp = proxy.send(http, req, HttpResponse.BodyHandlers.ofInputStream());
            String sid = resp.headers().firstValue("Mcp-Session-Id").orElse(null);
            if (sid != null) {
                session = sid;
            }
            try (InputStream in = resp.body()) {
                if (resp.statusCode() / 100 != 2) {
                    throw new LlmException(LlmException.Kind.FAILED, "the MCP server answered HTTP " + resp.statusCode());
                }
                if (!wantAnswer) {
                    return null;
                }
                String type = resp.headers().firstValue("Content-Type").orElse("");
                if (type.startsWith("text/event-stream")) {
                    return fromEvents(in, message.path("id").asLong());
                }
                return JSON.readTree(in.readNBytes(MAX_BODY));
            }
        } catch (java.net.http.HttpTimeoutException | java.net.SocketTimeoutException e) {
            throw new LlmException(LlmException.Kind.TIMEOUT, "the MCP server did not answer in time", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.FAILED, "the MCP call was interrupted", e);
        } catch (IOException e) {
            throw new LlmException(LlmException.Kind.FAILED, "the MCP server could not be reached or answered something unreadable", e);
        }
    }

    private static JsonNode fromEvents(InputStream in, long id) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        String line;
        long read = 0;
        while ((line = r.readLine()) != null) {
            read += line.length();
            if (read > MAX_BODY) {
                throw new IOException("event stream too large");
            }
            if (line.startsWith("data:")) {
                data.append(line.substring(5).strip());
            } else if (line.isEmpty() && !data.isEmpty()) {
                JsonNode n = JSON.readTree(data.toString());
                data.setLength(0);
                if (n.has("id") && n.get("id").asLong(-1) == id && (n.has("result") || n.has("error"))) {
                    return n;
                }
            }
        }
        if (!data.isEmpty()) {
            JsonNode n = JSON.readTree(data.toString());
            if (n.has("result") || n.has("error")) {
                return n;
            }
        }
        return null;
    }

    /** {@code transport: streamable-http}. */
    public static final class Factory implements McpClientFactory {
        @Override
        public String transport() {
            return "streamable-http";
        }

        @Override
        public McpClient create(McpServerConfig config) {
            if (config.url().isEmpty()) {
                throw new LlmException(LlmException.Kind.CONFIG, "url is missing");
            }
            return new StreamableHttpMcpClient(config);
        }
    }
}
