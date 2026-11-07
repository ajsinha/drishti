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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Delta Sharing server for tests: the protocol's REST endpoints (shares, schemas, tables, version, metadata, query) for tables written
 * by {@link SparkDeltaTable}, and their Parquet files at pre-signed-style URLs ({@code /files/<path>?X-Amz-Expires=…&exp=…&sig=…}) that
 * expire after {@link #urlTtlMs}, honour {@code Range}, and refuse an expired or unsigned link with 403, as S3 does.
 */
public final class MockSharingServer implements AutoCloseable {

    public static final String TOKEN = "sharing-test-token";

    private final HttpServer server;
    private final Map<String, SparkDeltaTable.Written> tables = new ConcurrentHashMap<>();
    /** How long a file URL lives. */
    public volatile long urlTtlMs = 3_600_000;
    public final AtomicInteger queries = new AtomicInteger();
    public final AtomicInteger expiredRefusals = new AtomicInteger();
    /** The jsonPredicateHints of each query. */
    public final List<String> hints = new CopyOnWriteArrayList<>();
    public final AtomicInteger rangedReads = new AtomicInteger();
    /** File reads to refuse with 403 although their link is valid (a store that expired the link early). */
    public final AtomicInteger rejectNext = new AtomicInteger();

    public MockSharingServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/delta-sharing";
    }

    /** Shares {@code t} as {@code share.schema.table}. */
    public void share(String share, String schema, String table, SparkDeltaTable.Written t) {
        tables.put(share + "." + schema + "." + table, t);
    }

    /** A profile file for this server, its token an environment reference. */
    public Path profile(Path dir, String envName) throws IOException {
        Path p = dir.resolve("provider.share");
        Files.writeString(p, "{\"shareCredentialsVersion\":1,\"endpoint\":\"" + endpoint() + "/\",\"bearerToken\":\"${" + envName + "}\"}");
        return p;
    }

    private void handle(HttpExchange x) throws IOException {
        try (x) {
            String path = x.getRequestURI().getRawPath();
            if (path.startsWith("/files/")) {
                file(x, URLDecoder.decode(path.substring(7), StandardCharsets.UTF_8));
                return;
            }
            if (!("Bearer " + TOKEN).equals(x.getRequestHeaders().getFirst("Authorization"))) {
                MockUnityCatalog.send(x, 401, "{\"errorCode\":\"UNAUTHENTICATED\",\"message\":\"The bearer token is invalid\"}");
                return;
            }
            String[] p = path.replaceFirst("^/delta-sharing", "").replaceAll("^/+", "").split("/");
            List<String> seg = java.util.Arrays.stream(p).map(s -> URLDecoder.decode(s, StandardCharsets.UTF_8)).toList();
            if (seg.size() == 1 && seg.get(0).equals("shares")) {
                items(x, tables.keySet().stream().map(k -> k.split("\\.")[0]).distinct().sorted().toList());
            } else if (seg.size() == 3 && seg.get(2).equals("schemas")) {
                items(x, tables.keySet().stream().filter(k -> k.startsWith(seg.get(1) + ".")).map(k -> k.split("\\.")[1]).distinct().sorted().toList());
            } else if (seg.size() == 5 && seg.get(4).equals("tables")) {
                String prefix = seg.get(1) + "." + seg.get(3) + ".";
                if (tables.keySet().stream().noneMatch(k -> k.startsWith(prefix))) {
                    MockUnityCatalog.send(x, 404, "{\"errorCode\":\"RESOURCE_DOES_NOT_EXIST\",\"message\":\"schema not found\"}");
                    return;
                }
                items(x, tables.keySet().stream().filter(k -> k.startsWith(prefix)).map(k -> k.substring(prefix.length())).sorted().toList());
            } else if (seg.size() == 7 && seg.get(4).equals("tables")) {
                String name = seg.get(1) + "." + seg.get(3) + "." + seg.get(5);
                SparkDeltaTable.Written t = tables.get(name);
                if (t == null) {
                    MockUnityCatalog.send(x, 404, "{\"errorCode\":\"RESOURCE_DOES_NOT_EXIST\",\"message\":\"table " + name + " not found\"}");
                    return;
                }
                x.getResponseHeaders().set("delta-table-version", String.valueOf(t.version()));
                switch (seg.get(6)) {
                    case "version" -> MockUnityCatalog.send(x, 200, "");
                    case "metadata" -> ndjson(x, header(t));
                    case "query" -> {
                        queries.incrementAndGet();
                        JsonNode body = SparkDeltaTable.JSON.readTree(x.getRequestBody());
                        if (body.hasNonNull("jsonPredicateHints")) {
                            hints.add(body.get("jsonPredicateHints").asText());
                        }
                        StringBuilder b = new StringBuilder(header(t));
                        long exp = System.currentTimeMillis() + urlTtlMs;
                        for (SparkDeltaTable.AddedFile f : t.files()) {
                            ObjectNode line = SparkDeltaTable.JSON.createObjectNode();
                            ObjectNode o = line.putObject("file");
                            String file = t.dir().getFileName() + "/" + f.path();
                            o.put("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/files/" + file + "?X-Amz-Expires=" + urlTtlMs / 1000
                                    + "&exp=" + exp + "&sig=" + Integer.toHexString((file + exp).hashCode()));
                            o.put("id", Integer.toHexString(f.path().hashCode()));
                            ObjectNode pv = o.putObject("partitionValues");
                            f.partitionValues().forEach(pv::put);
                            o.put("size", f.size()).put("stats", f.stats()).put("expirationTimestamp", exp);
                            b.append(line).append('\n');
                        }
                        ndjson(x, b.toString());
                    }
                    default -> MockUnityCatalog.send(x, 404, "{}");
                }
            } else {
                MockUnityCatalog.send(x, 404, "{\"errorCode\":\"RESOURCE_DOES_NOT_EXIST\",\"message\":\"" + path + "\"}");
            }
        }
    }

    private static String header(SparkDeltaTable.Written t) {
        ObjectNode protocol = SparkDeltaTable.JSON.createObjectNode();
        protocol.putObject("protocol").put("minReaderVersion", 1);
        ObjectNode md = SparkDeltaTable.JSON.createObjectNode();
        ObjectNode m = md.putObject("metaData");
        m.put("id", t.id());
        m.putObject("format").put("provider", "parquet");
        m.put("schemaString", t.schemaString());
        ArrayNode pc = m.putArray("partitionColumns");
        t.partitionColumns().forEach(pc::add);
        return protocol + "\n" + md + "\n";
    }

    private void file(HttpExchange x, String rel) throws IOException {
        Map<String, String> q = new java.util.HashMap<>();
        String raw = x.getRequestURI().getRawQuery();
        for (String kv : raw == null ? new String[0] : raw.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                q.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        long exp = Long.parseLong(q.getOrDefault("exp", "0"));
        if (!q.containsKey("sig") || exp < System.currentTimeMillis() || rejectNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            expiredRefusals.incrementAndGet();
            x.getResponseHeaders().set("Content-Type", "application/xml");
            MockUnityCatalog.send(x, 403, "<Error><Code>AccessDenied</Code><Message>Request has expired</Message></Error>");
            return;
        }
        String table = rel.substring(0, rel.indexOf('/'));
        SparkDeltaTable.Written t = tables.values().stream().filter(w -> w.dir().getFileName().toString().equals(table)).findFirst().orElse(null);
        if (t == null) {
            MockUnityCatalog.send(x, 404, "");
            return;
        }
        byte[] all = Files.readAllBytes(t.dir().resolve(rel.substring(rel.indexOf('/') + 1)));
        String range = x.getRequestHeaders().getFirst("Range");
        if (range != null && range.startsWith("bytes=")) {
            rangedReads.incrementAndGet();
            String[] r = range.substring(6).split("-");
            int from = Integer.parseInt(r[0]);
            int to = r.length > 1 && !r[1].isEmpty() ? Math.min(all.length - 1, Integer.parseInt(r[1])) : all.length - 1;
            byte[] part = java.util.Arrays.copyOfRange(all, from, to + 1);
            x.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + all.length);
            x.sendResponseHeaders(206, part.length);
            x.getResponseBody().write(part);
            return;
        }
        x.sendResponseHeaders(200, all.length);
        x.getResponseBody().write(all);
    }

    private static void items(HttpExchange x, List<String> names) throws IOException {
        ObjectNode o = SparkDeltaTable.JSON.createObjectNode();
        ArrayNode arr = o.putArray("items");
        names.forEach(n -> arr.addObject().put("name", n));
        MockUnityCatalog.send(x, 200, o.toString());
    }

    private static void ndjson(HttpExchange x, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
        x.sendResponseHeaders(200, b.length);
        x.getResponseBody().write(b);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
