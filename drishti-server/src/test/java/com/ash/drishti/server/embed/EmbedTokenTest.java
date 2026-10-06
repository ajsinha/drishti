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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.ash.drishti.identity.AccessLog;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.server.security.TokenVerifier;
import com.ash.drishti.server.security.oidc.FakeProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Embed tokens (Drishti Elements, build steps 1 to 3): the RFC 8693 exchange with both client authentications and both subject
 * kinds and every refusal, the checks of every call, masking whatever the user's roles, rate limits, renewal, audit rows, and
 * {@code provenance.masked}.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=trading,counterparty-risk",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact=trader,counterparty,mtm", "drishti.security.registered-users-only=false",
        "drishti.security.roles.full.kinds[0]=*", "drishti.security.roles.full.raw=true",
        "drishti.security.oidc.enabled=true", "drishti.security.oidc.client-id=drishti",
        "drishti.embed.enabled=true", "drishti.embed.issuer=https://drishti.test", "drishti.embed.audiences[0]=https://console.test"})
@AutoConfigureMockMvc
class EmbedTokenTest {

    static final String MASK = "•••";
    static final String CRM = "https://crm.bank.example";
    static final String VIEW = "/api/v1/views/trade/MX-20000001";
    static final FakeProvider IDP = idp();
    static KeyPair host;

    static FakeProvider idp() {
        try {
            return new FakeProvider();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry r) {
        r.add("drishti.security.oidc.issuer", IDP::issuer);
    }

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        host = g.generateKeyPair();
    }

    @AfterAll
    static void stop() {
        IDP.close();
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired UserService users;
    @Autowired AccessLog accessLog;
    @Autowired AuditLog audit;
    @Autowired EmbedKeys embedKeys;
    final ObjectMapper json = new ObjectMapper();

    // ---- helpers ---------------------------------------------------------------------------------------------------------

    private String admin() {
        return "Bearer " + tokens.mint("root", List.of("admin"), 300);
    }

    private String uid(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    private String hostJwks() {
        RSAPublicKey k = (RSAPublicKey) host.getPublic();
        Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
        return "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"host-1\",\"n\":\"" + e.encodeToString(unsigned(k.getModulus().toByteArray())) + "\",\"e\":\""
                + e.encodeToString(unsigned(k.getPublicExponent().toByteArray())) + "\"}]}";
    }

    private static byte[] unsigned(byte[] b) {
        return b.length > 1 && b[0] == 0 ? java.util.Arrays.copyOfRange(b, 1, b.length) : b;
    }

    /** Registers a host application as an administrator; returns its secret. */
    private String register(String id, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of("id", id, "name", "Client CRM", "origins", List.of(CRM),
                "scopes", List.of("embed:view", "embed:about"), "jwks", hostJwks()));
        body.putAll(extra);
        MvcResult r = mvc.perform(post("/api/v1/admin/embed/apps").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return json.readTree(r.getResponse().getContentAsString()).path("secret").asText(null);
    }

    private void change(String id, Map<String, Object> patch) throws Exception {
        assertThat(mvc.perform(put("/api/v1/admin/embed/apps/" + id).header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(patch))).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    private void user(String name, String role) {
        users.create("admin", name, new UserService.Profile(name, name + "@bank.example", "", Set.of(role), true), "Str0ng-Passw0rd-" + name, false);
    }

    private String assertion(String app, String sub, Map<String, Object> over, java.security.PrivateKey key) throws Exception {
        long now = java.time.Instant.now().getEpochSecond();
        Map<String, Object> c = new java.util.LinkedHashMap<>(Map.of("iss", app, "sub", sub, "aud", "https://drishti.test", "iat", now, "exp", now + 60,
                "jti", UUID.randomUUID().toString()));
        c.putAll(over);
        return IDP.sign("RS256", "host-1", key == null ? host.getPrivate() : key, c);
    }

    private static String basic(String id, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    /** The host backend's request: client secret and a user assertion the host signed. */
    private MockHttpServletRequestBuilder exchange(String app, String secret, String subjectToken, String subjectType) {
        return post("/api/v1/embed/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).header("Authorization", basic(app, secret))
                .param("grant_type", EmbedTokenService.GRANT).param("subject_token", subjectToken).param("subject_token_type", subjectType);
    }

    private MvcResult run(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String token(String app, String secret, String user) throws Exception {
        MvcResult r = run(exchange(app, secret, assertion(app, user, Map.of(), null), EmbedTokenService.JWT));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).path("access_token").asText();
    }

    private MvcResult call(String token, String origin, String path) throws Exception {
        MockHttpServletRequestBuilder b = get(path).header("Authorization", "Bearer " + token);
        if (origin != null) {
            b = b.header("Origin", origin);
        }
        return run(b);
    }

    private void refused(MvcResult r, int status, String code) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(r.getResponse().getContentAsString()).contains("\"" + code + "\"");
    }

    private String forged(Map<String, Object> over) {
        long now = java.time.Instant.now().getEpochSecond();
        Map<String, Object> c = new java.util.LinkedHashMap<>(Map.of("iss", "https://drishti.test", "aud", "https://console.test", "sub", "x", "azp", "x",
                "scope", "embed:view", "iat", now, "exp", now + 300, "jti", "emb_x", "typ", EmbedTokenService.TYP));
        c.putAll(over);
        return embedKeys.sign(EmbedTokenService.TYP, c);
    }

    // ---- the happy paths -------------------------------------------------------------------------------------------------

    @Test
    void aHostsSignedAssertionBuysAReadOnlyMaskedTokenWhateverTheUsersRolesSay() throws Exception {
        String app = uid("crm-"), u = uid("ravi");
        String secret = register(app, Map.of());
        user(u, "full");
        MvcResult ex = run(exchange(app, secret, assertion(app, u, Map.of(), null), EmbedTokenService.JWT));
        assertThat(ex.getResponse().getStatus()).as(ex.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode out = body(ex);
        assertThat(out.path("token_type").asText()).isEqualTo("Bearer");
        assertThat(out.path("issued_token_type").asText()).isEqualTo(EmbedTokenService.ACCESS_TOKEN);
        assertThat(out.path("expires_in").asInt()).isBetween(1, 300);
        assertThat(out.path("scope").asText()).isEqualTo("embed:view embed:about");
        String tok = out.path("access_token").asText();
        JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(tok.split("\\.")[1]));
        assertThat(claims.path("aud").asText()).isEqualTo("https://console.test");
        assertThat(claims.path("sub").asText()).isEqualTo(u);
        assertThat(claims.path("azp").asText()).isEqualTo(app);
        assertThat(claims.path("origins").get(0).asText()).isEqualTo(CRM);
        assertThat(claims.has("roles")).isFalse();

        // the same user, signed in the usual way, has raw; through the embed token the masks always apply
        String own = "Bearer " + tokens.mint(u, List.of("full"), 300);
        JsonNode plain = json.readTree(mvc.perform(get(VIEW).header("Authorization", own)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(plain.toString()).contains("Meridian");
        assertThat(plain.path("provenance").path("masked").asInt()).isZero();

        MvcResult shown = call(tok, CRM, VIEW);
        assertThat(shown.getResponse().getStatus()).isEqualTo(200);
        JsonNode view = body(shown);
        assertThat(view.toString()).doesNotContain("Meridian").contains(MASK);
        JsonNode prov = view.path("provenance");
        assertThat(prov.path("masked").asInt()).isGreaterThan(0);
        assertThat(prov.path("maskedPanels").toString()).contains("terms");
    }

    @Test
    void theUsersIdTokenIsAnotherWayToSayWho() throws Exception {
        String app = uid("crm-"), u = uid("ida");
        String secret = register(app, Map.of());
        user(u, "full");
        String idToken = IDP.sign("RS256", "rsa-1", IDP.rsa.getPrivate(), IDP.claims(u, "drishti", "unused", Map.of()));
        MvcResult ex = run(exchange(app, secret, idToken, EmbedTokenService.ID_TOKEN));
        assertThat(ex.getResponse().getStatus()).as(ex.getResponse().getContentAsString()).isEqualTo(200);
        String tok = body(ex).path("access_token").asText();
        assertThat(json.readTree(Base64.getUrlDecoder().decode(tok.split("\\.")[1])).path("sub").asText()).isEqualTo(u);
        // an ID token the provider never signed, one for another audience and an expired one are refused
        refused(run(exchange(app, secret, IDP.sign("RS256", "rsa-1", IDP.stranger.getPrivate(), IDP.claims(u, "drishti", "n", Map.of())), EmbedTokenService.ID_TOKEN)), 400, "DRS-8001");
        refused(run(exchange(app, secret, IDP.sign("RS256", "rsa-1", IDP.rsa.getPrivate(), IDP.claims(u, "someone-else", "n", Map.of())), EmbedTokenService.ID_TOKEN)), 400, "DRS-8001");
        long past = java.time.Instant.now().getEpochSecond() - 3600;
        refused(run(exchange(app, secret, IDP.sign("RS256", "rsa-1", IDP.rsa.getPrivate(), IDP.claims(u, "drishti", "n", Map.of("exp", past, "iat", past - 60))), EmbedTokenService.ID_TOKEN)), 400, "DRS-8001");
    }

    @Test
    void aHostCanAuthenticateWithItsKeyInsteadOfASecret() throws Exception {
        String app = uid("crm-"), u = uid("kim");
        register(app, Map.of("secret", false));
        user(u, "full");
        MockHttpServletRequestBuilder b = post("/api/v1/embed/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", EmbedTokenService.GRANT).param("audience", "https://console.test")
                .param("client_assertion_type", EmbedTokenService.CLIENT_ASSERTION).param("client_assertion", assertion(app, app, Map.of(), null))
                .param("subject_token", assertion(app, u, Map.of(), null)).param("subject_token_type", EmbedTokenService.JWT);
        MvcResult ok = run(b);
        assertThat(ok.getResponse().getStatus()).as(ok.getResponse().getContentAsString()).isEqualTo(200);
        // a client assertion signed by a key the host did not register
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        MockHttpServletRequestBuilder forgedClient = post("/api/v1/embed/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", EmbedTokenService.GRANT).param("client_assertion_type", EmbedTokenService.CLIENT_ASSERTION)
                .param("client_assertion", assertion(app, app, Map.of(), g.generateKeyPair().getPrivate()))
                .param("subject_token", assertion(app, u, Map.of(), null)).param("subject_token_type", EmbedTokenService.JWT);
        refused(run(forgedClient), 401, "DRS-8003");
    }

    // ---- the refusals of the exchange ------------------------------------------------------------------------------------

    @Test
    void badHostCredentialsAreRefusedTheSameWayWhateverIsWrong() throws Exception {
        String app = uid("crm-"), u = uid("lee");
        String secret = register(app, Map.of());
        user(u, "full");
        String who = assertion(app, u, Map.of(), null);
        MvcResult wrong = run(exchange(app, "not-the-secret", who, EmbedTokenService.JWT));
        refused(wrong, 401, "DRS-8003");
        assertThat(body(wrong).path("error").asText()).isEqualTo("invalid_client");
        refused(run(exchange("no-such-app", secret, who, EmbedTokenService.JWT)), 401, "DRS-8003");
        refused(run(post("/api/v1/embed/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("grant_type", EmbedTokenService.GRANT)), 401, "DRS-8003");
    }

    @Test
    void aSecretAloneNamesNobody() throws Exception {
        String app = uid("crm-"), u = uid("max");
        String secret = register(app, Map.of());
        user(u, "full");
        // no plain username field exists: with the secret and only a user name there is no subject token to verify
        MvcResult r = run(post("/api/v1/embed/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).header("Authorization", basic(app, secret))
                .param("grant_type", EmbedTokenService.GRANT).param("username", u).param("sub", u).param("audience", "https://console.test"));
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        // an assertion signed by a stranger's key is no better
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        refused(run(exchange(app, secret, assertion(app, u, Map.of(), g.generateKeyPair().getPrivate()), EmbedTokenService.JWT)), 400, "DRS-8001");
    }

    @Test
    void theAudienceMustBeOneThisServerMakesTokensFor() throws Exception {
        String app = uid("crm-"), u = uid("nia");
        String secret = register(app, Map.of());
        user(u, "full");
        MvcResult r = run(exchange(app, secret, assertion(app, u, Map.of(), null), EmbedTokenService.JWT).param("audience", "https://evil.example"));
        refused(r, 400, "DRS-8001");
        assertThat(body(r).path("error").asText()).isEqualTo("invalid_target");
    }

    @Test
    void assertionsAreFreshAndUsedOnce() throws Exception {
        String app = uid("crm-"), u = uid("oli");
        String secret = register(app, Map.of());
        user(u, "full");
        String once = assertion(app, u, Map.of(), null);
        assertThat(run(exchange(app, secret, once, EmbedTokenService.JWT)).getResponse().getStatus()).isEqualTo(200);
        refused(run(exchange(app, secret, once, EmbedTokenService.JWT)), 400, "DRS-8001");                       // jti replayed
        long past = java.time.Instant.now().getEpochSecond() - 600;
        refused(run(exchange(app, secret, assertion(app, u, Map.of("iat", past - 60, "exp", past), null), EmbedTokenService.JWT)), 400, "DRS-8001");   // expired
        long now = java.time.Instant.now().getEpochSecond();
        refused(run(exchange(app, secret, assertion(app, u, Map.of("exp", now + 3600), null), EmbedTokenService.JWT)), 400, "DRS-8001");              // lives too long
        refused(run(exchange(app, secret, assertion(app, u, Map.of("aud", "https://other.example"), null), EmbedTokenService.JWT)), 400, "DRS-8001");  // not for us
    }

    @Test
    void aDisabledOrUnknownUserGetsNoToken() throws Exception {
        String app = uid("crm-"), u = uid("pia");
        String secret = register(app, Map.of());
        user(u, "full");
        refused(run(exchange(app, secret, assertion(app, "ghost-" + u, Map.of(), null), EmbedTokenService.JWT)), 400, "DRS-5010");
        mvc.perform(post("/api/v1/admin/users/" + u + "/enabled").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"));
        refused(run(exchange(app, secret, assertion(app, u, Map.of(), null), EmbedTokenService.JWT)), 400, "DRS-5010");
    }

    @Test
    void scopesAreWithinTheApplicationsAndTheOriginHeaderIsNeverAcceptedOnTheTokenEndpoint() throws Exception {
        String app = uid("crm-"), u = uid("quin");
        String secret = register(app, Map.of("scopes", List.of("embed:view")));
        user(u, "full");
        MvcResult r = run(exchange(app, secret, assertion(app, u, Map.of(), null), EmbedTokenService.JWT).param("scope", "embed:view embed:about"));
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(r).path("error").asText()).isEqualTo("invalid_scope");
        refused(run(exchange(app, secret, assertion(app, u, Map.of(), null), EmbedTokenService.JWT).header("Origin", CRM)), 403, "DRS-8002");
    }

    @Test
    void aDisabledHostGetsNoTokenAndItsTokensStopAtOnce() throws Exception {
        String app = uid("crm-"), u = uid("rae");
        String secret = register(app, Map.of());
        user(u, "full");
        String tok = token(app, secret, u);
        assertThat(call(tok, CRM, VIEW).getResponse().getStatus()).isEqualTo(200);
        change(app, Map.of("enabled", false));
        refused(call(tok, CRM, VIEW), 403, "DRS-8003");
        refused(run(exchange(app, secret, assertion(app, u, Map.of(), null), EmbedTokenService.JWT)), 401, "DRS-8003");
        change(app, Map.of("enabled", true));
        assertThat(call(tok, CRM, VIEW).getResponse().getStatus()).isEqualTo(200);
    }

    // ---- the checks of every call ----------------------------------------------------------------------------------------

    @Test
    void everyCallChecksTheTokenTheUserAndTheOrigin() throws Exception {
        String app = uid("crm-"), u = uid("sam");
        String secret = register(app, Map.of());
        user(u, "full");
        String tok = token(app, secret, u);
        assertThat(call(tok, CRM, VIEW).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(tok, null, VIEW).getResponse().getStatus()).isEqualTo(200);                     // the console's own call has no Origin
        refused(call(tok, "https://evil.example", VIEW), 403, "DRS-8002");                                  // origin mismatch
        refused(call(tok, "null", VIEW), 403, "DRS-8002");
        refused(call(tok + "x", CRM, VIEW), 401, "DRS-8001");                                              // bad signature
        refused(call("a.b.c", CRM, VIEW), 401, "DRS-5010");                                                // not an embed token at all
        refused(call(forged(Map.of("azp", app, "sub", u, "exp", java.time.Instant.now().getEpochSecond() - 5)), CRM, VIEW), 401, "DRS-8001");   // expired
        refused(call(forged(Map.of("azp", app, "sub", u, "aud", "https://another.console")), CRM, VIEW), 401, "DRS-8001");                        // wrong audience
        refused(call(forged(Map.of("azp", "nobody", "sub", u)), CRM, VIEW), 403, "DRS-8003");                                                     // unknown host
        mvc.perform(post("/api/v1/admin/users/" + u + "/enabled").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"));
        refused(call(tok, CRM, VIEW), 401, "DRS-5010");                                                    // the user was disabled meanwhile
    }

    @Test
    void anEmbedTokenOnlyReadsWhatItsScopesOpen() throws Exception {
        String app = uid("crm-"), u = uid("tia");
        String secret = register(app, Map.of("scopes", List.of("embed:view")));
        user(u, "full");
        String tok = token(app, secret, u);
        refused(call(tok, CRM, "/api/v1/me/tokens"), 403, "DRS-5002");
        refused(call(tok, CRM, "/api/v1/admin/embed/apps"), 403, "DRS-5002");
        refused(call(tok, CRM, VIEW + "/explain"), 403, "DRS-5002");                                       // embed:about was not granted
        MvcResult post = run(post("/api/v1/builder/designs").header("Authorization", "Bearer " + tok).contentType(MediaType.APPLICATION_JSON).content("{}"));
        refused(post, 403, "DRS-5002");
        MvcResult del = run(delete("/api/v1/views/trade/MX-20000001").header("Authorization", "Bearer " + tok));
        assertThat(del.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void aTypedCommandResolvesForTheHostButNeverEntersTheUsersHistory() throws Exception {
        String app = uid("crm-"), u = uid("hal");
        String secret = register(app, Map.of());
        user(u, "full");
        String tok = token(app, secret, u);
        MvcResult r = run(post("/api/v1/command").header("Authorization", "Bearer " + tok).header("Origin", CRM).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"TRD MX-20000001\"}"));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        String history = run(get("/api/v1/command/history").header("Authorization", "Bearer " + tokens.mint(u, List.of("full"), 60))).getResponse().getContentAsString();
        assertThat(history).doesNotContain("MX-20000001");
    }

    @Test
    void anApplicationMayNarrowTheKindsItShows() throws Exception {
        String app = uid("crm-"), u = uid("uma");
        String secret = register(app, Map.of("kinds", List.of("counterparty")));
        user(u, "full");
        String tok = token(app, secret, u);
        refused(call(tok, CRM, VIEW), 400, "DRS-8005");
        assertThat(call(tok, CRM, "/api/v1/views/counterparty/CP-MERIDIAN-RE").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void callsAreRateLimitedPerApplicationAndPerUser() throws Exception {
        String app = uid("crm-"), u = uid("val"), w = uid("wes");
        String secret = register(app, Map.of("callsPerMinute", 100, "userCallsPerMinute", 3));
        user(u, "full");
        user(w, "full");
        String tok = token(app, secret, u);
        for (int i = 0; i < 3; i++) {
            assertThat(call(tok, CRM, VIEW).getResponse().getStatus()).isEqualTo(200);
        }
        MvcResult over = call(tok, CRM, VIEW);
        refused(over, 429, "DRS-8004");
        assertThat(Long.parseLong(over.getResponse().getHeader("Retry-After"))).isBetween(1L, 60L);
        assertThat(call(token(app, secret, w), CRM, VIEW).getResponse().getStatus()).isEqualTo(200);      // another user is not affected

        String busy = uid("busy-"), x = uid("xan");
        String busySecret = register(busy, Map.of("callsPerMinute", 2, "userCallsPerMinute", 50));
        user(x, "full");
        String t = token(busy, busySecret, x);
        assertThat(call(t, CRM, VIEW).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(t, CRM, VIEW).getResponse().getStatus()).isEqualTo(200);
        refused(call(t, CRM, VIEW), 429, "DRS-8004");                                                      // the application's own rate
    }

    @Test
    void aLongStreamRenewsInPlaceWithAFreshTokenOfTheSameUserAndApplication() throws Exception {
        String app = uid("crm-"), u = uid("yan");
        String secret = register(app, Map.of());
        user(u, "full");
        String first = token(app, secret, u);
        String second = token(app, secret, u);                                                              // the host backend answers the element's request
        assertThat(second).isNotEqualTo(first);
        JsonNode a = json.readTree(Base64.getUrlDecoder().decode(first.split("\\.")[1]));
        JsonNode b = json.readTree(Base64.getUrlDecoder().decode(second.split("\\.")[1]));
        assertThat(b.path("jti").asText()).isNotEqualTo(a.path("jti").asText());
        assertThat(b.path("exp").asLong()).isGreaterThanOrEqualTo(a.path("exp").asLong());
        for (String t : List.of(first, second)) {
            JsonNode check = body(call(t, CRM, "/api/v1/embed/check"));
            assertThat(check.path("app").asText()).isEqualTo(app);
            assertThat(check.path("user").asText()).isEqualTo(u);
            assertThat(check.path("expiresIn").asLong()).isPositive();
        }
    }

    // ---- audit, discovery, off by default ----------------------------------------------------------------------------------

    @Test
    void theAccessLogSaysWhichHostShowedWhatToWhomAndTheAuditLogHasTheExchange() throws Exception {
        String app = uid("crm-"), u = uid("zoe");
        String secret = register(app, Map.of());
        user(u, "full");
        String tok = token(app, secret, u);
        assertThat(call(tok, CRM, VIEW).getResponse().getStatus()).isEqualTo(200);
        accessLog.flush();
        List<AccessLog.Event> seen = accessLog.find(new AccessLog.Filter(u, "view", null, null, null, null, 10));
        assertThat(seen).anySatisfy(e -> {
            assertThat(e.detail()).isEqualTo("embed:" + app);
            assertThat(e.kind()).isEqualTo("trade");
            assertThat(e.entityId()).isEqualTo("MX-20000001");
        });
        assertThat(audit.recent(50, app)).extracting(AuditLog.Event::action).contains("embed-app-created", "embed-token");
        run(exchange(app, "wrong", "x", EmbedTokenService.JWT));
        assertThat(audit.recent(50, app)).extracting(AuditLog.Event::action).contains("embed-token-refused");
    }

    @Test
    void theKeyIsPublishedAndTheConsoleLearnsTheOrigins() throws Exception {
        String app = uid("crm-");
        register(app, Map.of());
        MvcResult jwks = run(get("/api/v1/embed/jwks"));
        assertThat(jwks.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(jwks).path("keys").get(0).path("alg").asText()).isEqualTo("ES256");
        String service = "Bearer " + tokens.mint("console", List.of("service"), 60);
        MvcResult origins = run(get("/api/v1/embed/apps/origins").header("Authorization", service));
        assertThat(body(origins).path("origins").toString()).contains(CRM);
        assertThat(run(get("/api/v1/embed/apps/origins").header("Authorization", "Bearer " + tokens.mint("bob", List.of("full"), 60))).getResponse().getStatus()).isEqualTo(403);
        assertThat(run(get("/api/v1/admin/embed/apps").header("Authorization", "Bearer " + tokens.mint("bob", List.of("full"), 60))).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void theRegistryRefusesWhatIsNotAnExactOriginAndRotatesSecrets() throws Exception {
        String app = uid("crm-"), u = uid("abe");
        MvcResult bad = run(post("/api/v1/admin/embed/apps").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("id", app, "name", "x", "origins", List.of("https://*.bank.example"), "scopes", List.of("embed:view")))));
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);                                        // wildcards are off
        String secret = register(app, Map.of());
        user(u, "full");
        MvcResult rot = run(post("/api/v1/admin/embed/apps/" + app + "/rotate-secret").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON).content("{\"graceSeconds\":0}"));
        String next = body(rot).path("secret").asText();
        assertThat(next).isNotEqualTo(secret);
        refused(run(exchange(app, secret, assertion(app, u, Map.of(), null), EmbedTokenService.JWT)), 401, "DRS-8003");        // the old secret is gone
        assertThat(run(exchange(app, next, assertion(app, u, Map.of(), null), EmbedTokenService.JWT)).getResponse().getStatus()).isEqualTo(200);
        assertThat(run(get("/api/v1/admin/embed/apps/" + app).header("Authorization", admin())).getResponse().getContentAsString()).doesNotContain(next);
    }
}
