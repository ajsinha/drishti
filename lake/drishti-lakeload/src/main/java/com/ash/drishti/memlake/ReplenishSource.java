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
package com.ash.drishti.memlake;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.engine.source.SourceRegistry;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * What a replenish job reads (HOW_IT_FITS.md section D4): a connector's documents of a kind (any connector that can list a kind: the
 * lakes, Kafka's topic state, JDBC, files, the vendor feeds), a JSON Lines file, or a URL answering JSON Lines or a JSON array. Everything
 * is read into memory and nothing is written anywhere: the source's own bytes stay where they were.
 */
public final class ReplenishSource {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private final SourceRegistry registry;
    private volatile java.util.function.Function<String, List<String>> idsOf = ref -> List.of();

    public ReplenishSource(SourceRegistry registry) {
        this.registry = registry;
    }

    /** How {@code ids-from} ({@code <table>.<FIELD>}) is read: the lake answers it from its own memory tables. */
    void idsOf(java.util.function.Function<String, List<String>> f) {
        this.idsOf = f;
    }

    /** The documents of {@code source}, each a JSON object. */
    List<ObjectNode> read(MemoryLakeSettings.Source source) throws Exception {
        if (source == null) {
            throw new IllegalArgumentException("the table names no source");
        }
        if (source.connector() != null && !source.connector().isBlank()) {
            if (!source.ids().isEmpty() || (source.idsFrom() != null && !source.idsFrom().isBlank())) {
                List<String> ids = new ArrayList<>(source.ids());
                if (source.idsFrom() != null && !source.idsFrom().isBlank()) {
                    ids.addAll(idsOf.apply(source.idsFrom().trim()));
                }
                return byIds(source.connector(), source.kind(), ids.stream().distinct().toList());
            }
            return fromConnector(source.connector(), source.kind());
        }
        if (source.file() != null && !source.file().isBlank()) {
            Path p = Path.of(source.file());
            try (InputStream in = Files.newInputStream(p)) {
                return lines(source.file().endsWith(".gz") ? new GZIPInputStream(in, 1 << 16) : in);
            }
        }
        if (source.url() != null && !source.url().isBlank()) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(source.url())).timeout(Duration.ofMinutes(2)).GET();
            source.headers().forEach(b::header);
            HttpResponse<InputStream> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                if (r.statusCode() / 100 != 2) {
                    throw new IOException("the feed answered HTTP " + r.statusCode());
                }
                return lines(in);
            }
        }
        throw new IllegalArgumentException("the source names no connector, file or url");
    }

    /** A marker of the source's state, so a change can start a run (a file's time and size; a connector's last update), or null. */
    String mark(MemoryLakeSettings.Source source) {
        try {
            if (source.file() != null && !source.file().isBlank()) {
                Path p = Path.of(source.file());
                return Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() + ":" + Files.size(p) : null;
            }
            if (source.connector() != null && !source.connector().isBlank()) {
                SourcePlugin plugin = registry.connector(source.connector()).orElse(null);
                if (plugin == null) {
                    return null;
                }
                java.time.Instant at = plugin.lastUpdate();
                String v = source.kind() == null ? "" : plugin.dataVersion(source.kind()).orElse("");
                return (at == null ? "" : at.toString()) + "|" + v;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;                                              // a URL has no cheap marker: its schedule refreshes it
    }

    private List<ObjectNode> fromConnector(String name, String kind) throws Exception {
        SourcePlugin plugin = registry.connector(name, Duration.ofSeconds(30))
                .orElseThrow(() -> new IllegalStateException("connector " + name + " is not running"));
        if (plugin.memoryOnly()) {
            throw new IllegalStateException("connector " + name + " is itself held in memory; a memory table is filled from its source, not from another memory table");
        }
        List<ObjectNode> out = new ArrayList<>();
        boolean listed = plugin.documents(kind, AsOf.LATEST, d -> {
            if (!d.deleted() && d.data() != null) {
                JsonNode n = JSON.valueToTree(d.data().unwrap());
                if (n instanceof ObjectNode o) {
                    out.add(o);
                }
            }
        });
        if (!listed) {
            throw new IllegalStateException("connector " + name + " cannot list its documents of " + kind
                    + "; fill the table from a file or a URL of the feed instead");
        }
        return out;
    }

    /** Each id fetched from the connector (one that computes a document when asked, such as the news connector); a miss is skipped. */
    private List<ObjectNode> byIds(String name, String kind, List<String> ids) throws Exception {
        SourcePlugin plugin = registry.connector(name, Duration.ofSeconds(30))
                .orElseThrow(() -> new IllegalStateException("connector " + name + " is not running"));
        List<ObjectNode> out = new ArrayList<>();
        int failed = 0;
        for (String id : ids) {
            try {
                var doc = plugin.fetch(new com.ash.drishti.api.EntityRef(kind, id));
                if (doc.isPresent() && !doc.get().deleted() && doc.get().data() != null && JSON.valueToTree(doc.get().data().unwrap()) instanceof ObjectNode o) {
                    out.add(o);
                }
            } catch (Exception e) {
                failed++;                                        // one entity's feed failing does not stop the others
            }
        }
        if (!ids.isEmpty() && out.isEmpty() && failed > 0) {
            throw new IllegalStateException("connector " + name + " answered none of " + ids.size() + " ids (" + failed + " failed)");
        }
        return out;
    }

    /** JSON Lines, or one JSON array of objects. */
    static List<ObjectNode> lines(InputStream in) throws IOException {
        List<ObjectNode> out = new ArrayList<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
        r.mark(1 << 16);
        int first;
        do {
            first = r.read();
        } while (first >= 0 && Character.isWhitespace(first));
        r.reset();
        if (first == '[') {
            JsonNode all = JSON.readTree(r);
            all.forEach(n -> {
                if (n instanceof ObjectNode o) {
                    out.add(o);
                }
            });
            return out;
        }
        String line;
        while ((line = r.readLine()) != null) {
            if (!line.isBlank()) {
                JsonNode n = JSON.readTree(line);
                if (n instanceof ObjectNode o) {
                    out.add(o);
                }
            }
        }
        return out;
    }
}
