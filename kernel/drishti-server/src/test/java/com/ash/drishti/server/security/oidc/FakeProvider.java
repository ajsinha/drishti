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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** A tiny OpenID provider for tests: discovery, a JWKS with an RSA and an EC key, and token minting. */
public final class FakeProvider implements AutoCloseable {

    public final KeyPair rsa;
    public final KeyPair ec;
    public final KeyPair stranger;                         // a key the provider never published
    public final AtomicInteger jwksHits = new AtomicInteger();
    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();

    public FakeProvider() throws Exception {
        KeyPairGenerator r = KeyPairGenerator.getInstance("RSA");
        r.initialize(2048);
        rsa = r.generateKeyPair();
        stranger = r.generateKeyPair();
        KeyPairGenerator e = KeyPairGenerator.getInstance("EC");
        e.initialize(new ECGenParameterSpec("secp256r1"));
        ec = e.generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/openid-configuration", x -> reply(x, Map.of("issuer", issuer(), "jwks_uri", issuer() + "/jwks")));
        server.createContext("/jwks", x -> {
            jwksHits.incrementAndGet();
            reply(x, Map.of("keys", List.of(rsaJwk(), ecJwk())));
        });
        server.start();
    }

    public String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private Map<String, Object> rsaJwk() {
        RSAPublicKey k = (RSAPublicKey) rsa.getPublic();
        return Map.of("kty", "RSA", "kid", "rsa-1", "use", "sig", "alg", "RS256", "n", b64(k.getModulus()), "e", b64(k.getPublicExponent()));
    }

    private Map<String, Object> ecJwk() {
        ECPublicKey k = (ECPublicKey) ec.getPublic();
        return Map.of("kty", "EC", "kid", "ec-1", "use", "sig", "crv", "P-256", "x", b64(k.getW().getAffineX(), 32), "y", b64(k.getW().getAffineY(), 32));
    }

    /** Standard claims for {@code user}, valid now, for audience {@code aud} and {@code nonce}; {@code extra} overrides. */
    public Map<String, Object> claims(String user, String aud, String nonce, Map<String, Object> extra) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> c = new LinkedHashMap<>(Map.of("iss", issuer(), "sub", "sub-" + user, "aud", aud, "iat", now, "exp", now + 300,
                "nonce", nonce, "preferred_username", user, "name", "User " + user, "email", user + "@example.com"));
        c.putAll(extra);
        return c;
    }

    public String sign(String alg, String kid, PrivateKey key, Map<String, Object> claims) throws Exception {
        Map<String, Object> header = new LinkedHashMap<>(Map.of("alg", alg, "typ", "JWT"));
        if (kid != null) {
            header.put("kid", kid);
        }
        String input = enc(json.writeValueAsBytes(header)) + "." + enc(json.writeValueAsBytes(claims));
        if (key == null) {
            return input + ".";                              // alg none
        }
        Signature s = Signature.getInstance(alg.equals("ES256") ? "SHA256withECDSAinP1363Format" : "SHA256withRSA");
        s.initSign(key);
        s.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + enc(s.sign());
    }

    private void reply(com.sun.net.httpserver.HttpExchange x, Object body) throws java.io.IOException {
        byte[] b = json.writeValueAsBytes(body);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(200, b.length);
        x.getResponseBody().write(b);
        x.close();
    }

    private static String enc(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String b64(BigInteger v) {
        byte[] b = v.toByteArray();
        return enc(b[0] == 0 ? java.util.Arrays.copyOfRange(b, 1, b.length) : b);
    }

    private static String b64(BigInteger v, int len) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[len];
        int copy = Math.min(len, raw.length);
        System.arraycopy(raw, raw.length - copy, out, len - copy, copy);
        return enc(out);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
