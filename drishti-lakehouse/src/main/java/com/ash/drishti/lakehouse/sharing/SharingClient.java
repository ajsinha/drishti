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
package com.ash.drishti.lakehouse.sharing;

import com.ash.drishti.lakehouse.JsonHttp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The open Delta Sharing protocol (REST, bearer token): list shares, schemas and tables; a table's version and metadata; and a table's
 * files ({@code POST …/query}, with predicate hints), each a pre-signed URL that expires. Answers in the Parquet response format (the
 * protocol's default). Change data feed and streaming are not used. Errors in words. Thread-safe.
 */
public final class SharingClient {

    /** A table's metadata at a version. */
    public record Metadata(long version, String id, String schemaString, List<String> partitionColumns, int minReaderVersion) {}

    /**
     * One file of a query answer.
     *
     * @param expiresAtMs when its URL expires (epoch milliseconds; {@link Long#MAX_VALUE} when the server does not say)
     */
    public record SharedFile(String id, String url, long size, Map<String, String> partitionValues, String stats, long expiresAtMs) {

        @Override
        public String toString() {                               // never the pre-signed URL: its query is a credential
            return "SharedFile[" + id + ", " + JsonHttp.strip(url) + "]";
        }
    }

    /** A query answer: the version, the metadata, the files. */
    public record Answer(Metadata metadata, List<SharedFile> files) {}

    private final SharingProfile profile;
    private final JsonHttp http;

    public SharingClient(SharingProfile profile, Map<String, String> settings) {
        this.profile = profile;
        this.http = new JsonHttp(settings, profile.endpoint(), Long.parseLong(settings.getOrDefault("timeout-seconds", "60")));
    }

    public SharingProfile profile() {
        return profile;
    }

    /** The HTTP client the files are downloaded with (the same proxy). */
    public JsonHttp http() {
        return http;
    }

    private static String seg(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String table(String share, String schema, String table) {
        return "/shares/" + seg(share) + "/schemas/" + seg(schema) + "/tables/" + seg(table);
    }

    private JsonHttp.Answer call(String method, String path, Object body) throws IOException {
        if (profile.expiresAt() != null && java.time.Instant.now().isAfter(profile.expiresAt())) {
            throw new IOException("the Delta Sharing token for " + profile.endpoint() + " expired at " + profile.expiresAt()
                    + ": ask the provider for a new profile");
        }
        JsonHttp.Answer a = http.send(method, profile.endpoint() + path, body, null, Map.of("Authorization", "Bearer " + profile.token(),
                "delta-sharing-capabilities", "responseformat=parquet"));
        if (!a.ok()) {
            throw refusal(a, path);
        }
        return a;
    }

    private IOException refusal(JsonHttp.Answer a, String path) {
        JsonNode e = a.json();
        String said = " (the server says: " + e.path("errorCode").asText("") + " " + e.path("message").asText(a.text()) + ")";
        String what = path.replace("/shares/", "share ").replace("/schemas/", ", schema ").replace("/tables/", ", table ");
        return switch (a.status()) {
            case 401 -> new IOException("the Delta Sharing server " + profile.endpoint() + " refused the bearer token (HTTP 401): it is wrong or "
                    + "expired; ask the provider for a new profile" + said);
            case 403 -> new IOException("no access: the recipient may not read " + what + " (HTTP 403); the provider grants the share" + said);
            case 404 -> new IOException("not found: " + what + " is not shared with this recipient (HTTP 404)" + said);
            default -> new IOException("the Delta Sharing server " + profile.endpoint() + " answered HTTP " + a.status() + " for " + what + said);
        };
    }

    private List<String> names(String path) throws IOException {
        List<String> out = new ArrayList<>();
        String page = null;
        do {
            String p = path + "?maxResults=500" + (page == null ? "" : "&pageToken=" + seg(page));
            JsonNode n = call("GET", p, null).json();
            n.path("items").forEach(i -> out.add(i.path("name").asText()));
            page = n.path("nextPageToken").asText("");
            page = page.isBlank() ? null : page;
        } while (page != null);
        out.sort(String::compareTo);
        return out;
    }

    public List<String> shares() throws IOException {
        return names("/shares");
    }

    public List<String> schemas(String share) throws IOException {
        return names("/shares/" + seg(share) + "/schemas");
    }

    public List<String> tables(String share, String schema) throws IOException {
        return names("/shares/" + seg(share) + "/schemas/" + seg(schema) + "/tables");
    }

    /** The table's current version. */
    public long version(String share, String schema, String table) throws IOException {
        JsonHttp.Answer a = call("GET", table(share, schema, table) + "/version", null);
        return a.headers().firstValue("delta-table-version").map(Long::parseLong)
                .orElseThrow(() -> new IOException("the Delta Sharing server gave no delta-table-version for " + share + "." + schema + "." + table));
    }

    /** The table's metadata (and its version). */
    public Metadata metadata(String share, String schema, String table) throws IOException {
        JsonHttp.Answer a = call("GET", table(share, schema, table) + "/metadata", null);
        return lines(a, share + "." + schema + "." + table).metadata();
    }

    /**
     * The table's files at {@code version} (null: the current one) that may hold rows matching {@code hints} (the protocol's
     * {@code jsonPredicateHints}: the server may skip files by them, never must), and its metadata.
     */
    public Answer query(String share, String schema, String table, Long version, JsonNode hints) throws IOException {
        ObjectNode body = JsonHttp.JSON.createObjectNode();
        if (version != null) {
            body.put("version", version);
        }
        if (hints != null) {
            body.put("jsonPredicateHints", hints.toString());
        }
        JsonHttp.Answer a = call("POST", table(share, schema, table) + "/query", body);
        return lines(a, share + "." + schema + "." + table);
    }

    /** Reads an answer of newline-delimited JSON actions ({@code protocol}, {@code metaData}, {@code file}). */
    private static Answer lines(JsonHttp.Answer a, String name) throws IOException {
        long version = a.headers().firstValue("delta-table-version").map(Long::parseLong).orElse(-1L);
        int reader = 1;
        JsonNode md = null;
        List<SharedFile> files = new ArrayList<>();
        for (String line : new String(a.body(), StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode n = JsonHttp.JSON.readTree(line);
            if (n.has("protocol")) {
                reader = n.get("protocol").path("minReaderVersion").asInt(1);
            } else if (n.has("metaData")) {
                md = n.get("metaData");
            } else if (n.has("file")) {
                JsonNode f = n.get("file");
                Map<String, String> pv = new java.util.LinkedHashMap<>();
                f.path("partitionValues").fields().forEachRemaining(e -> pv.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText()));
                files.add(new SharedFile(f.path("id").asText(f.path("url").asText()), f.path("url").asText(), f.path("size").asLong(), pv,
                        f.hasNonNull("stats") ? f.get("stats").asText() : null, f.path("expirationTimestamp").asLong(Long.MAX_VALUE)));
            } else if (n.has("add") || n.has("deletionVector")) {
                throw new IOException("the Delta Sharing server answered " + name + " in the delta response format: only the parquet format is read");
            }
        }
        if (md == null) {
            throw new IOException("the Delta Sharing server gave no metadata for " + name);
        }
        if (reader > 1) {
            throw new IOException("the shared table " + name + " needs a Delta reader of version " + reader + " (deletion vectors or column "
                    + "mapping): the provider can share it without them, or with history and the delta response format, which is under construction");
        }
        if (!"parquet".equalsIgnoreCase(md.path("format").path("provider").asText("parquet"))) {
            throw new IOException("the shared table " + name + " is in " + md.path("format").path("provider").asText() + " files: only Parquet is read");
        }
        List<String> parts = new ArrayList<>();
        md.path("partitionColumns").forEach(p -> parts.add(p.asText()));
        return new Answer(new Metadata(version, md.path("id").asText(name), md.path("schemaString").asText(), parts, reader), files);
    }
}
