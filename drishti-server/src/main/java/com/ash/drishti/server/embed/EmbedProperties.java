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
package com.ash.drishti.server.embed;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.embed.*}: embedded views for other web applications (Drishti Elements, docs/architecture/CLIENT_RENDERER.md). Off by
 * default: with {@code enabled} false there is no token endpoint and embed tokens are refused.
 *
 * @param enabled switch embedding on (needs {@code drishti.security.enabled})
 * @param issuer the {@code iss} of embed tokens, normally this server's public URL
 * @param audiences the consoles that may accept embed tokens: a token request names one of them (its public URL); the first is the default
 * @param signingKey PEM file with the ES256 key pair (a PKCS#8 {@code PRIVATE KEY} and an {@code PUBLIC KEY} block); empty: generated at
 *     start, so tokens do not survive a restart and only one server can use them
 * @param tokenMaxSeconds longest life of an embed token, and the cap on an application's own lifetime
 * @param mask {@code always}: embed calls mask the fields named in {@code drishti.security.redact} whatever the user's roles
 * @param scopes embed scope to the {@code METHOD path} patterns it opens; a token may call only what its scopes open, and only GET or the
 *     read-only POSTs listed here
 * @param limits call rates
 * @param allowWildcardOrigins let an application register {@code https://*.suffix} origins (review apps)
 * @param assertionMaxAge longest life of a client or subject assertion a host signs (its {@code exp} minus its {@code iat})
 * @param search the limits of {@code <drishti-search>} (scope {@code embed:search})
 * @param apps host applications declared in configuration (GitOps): read-only in Admin → Embedding, marked "from config"
 */
@ConfigurationProperties("drishti.embed")
public record EmbedProperties(Boolean enabled, String issuer, List<String> audiences, String signingKey, Integer tokenMaxSeconds, String mask,
        Map<String, List<String>> scopes, Limits limits, Boolean allowWildcardOrigins, Duration assertionMaxAge, List<ConfiguredApp> apps, Search search) {

    /** The scope that opens views, the live stream and a panel's rows. */
    public static final String VIEW = "embed:view";
    /** The scope that opens the About body. */
    public static final String ABOUT = "embed:about";
    /** The scope that opens a published BI report: its definition, its visuals' queries and its live stream (the app's {@code kinds} then name {@code report:<id>} or {@code report:*}). */
    public static final String REPORT = "embed:report";
    /** The scope that opens the search across Drishti ({@code GET /api/v1/embed/search}): read-only, masked, rate-limited. */
    public static final String SEARCH = "embed:search";
    /** The path the search scope opens. */
    public static final String SEARCH_PATH = "/api/v1/embed/search";

    /**
     * @param tokensPerMinute exchanges per application per minute
     * @param viewsPerMinute calls per application per minute (the default of a new registration)
     * @param viewsPerUserPerMinute calls per user per minute (the default of a new registration)
     * @param searchesPerUserPerMinute searches per user per minute (and application) through {@code <drishti-search>}, on top of the call rates
     */
    public record Limits(Integer tokensPerMinute, Integer viewsPerMinute, Integer viewsPerUserPerMinute, Integer searchesPerUserPerMinute) {

        public Limits(Integer tokensPerMinute, Integer viewsPerMinute, Integer viewsPerUserPerMinute) {
            this(tokensPerMinute, viewsPerMinute, viewsPerUserPerMinute, null);
        }

        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Limits {
            tokensPerMinute = tokensPerMinute == null ? 120 : Math.max(1, tokensPerMinute);
            viewsPerMinute = viewsPerMinute == null ? 600 : Math.max(1, viewsPerMinute);
            viewsPerUserPerMinute = viewsPerUserPerMinute == null ? 60 : Math.max(1, viewsPerUserPerMinute);
            searchesPerUserPerMinute = searchesPerUserPerMinute == null ? 30 : Math.max(1, searchesPerUserPerMinute);
        }
    }

    /**
     * @param maxResults most results one search returns
     * @param minChars shortest query that is searched (shorter: an empty answer)
     */
    public record Search(Integer maxResults, Integer minChars) {
        public Search {
            maxResults = maxResults == null ? 10 : Math.max(1, Math.min(50, maxResults));
            minChars = minChars == null ? 2 : Math.max(1, minChars);
        }
    }

    /**
     * One host application declared in configuration. {@code secretSha256} is the hex SHA-256 of the client secret (never the secret);
     * {@code jwks} the JWK set document when the application authenticates with a key. Unset numbers take the {@code limits} defaults.
     */
    public record ConfiguredApp(String id, String name, String contact, List<String> origins, List<String> kinds, List<String> scopes,
            List<String> subjectTypes, List<String> subjectAudiences, String jwks, String secretSha256, Integer tokenSeconds,
            Integer callsPerMinute, Integer userCallsPerMinute, Boolean enabled) {

        /** As the registry's own draft, which the store validates exactly like an administrator's registration. */
        public com.ash.drishti.identity.EmbedAppStore.Configured toConfigured() {
            return new com.ash.drishti.identity.EmbedAppStore.Configured(new com.ash.drishti.identity.EmbedAppStore.Draft(id, name, contact,
                    origins, kinds, scopes == null || scopes.isEmpty() ? List.of(VIEW) : scopes, subjectTypes, subjectAudiences, jwks,
                    secretSha256 != null && !secretSha256.isBlank(), tokenSeconds, callsPerMinute, userCallsPerMinute, enabled), secretSha256);
        }
    }

    public EmbedProperties {
        enabled = enabled != null && enabled;
        issuer = issuer == null ? "" : issuer.trim().replaceAll("/+$", "");
        audiences = audiences == null ? List.of() : audiences.stream().filter(a -> a != null && !a.isBlank()).map(a -> a.trim().replaceAll("/+$", "")).toList();
        signingKey = signingKey == null ? "" : signingKey.trim();
        tokenMaxSeconds = tokenMaxSeconds == null ? 900 : Math.max(30, tokenMaxSeconds);
        mask = mask == null || mask.isBlank() ? "always" : mask;
        scopes = scopes == null || scopes.isEmpty() ? Map.of(
                VIEW, List.of("GET /api/v1/views/*/*", "GET /api/v1/views/*/*/stream", "GET /api/v1/views/*/*/panels/*/records",
                        "GET /api/v1/embed/check", "POST /api/v1/command"),
                ABOUT, List.of("GET /api/v1/views/*/*/explain"),
                REPORT, List.of("GET /api/v1/bi/reports/*", "POST /api/v1/bi/reports/*/query", "GET /api/v1/bi/reports/*/live"),
                SEARCH, List.of("GET " + SEARCH_PATH)) : Map.copyOf(scopes);
        limits = limits == null ? new Limits(null, null, null, null) : limits;
        search = search == null ? new Search(null, null) : search;
        allowWildcardOrigins = allowWildcardOrigins != null && allowWildcardOrigins;
        assertionMaxAge = assertionMaxAge == null ? Duration.ofSeconds(60) : assertionMaxAge;
        apps = apps == null ? List.of() : List.copyOf(apps);
    }

    /** The audience a token is made for when the request names none. */
    public String defaultAudience() {
        return audiences.isEmpty() ? issuer : audiences.get(0);
    }

    /** Whether a token for this audience may be made. */
    public boolean acceptsAudience(String audience) {
        return audiences.isEmpty() ? audience.equals(issuer) : audiences.contains(audience);
    }
}
