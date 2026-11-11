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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * Verifies an OpenID Connect ID token with the JDK alone: the signature (RSA PKCS#1, RSA-PSS or ECDSA, from an allowed
 * list that never includes {@code none} or a shared-secret HMAC) against the provider's published key, then the
 * issuer, the audience (and the authorised party when there are several audiences), expiry, not-before, issued-at
 * and the nonce the console sent. Any failure is the same refusal, so a caller learns nothing about which check
 * failed. Stateless apart from the key cache; thread-safe.
 */
public final class IdTokenVerifier {

    private final OidcProperties props;
    private final JwksCache keys;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public IdTokenVerifier(OidcProperties props, JwksCache keys, Clock clock) {
        this.props = props;
        this.keys = keys;
        this.clock = clock;
    }

    /** The token's claims when every check passes; otherwise a sign-in refusal. */
    public JsonNode verify(String token, String expectedNonce) {
        return verify(token, expectedNonce, java.util.List.of(props.clientId()), true);
    }

    /**
     * An ID token a host application presents to exchange for an embed token (RFC 8693): the same signature, issuer, expiry
     * and audience checks, the audience being any of {@code audiences} (the client ids the provider issued to Drishti or to the
     * host application), and no nonce (the user did not sign in to Drishti with it).
     */
    public JsonNode verifyForExchange(String token, java.util.Collection<String> audiences) {
        return verify(token, null, audiences, false);
    }

    private JsonNode verify(String token, String expectedNonce, java.util.Collection<String> audiences, boolean checkNonce) {
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.", -1);
            if (parts.length != 3) {
                throw refuse("not a signed token");
            }
            JsonNode header = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
            String alg = header.path("alg").asText();
            if (!props.algorithms().contains(alg)) {
                throw refuse("algorithm " + alg + " not accepted");
            }
            String family = alg.startsWith("ES") ? "EC" : "RSA";
            JwksCache.Key key = keys.find(header.hasNonNull("kid") ? header.get("kid").asText() : null, family)
                    .orElseThrow(() -> refuse("unknown signing key"));
            Signature sig = signature(alg);
            sig.initVerify(key.key());
            sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!sig.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                throw refuse("bad signature");
            }
            JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
            checkClaims(claims, expectedNonce, audiences, checkNonce);
            return claims;
        } catch (DrishtiException e) {
            throw e;
        } catch (Exception e) {
            throw refuse("unreadable token");
        }
    }

    private void checkClaims(JsonNode c, String expectedNonce, java.util.Collection<String> audiences, boolean checkNonce) {
        Instant now = clock.instant();
        long skew = props.clockSkew().toSeconds();
        if (!props.issuer().equals(c.path("iss").asText().replaceAll("/+$", ""))) {
            throw refuse("issuer");
        }
        JsonNode aud = c.path("aud");
        java.util.List<String> accepted = audiences.stream().filter(a -> a != null && !a.isBlank()).toList();
        boolean audienceOk = accepted.stream().anyMatch(a -> aud.isArray() ? contains(aud, a) : a.equals(aud.asText()));
        if (!audienceOk) {
            throw refuse("audience");
        }
        if (aud.isArray() && aud.size() > 1 && !accepted.contains(c.path("azp").asText())) {
            throw refuse("authorised party");
        }
        if (!c.path("exp").canConvertToLong() || c.path("exp").asLong() + skew < now.getEpochSecond()) {
            throw refuse("expired");
        }
        if (c.hasNonNull("nbf") && c.get("nbf").asLong() - skew > now.getEpochSecond()) {
            throw refuse("not yet valid");
        }
        if (c.hasNonNull("iat") && c.get("iat").asLong() - skew > now.getEpochSecond()) {
            throw refuse("issued in the future");
        }
        if (checkNonce && (expectedNonce == null || expectedNonce.isBlank()
                || !MessageDigest.isEqual(expectedNonce.getBytes(StandardCharsets.UTF_8), c.path("nonce").asText().getBytes(StandardCharsets.UTF_8)))) {
            throw refuse("nonce");
        }
    }

    private static boolean contains(JsonNode arr, String value) {
        for (JsonNode n : arr) {
            if (value.equals(n.asText())) {
                return true;
            }
        }
        return false;
    }

    private static Signature signature(String alg) throws Exception {
        return switch (alg) {
            case "RS256" -> Signature.getInstance("SHA256withRSA");
            case "RS384" -> Signature.getInstance("SHA384withRSA");
            case "RS512" -> Signature.getInstance("SHA512withRSA");
            case "PS256" -> pss("SHA-256", MGF1ParameterSpec.SHA256, 32);
            case "PS384" -> pss("SHA-384", MGF1ParameterSpec.SHA384, 48);
            case "PS512" -> pss("SHA-512", MGF1ParameterSpec.SHA512, 64);
            case "ES256" -> Signature.getInstance("SHA256withECDSAinP1363Format");   // JOSE signatures are r||s, not DER
            case "ES384" -> Signature.getInstance("SHA384withECDSAinP1363Format");
            case "ES512" -> Signature.getInstance("SHA512withECDSAinP1363Format");
            default -> throw new IllegalArgumentException(alg);
        };
    }

    private static Signature pss(String digest, MGF1ParameterSpec mgf, int salt) throws Exception {
        Signature s = Signature.getInstance("RSASSA-PSS");
        s.setParameter(new PSSParameterSpec(digest, "MGF1", mgf, salt, 1));
        return s;
    }

    /** One refusal for every failure; the reason is logged by the caller at debug level only. */
    private static DrishtiException refuse(String why) {
        DrishtiException e = new DrishtiException(ErrorCode.BAD_CREDENTIALS, "single sign-on was refused");
        e.addSuppressed(new IllegalStateException(why));
        return e;
    }
}
