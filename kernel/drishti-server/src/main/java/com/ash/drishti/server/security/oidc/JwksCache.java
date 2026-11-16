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
package com.ash.drishti.server.security.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The provider's signing keys (JWKS), fetched on first use (from discovery unless configured) and refreshed when a
 * token names a key id the cache does not know, at most once a minute so a flood of bad tokens cannot hammer the
 * provider. Keys are published as an immutable map: readers never lock. Thread-safe.
 */
public final class JwksCache {

    /** A key and the algorithm family it may verify ({@code RSA} or {@code EC}). */
    record Key(String kid, String kty, PublicKey key) {}

    private static final Duration MIN_REFRESH = Duration.ofSeconds(60);
    private final OidcProperties props;
    private final HttpClient http;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<Map<String, Key>> keys = new AtomicReference<>(Map.of());
    private final ReentrantLock refresh = new ReentrantLock();
    private volatile Instant lastRefresh = Instant.EPOCH;
    private volatile String jwksUri;

    public JwksCache(OidcProperties props, HttpClient http, Clock clock) {
        this.props = props;
        this.http = http;
        this.clock = clock;
        this.jwksUri = props.jwksUri();
    }

    /** The key for {@code kid} (any key of the family when the token names none); refreshes once when unknown. */
    Optional<Key> find(String kid, String kty) {
        Optional<Key> k = pick(keys.get(), kid, kty);
        if (k.isPresent()) {
            return k;
        }
        refreshIfDue();
        return pick(keys.get(), kid, kty);
    }

    private static Optional<Key> pick(Map<String, Key> all, String kid, String kty) {
        if (kid != null) {
            return Optional.ofNullable(all.get(kid)).filter(k -> k.kty().equals(kty));
        }
        var matching = all.values().stream().filter(k -> k.kty().equals(kty)).toList();
        return matching.size() == 1 ? Optional.of(matching.get(0)) : Optional.empty();   // ambiguous without a kid: refuse
    }

    @SuppressWarnings("LockNotBeforeTry")   // lock-then-unlock only waits for the refresh another caller is doing
    private void refreshIfDue() {
        if (!refresh.tryLock()) {
            refresh.lock();                                    // someone else is refreshing: wait for them, then use theirs
            refresh.unlock();
            return;
        }
        try {
            if (Duration.between(lastRefresh, clock.instant()).compareTo(MIN_REFRESH) < 0) {
                return;
            }
            lastRefresh = clock.instant();
            keys.set(load());
        } catch (Exception e) {
            // keep the keys we have; the token is then refused as signed by an unknown key
        } finally {
            refresh.unlock();
        }
    }

    private Map<String, Key> load() throws Exception {
        if (jwksUri == null || jwksUri.isBlank()) {
            JsonNode discovery = get(props.issuer() + "/.well-known/openid-configuration");
            if (!props.issuer().equals(discovery.path("issuer").asText().replaceAll("/+$", ""))) {
                throw new IllegalStateException("discovery document names another issuer");
            }
            jwksUri = discovery.path("jwks_uri").asText();
        }
        Map<String, Key> out = new HashMap<>();
        int unnamed = 0;
        for (JsonNode k : get(jwksUri).path("keys")) {
            if (k.hasNonNull("use") && !"sig".equals(k.get("use").asText())) {
                continue;
            }
            PublicKey key = switch (k.path("kty").asText()) {
                case "RSA" -> KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(big(k, "n"), big(k, "e")));
                case "EC" -> ec(k);
                default -> null;
            };
            if (key != null) {
                String kid = k.hasNonNull("kid") ? k.get("kid").asText() : "#" + unnamed++;
                out.put(kid, new Key(kid, k.path("kty").asText(), key));
            }
        }
        return Map.copyOf(out);
    }

    private static PublicKey ec(JsonNode k) throws Exception {
        String curve = switch (k.path("crv").asText()) {
            case "P-256" -> "secp256r1";
            case "P-384" -> "secp384r1";
            case "P-521" -> "secp521r1";
            default -> null;
        };
        if (curve == null) {
            return null;
        }
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec(curve));
        ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(big(k, "x"), big(k, "y")), spec));
    }

    private static BigInteger big(JsonNode k, String field) {
        return new BigInteger(1, Base64.getUrlDecoder().decode(k.path(field).asText()));
    }

    private JsonNode get(String url) throws Exception {
        if (!url.startsWith("https://") && !url.startsWith("http://localhost") && !url.startsWith("http://127.0.0.1")) {
            throw new IllegalStateException("provider URLs must use https: " + url);
        }
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IllegalStateException(url + " answered " + r.statusCode());
        }
        return json.readTree(r.body());
    }
}
