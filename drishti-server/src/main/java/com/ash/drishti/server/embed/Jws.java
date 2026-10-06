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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Compact JWS reading and checking with the JDK alone, for what a host application presents (a client assertion, a signed
 * subject token): RSA PKCS#1 and ECDSA only, never {@code none} or a shared-secret HMAC. Also reads the JWK sets hosts register.
 */
final class Jws {

    /** The algorithms a host's key may sign with. */
    static final Set<String> ALGORITHMS = Set.of("RS256", "RS384", "RS512", "ES256", "ES384");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A token split into its parts; nothing is verified yet. */
    record Parsed(JsonNode header, JsonNode claims, String signingInput, byte[] signature) {}

    /** A public key of a JWK set, with its id. */
    record Key(String kid, String kty, PublicKey key) {}

    private Jws() {}

    /** Splits and decodes a compact JWS; {@link IllegalArgumentException} when it is not one. */
    static Parsed parse(String token) {
        String[] p = token == null ? new String[0] : token.split("\\.", -1);
        if (p.length != 3) {
            throw new IllegalArgumentException("not a signed token");
        }
        try {
            Base64.Decoder d = Base64.getUrlDecoder();
            return new Parsed(JSON.readTree(d.decode(p[0])), JSON.readTree(d.decode(p[1])), p[0] + "." + p[1], d.decode(p[2]));
        } catch (Exception e) {
            throw new IllegalArgumentException("unreadable token");
        }
    }

    /** Whether the signature holds for one of the set's keys that fits the algorithm (and {@code kid}, when the header names one). */
    static boolean verify(Parsed t, List<Key> keys) {
        String alg = t.header().path("alg").asText();
        if (!ALGORITHMS.contains(alg)) {
            return false;
        }
        String kty = alg.startsWith("ES") ? "EC" : "RSA";
        String kid = t.header().hasNonNull("kid") ? t.header().get("kid").asText() : null;
        for (Key k : keys) {
            if (!k.kty().equals(kty) || kid != null && k.kid() != null && !kid.equals(k.kid())) {
                continue;
            }
            try {
                Signature s = Signature.getInstance(switch (alg) {
                    case "RS256" -> "SHA256withRSA";
                    case "RS384" -> "SHA384withRSA";
                    case "RS512" -> "SHA512withRSA";
                    case "ES256" -> "SHA256withECDSAinP1363Format";
                    default -> "SHA384withECDSAinP1363Format";
                });
                s.initVerify(k.key());
                s.update(t.signingInput().getBytes(StandardCharsets.US_ASCII));
                if (s.verify(t.signature())) {
                    return true;
                }
            } catch (java.security.GeneralSecurityException ignored) {
                // a key that does not fit: try the next
            }
        }
        return false;
    }

    /** The RSA and P-256/P-384 keys of a JWK set document; keys of another kind are skipped. {@link IllegalArgumentException} when it is not a set. */
    static List<Key> keys(String jwks) {
        JsonNode root;
        try {
            root = JSON.readTree(jwks);
        } catch (Exception e) {
            throw new IllegalArgumentException("the keys are not JSON");
        }
        JsonNode arr = root.path("keys");
        if (!arr.isArray() || arr.isEmpty()) {
            throw new IllegalArgumentException("a JWK set is {\"keys\": [...]} with at least one key");
        }
        List<Key> out = new ArrayList<>();
        for (JsonNode k : arr) {
            String kid = k.hasNonNull("kid") ? k.get("kid").asText() : null;
            try {
                switch (k.path("kty").asText()) {
                    case "RSA" -> out.add(new Key(kid, "RSA", KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(big(k, "n"), big(k, "e")))));
                    case "EC" -> out.add(new Key(kid, "EC", ec(k)));
                    default -> { }
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("key " + (kid == null ? "" : kid + " ") + "is not a valid " + k.path("kty").asText() + " key");
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("the set holds no RSA or EC (P-256, P-384) key");
        }
        return out;
    }

    private static PublicKey ec(JsonNode k) throws Exception {
        String curve = switch (k.path("crv").asText()) {
            case "P-256" -> "secp256r1";
            case "P-384" -> "secp384r1";
            default -> throw new IllegalArgumentException("curve");
        };
        java.security.AlgorithmParameters params = java.security.AlgorithmParameters.getInstance("EC");
        params.init(new java.security.spec.ECGenParameterSpec(curve));
        ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(big(k, "x"), big(k, "y")), spec));
    }

    private static BigInteger big(JsonNode k, String field) {
        return new BigInteger(1, Base64.getUrlDecoder().decode(k.path(field).asText()));
    }
}
