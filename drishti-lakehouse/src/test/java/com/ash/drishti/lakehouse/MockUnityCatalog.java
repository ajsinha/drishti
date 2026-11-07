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
package com.ash.drishti.lakehouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Unity Catalog REST server for tests ({@code /api/2.1/unity-catalog}), answering as Databricks does: tables by name, the tables of a
 * schema, temporary table credentials (AWS keys that expire after {@link #credentialTtlMs}), OAuth machine-to-machine tokens at
 * {@code /oidc/v1/token}, and the refusals (no table, no grant, credential vending off, a bad token) with Databricks' error codes.
 */
public final class MockUnityCatalog implements AutoCloseable {

    public static final String TOKEN = "uc-test-token";
    public static final String CLIENT_ID = "sp-drishti";
    public static final String CLIENT_SECRET = "sp-secret";

    /** One table: its id, where it is stored, its format and type. */
    public record UcTable(String id, String location, String format, String type) {}

    private final HttpServer server;
    private final Map<String, UcTable> tables = new ConcurrentHashMap<>();
    /** Tables the principal has no grant on. */
    public final Set<String> denied = ConcurrentHashMap.newKeySet();
    /** Whether credential vending is off for the metastore. */
    public volatile boolean vendingOff;
    /** How long vended credentials last. */
    public volatile long credentialTtlMs = 3_600_000;
    /** The keys handed out (S3Mock accepts any). */
    public volatile String accessKey = "ASIA-VENDED";
    public final AtomicInteger credentialCalls = new AtomicInteger();
    public final AtomicInteger oauthCalls = new AtomicInteger();

    public MockUnityCatalog() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String host() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void table(String fullName, String location) {
        tables.put(fullName, new UcTable(java.util.UUID.nameUUIDFromBytes(fullName.getBytes(StandardCharsets.UTF_8)).toString(), location, "DELTA",
                "EXTERNAL"));
    }

    public void table(String fullName, UcTable t) {
        tables.put(fullName, t);
    }

    private void handle(HttpExchange x) throws IOException {
        try (x) {
            String path = x.getRequestURI().getRawPath();
            if (path.equals("/oidc/v1/token")) {
                String basic = "Basic " + Base64.getEncoder().encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
                if (!basic.equals(x.getRequestHeaders().getFirst("Authorization"))) {
                    send(x, 401, "{\"error\":\"invalid_client\"}");
                    return;
                }
                oauthCalls.incrementAndGet();
                send(x, 200, "{\"access_token\":\"oauth-" + oauthCalls.get() + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
                return;
            }
            String auth = x.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !(auth.equals("Bearer " + TOKEN) || auth.startsWith("Bearer oauth-"))) {
                send(x, 401, "{\"error_code\":\"UNAUTHENTICATED\",\"message\":\"Invalid access token.\"}");
                return;
            }
            String api = "/api/2.1/unity-catalog";
            if (!path.startsWith(api)) {
                send(x, 404, "{}");
                return;
            }
            String rest = path.substring(api.length());
            if (rest.startsWith("/tables/") && x.getRequestMethod().equals("GET")) {
                String name = URLDecoder.decode(rest.substring(8), StandardCharsets.UTF_8);
                UcTable t = tables.get(name);
                if (t == null) {
                    send(x, 404, "{\"error_code\":\"TABLE_DOES_NOT_EXIST\",\"message\":\"Table '" + name + "' does not exist.\"}");
                    return;
                }
                if (denied.contains(name)) {
                    send(x, 403, "{\"error_code\":\"PERMISSION_DENIED\",\"message\":\"User does not have SELECT on Table '" + name + "'.\"}");
                    return;
                }
                String[] p = name.split("\\.");
                ObjectNode o = SparkDeltaTable.JSON.createObjectNode().put("name", p[2]).put("catalog_name", p[0]).put("schema_name", p[1])
                        .put("full_name", name).put("table_id", t.id()).put("table_type", t.type()).put("data_source_format", t.format())
                        .put("storage_location", t.location());
                send(x, 200, o.toString());
                return;
            }
            if (rest.equals("/tables") && x.getRequestMethod().equals("GET")) {
                Map<String, String> q = query(x.getRequestURI().getRawQuery());
                String prefix = q.get("catalog_name") + "." + q.get("schema_name") + ".";
                ObjectNode o = SparkDeltaTable.JSON.createObjectNode();
                ArrayNode arr = o.putArray("tables");
                tables.keySet().stream().filter(k -> k.startsWith(prefix)).sorted()
                        .forEach(k -> arr.addObject().put("name", k.substring(prefix.length())).put("full_name", k));
                send(x, 200, o.toString());
                return;
            }
            if (rest.equals("/temporary-table-credentials") && x.getRequestMethod().equals("POST")) {
                JsonNode body = SparkDeltaTable.JSON.readTree(x.getRequestBody());
                String id = body.path("table_id").asText();
                String name = tables.entrySet().stream().filter(e -> e.getValue().id().equals(id)).map(Map.Entry::getKey).findFirst().orElse(null);
                if (name == null) {
                    send(x, 404, "{\"error_code\":\"TABLE_DOES_NOT_EXIST\",\"message\":\"Table with id " + id + " does not exist.\"}");
                    return;
                }
                if (vendingOff) {
                    send(x, 403, "{\"error_code\":\"PERMISSION_DENIED\",\"message\":\"Credential vending for external data access is not enabled on "
                            + "metastore; or missing EXTERNAL USE SCHEMA.\"}");
                    return;
                }
                if (denied.contains(name)) {
                    send(x, 403, "{\"error_code\":\"PERMISSION_DENIED\",\"message\":\"User does not have SELECT on Table '" + name + "'.\"}");
                    return;
                }
                int n = credentialCalls.incrementAndGet();
                ObjectNode o = SparkDeltaTable.JSON.createObjectNode();
                o.putObject("aws_temp_credentials").put("access_key_id", accessKey + "-" + n).put("secret_access_key", "vended-secret-" + n)
                        .put("session_token", "vended-session-" + n);
                o.put("expiration_time", System.currentTimeMillis() + credentialTtlMs);
                send(x, 200, o.toString());
                return;
            }
            send(x, 404, "{\"error_code\":\"ENDPOINT_NOT_FOUND\",\"message\":\"" + rest + "\"}");
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new java.util.HashMap<>();
        if (raw != null) {
            for (String kv : raw.split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    out.put(URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return out;
    }

    static void send(HttpExchange x, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            x.getResponseBody().write(b);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
