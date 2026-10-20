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

import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.meta.TableMetadata;
import com.ash.drishti.iceberg.meta.TableUpdate;
import com.ash.drishti.iceberg.types.UnsupportedFeature;
import com.ash.drishti.lakeio.S3Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One table of a REST catalog: {@code loadTable} (with the last answer's ETag, so an unchanged table costs one small
 * answer, 304), {@code commitTable} with the update's requirements (409 is a {@link CommitConflict}), and the S3
 * credentials the catalog vends for the table ({@code X-Iceberg-Access-Delegation: vended-credentials}), fetched again
 * five minutes before they expire. Thread-safe.
 */
final class RestTableOps implements TableOps {

    private static final long RENEW_BEFORE_MS = 5 * 60_000L;

    private final RestClient client;
    private final String path;
    private final List<String> namespace;
    private final String name;
    private final FileAccess base;
    private final boolean vend;
    private final ReentrantLock credentialsLock = new ReentrantLock();
    private volatile Map<String, String> vended = Map.of();
    private volatile long vendedExpiresAt = Long.MAX_VALUE;
    private volatile FileAccess io;
    private volatile FileAccess previous;

    RestTableOps(RestClient client, String path, List<String> namespace, String name, FileAccess base, boolean vend) {
        this.client = client;
        this.path = path;
        this.namespace = List.copyOf(namespace);
        this.name = name;
        this.base = base;
        this.vend = vend;
        this.io = base;
    }

    private Map<String, String> headers(String etag) {
        Map<String, String> h = new HashMap<>();
        if (vend) {
            h.put("X-Iceberg-Access-Delegation", "vended-credentials");
        }
        if (etag != null) {
            h.put("If-None-Match", etag);
        }
        return h;
    }

    @Override
    public Loaded load() throws IOException {
        RestClient.Answer a = client.get(path, headers(null));
        return loaded(a);
    }

    private Loaded loaded(RestClient.Answer a) throws IOException {
        if (a.status() == 404) {
            throw new NoSuchTable("the catalog has no table " + String.join(".", namespace) + "." + name);
        }
        if (a.status() != 200 || a.body() == null) {
            throw new IOException("loadTable " + String.join(".", namespace) + "." + name + ": HTTP " + a.status() + " " + RestClient.error(a));
        }
        credentials(a.body());
        TableMetadata md = new TableMetadata((ObjectNode) a.body().get("metadata"));
        return new Loaded(md, a.body().path("metadata-location").asText(null), a.etag());
    }

    @Override
    public Optional<Loaded> refresh(Loaded current) throws IOException {
        RestClient.Answer a = client.get(path, headers(current.etag()));
        if (a.status() == 304) {
            return Optional.empty();
        }
        Loaded l = loaded(a);
        if (l.location() != null && l.location().equals(current.location())) {
            return Optional.empty();
        }
        return Optional.of(l);
    }

    @Override
    public Loaded commit(Loaded base, TableUpdate update) throws IOException {
        RestClient.Answer a = client.post(path, update.restRequest(namespace, name), Map.of());
        switch (a.status()) {
            case 200 -> {
                TableMetadata md = new TableMetadata((ObjectNode) a.body().get("metadata"));
                return new Loaded(md, a.body().path("metadata-location").asText(null), a.etag());
            }
            case 409 -> throw new CommitConflict("the catalog refused the commit to " + name + ": " + RestClient.error(a));
            case 404 -> throw new NoSuchTable("the catalog has no table " + String.join(".", namespace) + "." + name);
            default -> throw new IOException("commitTable " + name + ": HTTP " + a.status() + " " + RestClient.error(a)
                    + (a.status() >= 500 ? " (the commit may or may not have been applied: the table is re-read before any retry)" : ""));
        }
    }

    /** The table's vended S3 settings from a loadTable answer: its {@code config}, or its {@code storage-credentials}. */
    private void credentials(JsonNode body) {
        Map<String, String> c = new LinkedHashMap<>();
        body.path("config").fields().forEachRemaining(e -> c.put(e.getKey(), e.getValue().asText()));
        String location = body.path("metadata").path("location").asText("");
        for (JsonNode sc : body.path("storage-credentials")) {
            if (location.startsWith(sc.path("prefix").asText("\u0000"))) {
                sc.path("config").fields().forEachRemaining(e -> c.put(e.getKey(), e.getValue().asText()));
            }
        }
        if (Boolean.parseBoolean(c.getOrDefault("s3.remote-signing-enabled", "false"))) {
            throw new UnsupportedFeature("S3 remote signing (s3.remote-signing-enabled)").in(name);
        }
        if (!c.containsKey("s3.access-key-id") && !c.containsKey("s3.endpoint")) {
            return;                                            // nothing vended: the connector's own s3.* settings
        }
        credentialsLock.lock();
        try {
            if (c.equals(vended)) {
                return;
            }
            S3Settings s = base.s3Settings();
            if (c.containsKey("s3.access-key-id")) {
                s = s.withCredentials(c.get("s3.access-key-id"), c.getOrDefault("s3.secret-access-key", ""), c.getOrDefault("s3.session-token", ""));
            }
            if (c.containsKey("s3.endpoint") || c.containsKey("client.region") || c.containsKey("s3.path-style-access")) {
                String endpoint = c.getOrDefault("s3.endpoint", s.endpoint());
                s = s.withStore(endpoint, c.getOrDefault("client.region", s.region()),
                        Boolean.parseBoolean(c.getOrDefault("s3.path-style-access", String.valueOf(s.pathStyle()))));
            }
            FileAccess old = previous;
            previous = io == base ? null : io;
            io = base.withS3(s);
            vended = c;
            vendedExpiresAt = Long.parseLong(c.getOrDefault("s3.session-token-expires-at-ms", String.valueOf(Long.MAX_VALUE)));
            if (old != null) {
                old.close();                                   // two generations back: no read still uses it
            }
        } finally {
            credentialsLock.unlock();
        }
    }

    @Override
    public FileAccess io(Loaded current) {
        if (System.currentTimeMillis() > vendedExpiresAt - RENEW_BEFORE_MS) {
            try {
                credentials(client.get(path, headers(null)).body());   // the same call, for its fresh credentials
            } catch (IOException | RuntimeException e) {
                // the catalog is unreachable: the old credentials serve until they really expire
            }
        }
        return io;
    }

    @Override
    public String describe() {
        return client.base() + " " + String.join(".", namespace) + "." + name;
    }
}
