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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class IdTokenVerifierTest {

    static FakeProvider idp;
    static IdTokenVerifier verifier;

    @BeforeAll
    static void start() throws Exception {
        idp = new FakeProvider();
        OidcProperties props = new OidcProperties(true, idp.issuer(), "drishti", null, null, null, null, null, null, null, Set.of("RS256", "ES256"), null);
        verifier = new IdTokenVerifier(props, new JwksCache(props, HttpClient.newHttpClient(), Clock.systemUTC()), Clock.systemUTC());
    }

    @AfterAll
    static void stop() {
        idp.close();
    }

    private static void refused(String token, String nonce) {
        assertThatThrownBy(() -> verifier.verify(token, nonce)).isInstanceOf(DrishtiException.class).hasMessageContaining("refused");
    }

    @Test
    void acceptsRsaAndEcTokensFromThePublishedKeys() throws Exception {
        assertThat(verifier.verify(idp.sign("RS256", "rsa-1", idp.rsa.getPrivate(), idp.claims("ana", "drishti", "n1", Map.of())), "n1")
                .path("preferred_username").asText()).isEqualTo("ana");
        assertThat(verifier.verify(idp.sign("ES256", "ec-1", idp.ec.getPrivate(), idp.claims("bea", "drishti", "n2", Map.of())), "n2")
                .path("email").asText()).isEqualTo("bea@example.com");
        assertThat(verifier.verify(idp.sign("RS256", "rsa-1", idp.rsa.getPrivate(),
                idp.claims("cy", "other", "n3", Map.of("aud", java.util.List.of("other", "drishti"), "azp", "drishti"))), "n3")).isNotNull();
    }

    @Test
    void refusesEveryKindOfBadToken() throws Exception {
        var ok = idp.claims("ana", "drishti", "n", Map.of());
        refused(idp.sign("RS256", "rsa-1", idp.stranger.getPrivate(), ok), "n");                            // forged signature
        refused(idp.sign("none", null, null, ok), "n");                                                     // alg none
        refused(idp.sign("HS256", "rsa-1", idp.rsa.getPrivate(), ok), "n");                                 // not an accepted algorithm
        refused(idp.sign("RS256", "unknown", idp.rsa.getPrivate(), ok), "n");                               // unknown key id
        refused(idp.sign("RS256", "ec-1", idp.rsa.getPrivate(), ok), "n");                                  // key of another family
        refused(idp.sign("RS256", "rsa-1", idp.rsa.getPrivate(), idp.claims("ana", "someone-else", "n", Map.of())), "n");   // audience
        refused(idp.sign("RS256", "rsa-1", idp.rsa.getPrivate(), idp.claims("ana", "drishti", "n", Map.of("iss", "https://evil.example"))), "n");
        refused(idp.sign("RS256", "rsa-1", idp.rsa.getPrivate(), idp.claims("ana", "drishti", "n", Map.of("exp", 1_000_000_000L))), "n");  // expired
        refused(idp.sign("RS256", "rsa-1", idp.rsa.getPrivate(), idp.claims("ana", "drishti", "n",
                Map.of("nbf", java.time.Instant.now().getEpochSecond() + 3600))), "n");                   // not yet valid
        refused(idp.sign("RS256", "rsa-1", idp.rsa.getPrivate(), ok), "another-nonce");                     // replayed into another sign-in
        refused(idp.sign("RS256", "rsa-1", idp.rsa.getPrivate(), idp.claims("cy", "other", "n",
                Map.of("aud", java.util.List.of("other", "drishti"), "azp", "other"))), "n");              // issued to another party
        String good = idp.sign("RS256", "rsa-1", idp.rsa.getPrivate(), ok);
        String[] p = good.split("\\.");
        refused(p[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"iss\":\"x\",\"preferred_username\":\"root\"}".getBytes()) + "." + p[2], "n");        // tampered payload
        refused("not-a-token", "n");
    }

    @Test
    void unknownKeyIdsRefreshTheKeysAtMostOncePerMinute() throws Exception {
        int before = idp.jwksHits.get();
        for (int i = 0; i < 20; i++) {
            refused(idp.sign("RS256", "rotated-" + i, idp.rsa.getPrivate(), idp.claims("ana", "drishti", "n", Map.of())), "n");
        }
        assertThat(idp.jwksHits.get() - before).isLessThanOrEqualTo(1);
    }
}
