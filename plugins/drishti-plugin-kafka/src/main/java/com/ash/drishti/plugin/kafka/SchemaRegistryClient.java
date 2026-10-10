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
package com.ash.drishti.plugin.kafka;

import com.ash.drishti.api.tls.Secrets;
import com.ash.drishti.api.tls.TlsContexts;
import com.ash.drishti.api.tls.TlsException;
import com.ash.drishti.api.tls.TlsMaterial;
import com.ash.drishti.api.tls.TlsSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.net.ssl.HttpsURLConnection;
import org.apache.avro.Schema;

/**
 * A client for the Confluent Schema Registry REST API, used to find the writer schema of a message by its id
 * ({@code GET /schemas/ids/<id>}). Works against Confluent Cloud, Confluent Platform and Apicurio's Confluent-compatible API.
 *
 * <p>Settings: {@code schema-registry.url}; {@code schema-registry.basic-auth} ({@code ${KEY}:${SECRET}}, or
 * {@code schema-registry.basic-auth-file}) or {@code schema-registry.bearer-token}; {@code schema-registry.timeout-ms}
 * (10000); and {@code schema-registry.tls.*} (the shared TLS module: private CA, mutual TLS, ...). A schema never changes
 * under its id, so it is fetched once and kept.
 */
final class SchemaRegistryClient {

    /** What the registry knows about one schema id. */
    record Registered(String type, Schema avro) {
        static final String AVRO = "AVRO";
        static final String JSON = "JSON";
        static final String PROTOBUF = "PROTOBUF";
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final URI base;
    private final String authorization;
    private final TlsMaterial tls;
    private final int timeoutMs;
    private final Map<Integer, Registered> cache = new ConcurrentHashMap<>();

    SchemaRegistryClient(Map<String, String> settings) {
        this(settings, System::getenv);
    }

    SchemaRegistryClient(Map<String, String> settings, Function<String, String> env) {
        String url = Secrets.get(settings, "schema-registry.url", env);
        if (url == null) {
            throw new TlsException("schema-registry.url is not set");
        }
        this.base = URI.create(url.endsWith("/") ? url : url + "/");
        if (!"http".equals(base.getScheme()) && !"https".equals(base.getScheme())) {
            throw new TlsException("schema-registry.url '" + url + "' must start with http:// or https://");
        }
        String basic = Secrets.secret(settings, "schema-registry.basic-auth", env);
        String bearer = Secrets.secret(settings, "schema-registry.bearer-token", env);
        if (basic != null && bearer != null) {
            throw new TlsException("schema-registry.basic-auth and schema-registry.bearer-token are both set: use one");
        }
        if (basic != null && !basic.contains(":")) {
            throw new TlsException("schema-registry.basic-auth must be KEY:SECRET (for example ${SR_KEY}:${SR_SECRET})");
        }
        this.authorization = basic != null ? "Basic " + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8))
                : bearer != null ? "Bearer " + bearer : null;
        this.timeoutMs = Integer.parseInt(Secrets.get(settings, "schema-registry.timeout-ms", env) == null ? "10000"
                : Secrets.get(settings, "schema-registry.timeout-ms", env));
        TlsSettings ts = TlsSettings.parse(settings, "schema-registry.tls.", env);
        this.tls = "https".equals(base.getScheme()) ? TlsContexts.build(ts, env, java.time.Clock.systemUTC()) : null;
        if (tls == null && TlsSettings.anyGiven(settings, "schema-registry.tls.")) {
            throw new TlsException("schema-registry.tls.* is set but schema-registry.url is http://; use https://");
        }
    }

    TlsMaterial tls() {
        return tls;
    }

    /** The schema registered under {@code id} (fetched once). */
    Registered schema(int id) throws IOException {
        Registered r = cache.get(id);
        if (r != null) {
            return r;
        }
        JsonNode body = get("schemas/ids/" + id);
        String type = body.hasNonNull("schemaType") ? body.get("schemaType").asText().toUpperCase(java.util.Locale.ROOT) : Registered.AVRO;
        String text = body.path("schema").asText();
        if (text.isEmpty()) {
            throw new IOException("the registry returned no schema for id " + id);
        }
        Schema avro = null;
        if (Registered.AVRO.equals(type)) {
            try {
                avro = new Schema.Parser().parse(text);
            } catch (RuntimeException e) {
                throw new IOException("schema id " + id + " is not valid Avro: " + e.getMessage(), e);
            }
        }
        Registered reg = new Registered(type, avro);
        cache.put(id, reg);
        return reg;
    }

    private JsonNode get(String path) throws IOException {
        URL url = base.resolve(path).toURL();
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("Accept", "application/vnd.schemaregistry.v1+json, application/json");
            if (authorization != null) {
                c.setRequestProperty("Authorization", authorization);
            }
            if (c instanceof HttpsURLConnection https && tls != null) {
                https.setSSLSocketFactory(new ParametersFactory(tls));
                if (!tls.settings().verifyHostname()) {
                    https.setHostnameVerifier((host, session) -> true);
                }
            }
            int status = c.getResponseCode();
            if (status == 401 || status == 403) {
                throw new IOException("the schema registry refused the credentials (HTTP " + status + "): check schema-registry.basic-auth");
            }
            if (status == 404) {
                throw new IOException("the schema registry has no schema for " + path + " (HTTP 404)");
            }
            if (status >= 400) {
                throw new IOException("the schema registry answered HTTP " + status + " for " + path);
            }
            try (InputStream in = c.getInputStream()) {
                return JSON.readTree(in);
            }
        } finally {
            c.disconnect();
        }
    }
}
