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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a Delta Sharing server is and the bearer token to call it with: a profile file ({@code profile}, the JSON a data provider hands
 * out: {@code shareCredentialsVersion} 1, {@code endpoint}, {@code bearerToken}, optionally {@code expirationTime}), or the connector's
 * own {@code endpoint} and {@code bearer-token} settings. The token is a secret: in the profile file it must be an environment reference
 * ({@code "bearerToken": "${PROVIDER_SHARING_TOKEN}"}), never the token itself; in the connector file {@code ${ENV}} or {@code file:}.
 * Profiles of version 2 (OAuth client credentials) are not read yet (UNDER_CONSTRUCTION.md).
 *
 * @param endpoint the server's address, without a trailing slash ({@code https://sharing.example.com/delta-sharing})
 * @param token the bearer token (never shown)
 * @param expiresAt when the provider's token expires, or null when the profile does not say
 */
public record SharingProfile(String endpoint, String token, Instant expiresAt) {

    private static final Pattern ENV = Pattern.compile("^\\$\\{([A-Za-z_][A-Za-z0-9_]*)}$");

    @Override
    public String toString() {                                   // never the token
        return "SharingProfile[" + endpoint + "]";
    }

    /** From the connector settings: {@code profile} (a file), or {@code endpoint} with {@code bearer-token}. */
    public static SharingProfile from(Map<String, String> settings) throws IOException {
        return from(settings, System::getenv);
    }

    static SharingProfile from(Map<String, String> settings, Function<String, String> env) throws IOException {
        String file = settings.getOrDefault("profile", "").trim();
        if (!file.isEmpty()) {
            return read(Path.of(file), env);
        }
        String endpoint = settings.getOrDefault("endpoint", "").trim();
        String token = settings.getOrDefault("bearer-token", "").trim();
        if (endpoint.isEmpty() || token.isEmpty()) {
            throw new IllegalArgumentException("delta-sharing needs settings.profile (a profile file), or settings.endpoint with "
                    + "settings.bearer-token (as ${ENV} or file:)");
        }
        return new SharingProfile(endpoint.replaceAll("/+$", ""), token, null);
    }

    /** A profile file, its token resolved from the environment. */
    static SharingProfile read(Path file, Function<String, String> env) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("the Delta Sharing profile " + file + " is not there");
        }
        JsonNode p = JsonHttp.JSON.readTree(Files.readAllBytes(file));
        int version = p.path("shareCredentialsVersion").asInt(1);
        if (version != 1 || p.has("type") && !"bearer_token".equals(p.path("type").asText())) {
            throw new IllegalArgumentException("the Delta Sharing profile " + file + " is of version " + version + " (" + p.path("type").asText("")
                    + "): only bearer-token profiles (version 1) are read; OAuth profiles are under construction");
        }
        String endpoint = p.path("endpoint").asText("").trim();
        if (endpoint.isEmpty()) {
            throw new IllegalArgumentException("the Delta Sharing profile " + file + " has no endpoint");
        }
        String raw = p.path("bearerToken").asText("").trim();
        Matcher m = ENV.matcher(raw);
        if (!m.matches()) {
            throw new IllegalArgumentException("the Delta Sharing profile " + file + " holds the bearer token itself: a secret is never written "
                    + "in a file the server reads; replace it with \"${AN_ENVIRONMENT_VARIABLE}\" and set that variable to the token");
        }
        String token = env.apply(m.group(1));
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("the Delta Sharing profile " + file + ": the environment variable " + m.group(1) + " is not set");
        }
        Instant expires = p.hasNonNull("expirationTime") ? Instant.parse(p.get("expirationTime").asText()) : null;
        return new SharingProfile(endpoint.replaceAll("/+$", ""), token.trim(), expires);
    }
}
