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
package com.ash.drishti.lakehouse.unity;

import com.ash.drishti.lakehouse.JsonHttp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Unity Catalog's open REST API ({@code <host>/api/2.1/unity-catalog}), as Databricks and the open-source Unity Catalog server answer it:
 * a table by its three-part name ({@code GET /tables/{catalog.schema.table}}: its id, format, type and storage location), the tables of
 * a schema, and the table's temporary credentials ({@code POST /temporary-table-credentials}, operation {@code READ}: credential vending).
 *
 * <p>Sign-in: a fixed token ({@code unity.token}, a personal access token or a service principal's token) or OAuth machine-to-machine
 * ({@code unity.client-id} and {@code unity.client-secret}, exchanged at {@code <host>/oidc/v1/token} for an access token with scope
 * {@code all-apis}, renewed a minute before it expires). The secrets come from the connector settings, given there as {@code ${ENV}} or
 * {@code file:} references; they are never logged or put in a message.
 *
 * <p>Errors are said in words ({@link UnityException}): the table not found, no grant on it, credential vending off, the sign-in refused.
 * Unity Catalog's grants decide what may be read; Drishti adds no permissions of its own here (UNDER_CONSTRUCTION.md: credential handling
 * and grants are reviewed in 2.9.11.i's security discussion). Thread-safe.
 */
public final class UnityClient {

    /** A table as Unity Catalog describes it. */
    public record TableInfo(String fullName, String tableId, String format, String type, String storageLocation) {}

    /**
     * A table's temporary credentials.
     *
     * @param values by kind: AWS {@code access_key_id}, {@code secret_access_key}, {@code session_token}; Azure {@code sas_token}; Google
     *     Cloud {@code oauth_token}
     * @param kind {@code aws}, {@code azure}, {@code gcp}, or {@code none} (the server vended nothing: a local table of the open-source
     *     server)
     * @param expiresAtMs when they expire (epoch milliseconds; {@link Long#MAX_VALUE} when not said)
     */
    public record Vended(String kind, Map<String, String> values, long expiresAtMs) {

        @Override
        public String toString() {                               // never the secrets
            return "Vended[" + kind + ", expires " + expiresAtMs + "]";
        }
    }

    private final String host;
    private final String api;
    private final String token;
    private final String clientId;
    private final String clientSecret;
    private final String tokenUri;
    private final JsonHttp http;
    private final ReentrantLock signIn = new ReentrantLock();
    private volatile String oauthToken;
    private volatile long oauthExpiresAt;

    /**
     * @param host the workspace or server address ({@code https://adb-123.4.azuredatabricks.net}, {@code http://localhost:8080})
     * @param token a fixed bearer token, or null with OAuth
     * @param clientId the OAuth client id (with {@code clientSecret}), or null
     * @param tokenUri where OAuth tokens come from (null: {@code <host>/oidc/v1/token})
     */
    public UnityClient(String host, String token, String clientId, String clientSecret, String tokenUri, Map<String, String> settings) {
        this.host = host.replaceAll("/+$", "");
        this.api = this.host + "/api/2.1/unity-catalog";
        this.token = blank(token) ? null : token.trim();
        this.clientId = blank(clientId) ? null : clientId.trim();
        this.clientSecret = blank(clientSecret) ? null : clientSecret.trim();
        this.tokenUri = blank(tokenUri) ? this.host + "/oidc/v1/token" : tokenUri.trim();
        this.http = new JsonHttp(settings, this.host, Long.parseLong(settings.getOrDefault("unity.timeout-seconds", "30")));
        if (this.token == null && (this.clientId == null || this.clientSecret == null)) {
            throw new IllegalArgumentException("Unity Catalog at " + this.host + " needs unity.token, or unity.client-id with unity.client-secret "
                    + "(OAuth machine-to-machine); give the secrets as ${ENV} or file: references");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    public String host() {
        return host;
    }

    /** The bearer token to call with: the fixed one, or an OAuth access token (fetched, then renewed a minute before expiry). */
    private String bearer() throws IOException {
        if (token != null) {
            return token;
        }
        if (oauthToken != null && System.currentTimeMillis() < oauthExpiresAt - 60_000) {
            return oauthToken;
        }
        signIn.lock();
        try {
            if (oauthToken != null && System.currentTimeMillis() < oauthExpiresAt - 60_000) {
                return oauthToken;
            }
            String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
            JsonHttp.Answer a = http.send("POST", tokenUri, "grant_type=client_credentials&scope=all-apis", "application/x-www-form-urlencoded",
                    Map.of("Authorization", "Basic " + basic));
            if (!a.ok() || !a.json().hasNonNull("access_token")) {
                throw new UnityException(UnityException.Reason.SIGN_IN, "Unity Catalog at " + host + " refused the OAuth sign-in of client "
                        + clientId + " (HTTP " + a.status() + " from " + tokenUri + "): check unity.client-id and unity.client-secret");
            }
            oauthToken = a.json().get("access_token").asText();
            oauthExpiresAt = System.currentTimeMillis() + a.json().path("expires_in").asLong(3600) * 1000;
            return oauthToken;
        } finally {
            signIn.unlock();
        }
    }

    private JsonHttp.Answer call(String method, String path, Object body) throws IOException {
        return http.send(method, api + path, body, null, Map.of("Authorization", "Bearer " + bearer()));
    }

    /** The table {@code fullName} ({@code catalog.schema.table}). */
    public TableInfo table(String fullName) throws IOException {
        JsonHttp.Answer a = call("GET", "/tables/" + URLEncoder.encode(fullName, StandardCharsets.UTF_8), null);
        if (!a.ok()) {
            throw refusal(a, fullName, false);
        }
        JsonNode t = a.json();
        return new TableInfo(t.path("full_name").asText(fullName), t.path("table_id").asText(""), t.path("data_source_format").asText(""),
                t.path("table_type").asText(""), t.path("storage_location").asText(""));
    }

    /** The table names (no catalog or schema) of {@code catalog.schema}, sorted. */
    public List<String> tables(String catalog, String schema) throws IOException {
        List<String> out = new ArrayList<>();
        String page = null;
        do {
            String q = "/tables?catalog_name=" + URLEncoder.encode(catalog, StandardCharsets.UTF_8) + "&schema_name="
                    + URLEncoder.encode(schema, StandardCharsets.UTF_8) + "&max_results=1000"
                    + (page == null ? "" : "&page_token=" + URLEncoder.encode(page, StandardCharsets.UTF_8));
            JsonHttp.Answer a = call("GET", q, null);
            if (!a.ok()) {
                throw refusal(a, catalog + "." + schema, false);
            }
            for (JsonNode t : a.json().path("tables")) {
                out.add(t.path("name").asText());
            }
            page = a.json().path("next_page_token").asText("");
            page = page.isBlank() ? null : page;
        } while (page != null);
        out.sort(String::compareTo);
        return out;
    }

    /** The table's temporary credentials to read it (credential vending). */
    public Vended credentials(TableInfo table) throws IOException {
        ObjectNode body = JsonHttp.JSON.createObjectNode().put("table_id", table.tableId()).put("operation", "READ");
        JsonHttp.Answer a = call("POST", "/temporary-table-credentials", body);
        if (!a.ok()) {
            throw refusal(a, table.fullName(), true);
        }
        JsonNode c = a.json();
        long expires = c.path("expiration_time").asLong(Long.MAX_VALUE);
        Map<String, String> v = new LinkedHashMap<>();
        if (c.has("aws_temp_credentials")) {
            JsonNode aws = c.get("aws_temp_credentials");
            v.put("access_key_id", aws.path("access_key_id").asText());
            v.put("secret_access_key", aws.path("secret_access_key").asText());
            v.put("session_token", aws.path("session_token").asText());
            return new Vended("aws", v, expires);
        }
        if (c.has("azure_user_delegation_sas")) {
            v.put("sas_token", c.get("azure_user_delegation_sas").path("sas_token").asText());
            return new Vended("azure", v, expires);
        }
        if (c.has("gcp_oauth_token")) {
            v.put("oauth_token", c.get("gcp_oauth_token").path("oauth_token").asText());
            return new Vended("gcp", v, expires);
        }
        return new Vended("none", v, expires);
    }

    /** An answer other than 2xx, in words. */
    UnityException refusal(JsonHttp.Answer a, String what, boolean vending) {
        JsonNode e = a.json();
        String code = e.path("error_code").asText("");
        String message = e.path("message").asText(a.text());
        String said = " (Unity Catalog says: " + (code.isEmpty() ? "" : code + ": ") + message + ")";
        String lower = (code + " " + message).toLowerCase(Locale.ROOT);
        if (a.status() == 401) {
            return new UnityException(UnityException.Reason.SIGN_IN, "Unity Catalog at " + host + " refused the sign-in (HTTP 401): check "
                    + (token != null ? "unity.token" : "unity.client-id and unity.client-secret") + said);
        }
        if (a.status() == 404 || code.endsWith("DOES_NOT_EXIST") || code.equals("NOT_FOUND")) {
            return new UnityException(UnityException.Reason.NOT_FOUND, "table not found: Unity Catalog at " + host + " has no " + what
                    + "; check the catalog, schema and table names (unity.catalog, unity.schema)" + said);
        }
        if (vending && (lower.contains("vending") || lower.contains("external data access") || lower.contains("external use schema")
                || lower.contains("external_use_schema") || code.equals("FEATURE_DISABLED") || a.status() == 501)) {
            return new UnityException(UnityException.Reason.VENDING_OFF, "credential vending is off for " + what + ": the metastore must allow "
                    + "external data access, and the principal needs EXTERNAL USE SCHEMA on the table's schema" + said);
        }
        if (a.status() == 403 || code.equals("PERMISSION_DENIED")) {
            return new UnityException(UnityException.Reason.NO_GRANT, "no grant: the principal the connector signs in as may not read " + what
                    + "; an owner grants USE CATALOG, USE SCHEMA and SELECT" + (vending ? " (and EXTERNAL USE SCHEMA, for credential vending)" : "")
                    + said);
        }
        return new UnityException(UnityException.Reason.OTHER, "Unity Catalog at " + host + " answered HTTP " + a.status() + " for " + what + said);
    }
}
