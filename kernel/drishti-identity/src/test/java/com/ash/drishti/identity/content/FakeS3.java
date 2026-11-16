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
package com.ash.drishti.identity.content;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;

/**
 * An in-process S3 stand-in for the content store's tests (path style, one bucket): GET, HEAD, PUT and DELETE of objects with the
 * conditional headers ({@code If-Match}, {@code If-None-Match: *}; {@code 412} when they fail), and ListObjectsV2. Only what
 * {@link S3ContentStore} uses; no signature checks.
 */
final class FakeS3 implements AutoCloseable {

    private record Obj(byte[] bytes, String etag, Instant modified) {}

    private final HttpServer server;
    private final TreeMap<String, Obj> objects = new TreeMap<>();
    private long counter;

    FakeS3() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
    }

    String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange x) throws IOException {
        try (x) {
            String path = x.getRequestURI().getPath();
            String[] parts = path.substring(1).split("/", 2);
            Map<String, String> q = query(x.getRequestURI().getRawQuery());
            byte[] body = readBody(x);
            if (parts.length < 2 || parts[1].isEmpty()) {
                if ("GET".equals(x.getRequestMethod()) && "2".equals(q.get("list-type"))) {
                    list(x, q.getOrDefault("prefix", ""));
                } else {
                    send(x, 200, new byte[0]);
                }
                return;
            }
            String key = parts[1];
            String ifMatch = x.getRequestHeaders().getFirst("If-Match");
            String ifNone = x.getRequestHeaders().getFirst("If-None-Match");
            synchronized (objects) {
                Obj o = objects.get(key);
                switch (x.getRequestMethod()) {
                    case "GET", "HEAD" -> {
                        if (o == null) {
                            error(x, 404, "NoSuchKey");
                            return;
                        }
                        x.getResponseHeaders().add("ETag", o.etag());
                        x.getResponseHeaders().add("Last-Modified", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(o.modified().atZone(java.time.ZoneOffset.UTC)));
                        if ("HEAD".equals(x.getRequestMethod())) {
                            x.getResponseHeaders().add("Content-Length", Integer.toString(o.bytes().length));
                            x.sendResponseHeaders(200, -1);
                        } else {
                            send(x, 200, o.bytes());
                        }
                    }
                    case "PUT" -> {
                        if (("*".equals(ifNone) && o != null) || (ifMatch != null && (o == null || !ifMatch.equals(o.etag())))) {
                            error(x, 412, "PreconditionFailed");
                            return;
                        }
                        String etag = "\"e" + (++counter) + "\"";
                        objects.put(key, new Obj(body, etag, Instant.now()));
                        x.getResponseHeaders().add("ETag", etag);
                        send(x, 200, new byte[0]);
                    }
                    case "DELETE" -> {
                        if (ifMatch != null && o != null && !ifMatch.equals(o.etag())) {
                            error(x, 412, "PreconditionFailed");
                            return;
                        }
                        objects.remove(key);
                        x.sendResponseHeaders(204, -1);
                    }
                    default -> error(x, 405, "MethodNotAllowed");
                }
            }
        }
    }

    private void list(HttpExchange x, String prefix) throws IOException {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult><IsTruncated>false</IsTruncated>");
        synchronized (objects) {
            for (Map.Entry<String, Obj> e : objects.tailMap(prefix).entrySet()) {
                if (!e.getKey().startsWith(prefix)) {
                    break;
                }
                b.append("<Contents><Key>").append(xml(e.getKey())).append("</Key><LastModified>").append(e.getValue().modified())
                        .append("</LastModified><ETag>").append(xml(e.getValue().etag())).append("</ETag><Size>").append(e.getValue().bytes().length)
                        .append("</Size></Contents>");
            }
        }
        b.append("</ListBucketResult>");
        x.getResponseHeaders().add("Content-Type", "application/xml");
        send(x, 200, b.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void error(HttpExchange x, int status, String code) throws IOException {
        byte[] b = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>" + code + "</Code><Message>" + code + "</Message></Error>")
                .getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/xml");
        if ("HEAD".equals(x.getRequestMethod())) {
            x.sendResponseHeaders(status, -1);
        } else {
            send(x, status, b);
        }
    }

    private static void send(HttpExchange x, int status, byte[] b) throws IOException {
        x.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream o = x.getResponseBody()) {
                o.write(b);
            }
        }
    }

    /** The request body; an {@code aws-chunked} upload is decoded to its payload. */
    private static byte[] readBody(HttpExchange x) throws IOException {
        byte[] raw;
        try (InputStream in = x.getRequestBody()) {
            raw = in.readAllBytes();
        }
        String sha = x.getRequestHeaders().getFirst("x-amz-content-sha256");
        if (sha == null || !sha.startsWith("STREAMING")) {
            return raw;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < raw.length) {
            int eol = indexOf(raw, i);
            String header = new String(raw, i, eol - i, StandardCharsets.US_ASCII);
            int size = Integer.parseInt(header.split(";")[0].trim(), 16);
            if (size == 0) {
                break;
            }
            out.write(raw, eol + 2, size);
            i = eol + 2 + size + 2;
        }
        return out.toByteArray();
    }

    private static int indexOf(byte[] b, int from) {
        for (int i = from; i + 1 < b.length; i++) {
            if (b[i] == '\r' && b[i + 1] == '\n') {
                return i;
            }
        }
        return b.length;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw != null) {
            for (String kv : raw.split("&")) {
                int eq = kv.indexOf('=');
                out.put(URLDecoder.decode(eq < 0 ? kv : kv.substring(0, eq), StandardCharsets.UTF_8),
                        eq < 0 ? "" : URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
