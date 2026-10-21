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
package com.ash.drishti.iceberg.catalog;

import com.ash.drishti.api.proxy.ProxySettings;
import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.meta.SchemaJson;
import com.ash.drishti.iceberg.meta.SortOrder;
import com.ash.drishti.iceberg.meta.TableMetadata;
import com.ash.drishti.iceberg.types.Schema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A namespace of an Iceberg REST catalog (Apache Polaris, Snowflake Open Catalog, AWS Glue's Iceberg endpoint, Unity
 * Catalog, Nessie, Lakekeeper, Gravitino), spoken directly over HTTP: {@code GET /v1/config} for the server's defaults,
 * overrides and {@code prefix}; then list, load, create and commit tables under {@code /v1/{prefix}/namespaces/{ns}}.
 * Settings as the library engine's: {@code uri}, {@code warehouse}, {@code credential} or {@code token}, {@code scope},
 * {@code oauth2-server-uri}, {@code prefix}, {@code namespace}, {@code catalog.<key>} (Iceberg catalog properties, such
 * as {@code catalog.rest.sigv4-enabled}); {@code vended-credentials: false} stops asking for the table's own S3
 * credentials. Thread-safe.
 */
public final class RestCatalog implements Catalog {

    private final RestClient client;
    private final String prefixPath;
    private final List<String> namespace;
    private final FileAccess io;
    private final boolean vend;
    private final String uri;

    private RestCatalog(RestClient client, String prefixPath, List<String> namespace, FileAccess io, boolean vend, String uri) {
        this.client = client;
        this.prefixPath = prefixPath;
        this.namespace = namespace;
        this.io = io;
        this.vend = vend;
        this.uri = uri;
    }

    static RestCatalog open(Map<String, String> s, String domain, FileAccess io) {
        String uri = s.getOrDefault("uri", "").trim();
        if (uri.isBlank()) {
            io.close();
            throw new IllegalArgumentException("a REST catalog needs uri");
        }
        Map<String, String> given = new HashMap<>();
        s.forEach((k, v) -> {
            if (k.startsWith("catalog.")) {
                given.put(k.substring(8), v);
            }
        });
        Map<String, String> props = given;
        RestClient client = new RestClient(uri, s, given);
        String warehouse = s.getOrDefault("warehouse", "");
        try {
            RestClient.Answer a = client.get("/v1/config" + (warehouse.isBlank() ? "" : "?warehouse=" + RestClient.segment(warehouse)), Map.of());
            if (a.status() != 200) {
                throw new IOException("GET /v1/config: HTTP " + a.status() + " " + RestClient.error(a));
            }
            Map<String, String> merged = new HashMap<>();
            a.body().path("defaults").fields().forEachRemaining(e -> merged.put(e.getKey(), e.getValue().asText()));
            merged.putAll(given);
            a.body().path("overrides").fields().forEachRemaining(e -> merged.put(e.getKey(), e.getValue().asText()));
            if (merged.containsKey("uri") && !merged.get("uri").replaceAll("/+$", "").equals(uri.replaceAll("/+$", ""))) {
                client = new RestClient(merged.get("uri"), s, merged);
            }
            props = merged;
        } catch (IOException e) {
            io.close();
            throw new java.io.UncheckedIOException("cannot reach the REST catalog at " + uri + ": " + com.ash.drishti.iceberg.io.Reasons.of(e), e);   // never ": null" (QA4-07)
        }
        String prefix = !s.getOrDefault("prefix", "").isBlank() ? s.get("prefix") : props.getOrDefault("prefix", "");
        String ns = s.getOrDefault("namespace", "").isBlank() ? domain : s.get("namespace");
        List<String> levels = ns.isBlank() ? List.of() : Arrays.asList(ns.split("\\."));
        return new RestCatalog(client, prefix.isBlank() ? "/v1" : "/v1/" + prefix.replaceAll("^/+|/+$", ""), levels, io,
                Boolean.parseBoolean(s.getOrDefault("vended-credentials", "true")), uri);
    }

    private String nsPath() {
        return prefixPath + "/namespaces/" + RestClient.segment(String.join("\u001f", namespace));
    }

    private String tablePath(String name) {
        return nsPath() + "/tables/" + RestClient.segment(name);
    }

    private RestTableOps ops(String name) {
        return new RestTableOps(client, tablePath(name), namespace, name, io, vend);
    }

    public ProxySettings proxy() {
        return client.proxy();
    }

    @Override
    public Optional<Table> load(String name) throws IOException {
        RestTableOps ops = ops(name);
        try {
            return Optional.of(Table.load(name, ops));
        } catch (NoSuchTable e) {
            return Optional.empty();
        }
    }

    @Override
    public List<String> tables() throws IOException {
        List<String> out = new ArrayList<>();
        String page = null;
        do {
            RestClient.Answer a = client.get(nsPath() + "/tables" + (page == null ? "" : "?pageToken=" + RestClient.segment(page)), Map.of());
            if (a.status() == 404) {
                return List.of();
            }
            if (a.status() != 200) {
                throw new IOException("listTables " + String.join(".", namespace) + ": HTTP " + a.status() + " " + RestClient.error(a));
            }
            a.body().path("identifiers").forEach(i -> out.add(i.path("name").asText()));
            JsonNode next = a.body().path("next-page-token");
            page = next.isTextual() && !next.asText().isEmpty() ? next.asText() : null;
        } while (page != null);
        out.sort(null);
        return out;
    }

    @Override
    public Table create(String name, Schema schema, PartitionSpec spec, SortOrder order, Map<String, String> properties) throws IOException {
        if (!namespace.isEmpty() && !namespaceExists()) {
            ObjectNode body = RestClient.object();
            var levels = body.putArray("namespace");
            namespace.forEach(levels::add);
            body.putObject("properties");
            RestClient.Answer a = client.post(prefixPath + "/namespaces", body, Map.of());
            if (a.status() != 200 && a.status() != 409) {
                throw new IOException("createNamespace " + String.join(".", namespace) + ": HTTP " + a.status() + " " + RestClient.error(a));
            }
        }
        ObjectNode body = RestClient.object();
        body.put("name", name);
        body.set("schema", SchemaJson.write(schema));
        body.set("partition-spec", spec.write());
        body.set("write-order", order.write());
        body.put("stage-create", false);
        ObjectNode props = body.putObject("properties");
        properties.forEach(props::put);
        Map<String, String> h = vend ? Map.of("X-Iceberg-Access-Delegation", "vended-credentials") : Map.of();
        RestClient.Answer a = client.post(nsPath() + "/tables", body, h);
        if (a.status() == 409) {
            throw new TableExists("the catalog already has a table " + String.join(".", namespace) + "." + name);
        }
        if (a.status() != 200) {
            throw new IOException("createTable " + name + ": HTTP " + a.status() + " " + RestClient.error(a));
        }
        RestTableOps ops = ops(name);
        return new Table(name, ops, new TableOps.Loaded(new TableMetadata((ObjectNode) a.body().get("metadata")),
                a.body().path("metadata-location").asText(null), a.etag()));
    }

    private boolean namespaceExists() throws IOException {
        RestClient.Answer a = client.get(nsPath(), Map.of());
        return a.status() == 200 || a.status() == 204;
    }

    @Override
    public boolean reachable() {
        try {
            return namespace.isEmpty() || namespaceExists();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public String describe() {
        return uri + " namespace " + String.join(".", namespace);
    }

    @Override
    public void close() {
        io.close();
    }
}
