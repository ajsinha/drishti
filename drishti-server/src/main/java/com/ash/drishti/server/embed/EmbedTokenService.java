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

import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.EmbedAppStore;
import com.ash.drishti.identity.User;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.oidc.IdTokenVerifier;
import com.ash.drishti.server.security.oidc.OidcProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.util.AntPathMatcher;

/**
 * Embed tokens (docs/architecture/ELEMENTS.md, section 8): the RFC 8693 token exchange a host application's BACKEND makes, and the
 * checks every embed call goes through.
 *
 * <p><b>Exchange.</b> The host authenticates ({@code client_secret_basic}, or {@code private_key_jwt} with a key it registered) and
 * presents who its user is: the user's OIDC ID token, or a JWT the host signs about the user with its registered key (60 s,
 * {@code jti} used once). There is no field that simply names a user, so a stolen client secret alone makes nobody. The token is
 * an ES256 JWT for one audience, a few minutes long, with read-only embed scopes; it carries no roles (they are read at each call).
 *
 * <p><b>Every call.</b> {@link #authorize}: signature, {@code typ}, audience, expiry; the host application exists and is enabled; the
 * user exists and is enabled; the request's {@code Origin} (when it has one) is one of the application's; the scopes open the
 * method and path; the kind is one the application may show; the call rates of the application and of the user. The caller
 * becomes a {@link Principal} with the user's roles as they are now and {@code embedApp} set, which makes every field mask apply
 * whatever the roles say (Decision 5).
 */
public final class EmbedTokenService {

    public static final String TYP = "drishti-embed+jwt";
    public static final String GRANT = "urn:ietf:params:oauth:grant-type:token-exchange";
    public static final String ID_TOKEN = "urn:ietf:params:oauth:token-type:id_token";
    public static final String JWT = "urn:ietf:params:oauth:token-type:jwt";
    public static final String ACCESS_TOKEN = "urn:ietf:params:oauth:token-type:access_token";
    public static final String CLIENT_ASSERTION = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    /** Request attribute holding the {@link Authorized} of an embed call. */
    public static final String GRANT_ATTRIBUTE = "drishti.embed.grant";

    /** What the token endpoint received; nulls for what the request did not carry. */
    public record Request(String basicId, String basicSecret, String clientAssertionType, String clientAssertion, String grantType,
            String subjectToken, String subjectTokenType, String audience, String scope, String origin) {}

    /** The RFC 8693 answer. */
    public record Response(String access_token, String issued_token_type, String token_type, long expires_in, String scope) {}

    /** A call that passed every check. */
    public record Authorized(Principal principal, String app, String jti, List<String> scopes, Instant expiresAt) {}

    private static final long MAX_ASSERTION_AGE_SKEW = 5;

    private final EmbedProperties props;
    private final EmbedKeys keys;
    private final EmbedAppStore apps;
    private final UserService users;
    private final AuditLog audit;
    private final IdTokenVerifier idTokens;
    private final OidcProperties oidc;
    private final Clock clock;
    private final boolean securityOn;
    private final RateWindows rates;
    private final EmbedUsage usage;
    private final Instant started = Instant.now();
    private final Map<String, Long> usedJti = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final AntPathMatcher matcher = new AntPathMatcher();

    public EmbedTokenService(EmbedProperties props, EmbedKeys keys, EmbedAppStore apps, UserService users, AuditLog audit,
            IdTokenVerifier idTokens, OidcProperties oidc, Clock clock, boolean securityOn, EmbedUsage usage) {
        this.usage = usage;
        this.props = props;
        this.keys = keys;
        this.apps = apps;
        this.users = users;
        this.audit = audit;
        this.idTokens = idTokens;
        this.oidc = oidc;
        this.clock = clock;
        this.securityOn = securityOn;
        this.rates = new RateWindows(clock);
        if (props.enabled() && !securityOn) {
            throw new IllegalStateException("drishti.embed.enabled needs drishti.security.enabled: an embed token is only checked when security is on");
        }
    }

    /** When this server (and so its usage counters) started. */
    public Instant startedAt() {
        return started;
    }

    public boolean enabled() {
        return props.enabled();
    }

    public EmbedProperties properties() {
        return props;
    }

    public EmbedKeys keys() {
        return keys;
    }

    // ---- the exchange ------------------------------------------------------------------------------------------------------

    public Response exchange(Request r) {
        if (!props.enabled()) {
            throw new EmbedException(ErrorCode.EMBED_APP, 403, "access_denied", "embedding is not switched on on this server (drishti.embed.enabled)");
        }
        String claimed = null;
        try {
            if (r.origin() != null && !r.origin().isBlank()) {
                throw new EmbedException(ErrorCode.EMBED_ORIGIN, 403, "access_denied", "the token endpoint is for a host application's backend, never a browser");
            }
            if (!GRANT.equals(r.grantType())) {
                throw new EmbedException(ErrorCode.BAD_REQUEST, 400, "unsupported_grant_type", "grant_type must be " + GRANT);
            }
            Jws.Parsed assertion = null;
            if (r.clientAssertion() != null && !r.clientAssertion().isBlank()) {
                if (!CLIENT_ASSERTION.equals(r.clientAssertionType())) {
                    throw new EmbedException(ErrorCode.BAD_REQUEST, 400, "invalid_request", "client_assertion_type must be " + CLIENT_ASSERTION);
                }
                assertion = parse(r.clientAssertion(), "invalid_client", ErrorCode.EMBED_APP, 401);
                claimed = assertion.claims().path("sub").asText("");
            } else if (r.basicId() != null) {
                claimed = r.basicId();
            }
            if (claimed == null || claimed.isBlank()) {
                throw new EmbedException(ErrorCode.EMBED_APP, 401, "invalid_client", "authenticate the host application: client_secret_basic or a client_assertion");
            }
            long wait = rates.take("tok:" + (claimed.length() > 64 ? claimed.substring(0, 64) : claimed), props.limits().tokensPerMinute());
            if (wait > 0) {
                throw new EmbedException(ErrorCode.EMBED_RATE_LIMITED, 429, "slow_down", "too many token requests from this host application; retry in " + wait + " s", wait);
            }
            EmbedAppStore.App app = authenticate(claimed, r, assertion);
            String audience = r.audience() == null || r.audience().isBlank() ? props.defaultAudience() : r.audience().trim().replaceAll("/+$", "");
            if (audience.isEmpty() || !props.acceptsAudience(audience)) {
                throw new EmbedException(ErrorCode.EMBED_TOKEN_INVALID, 400, "invalid_target", "this server makes no token for the audience '" + audience + "'");
            }
            List<String> scopes = scopes(app, r.scope());
            User user = subject(app, r, audience);
            Instant now = clock.instant();
            Instant exp = now.plusSeconds(Math.min(app.tokenSeconds(), props.tokenMaxSeconds()));
            String jti = "emb_" + randomText(12);
            String scope = String.join(" ", scopes);
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("iss", props.issuer().isEmpty() ? audience : props.issuer());
            claims.put("aud", audience);
            claims.put("sub", user.username());
            claims.put("azp", app.id());
            claims.put("scope", scope);
            claims.put("origins", app.origins());
            claims.put("iat", now.getEpochSecond());
            claims.put("exp", exp.getEpochSecond());
            claims.put("jti", jti);
            claims.put("typ", TYP);
            String token = keys.sign(TYP, claims);
            audit.record(user.username(), "embed-token", app.id(), jti + " scope " + scope + " aud " + audience + " expires " + exp);
            apps.touch(app.id());
            usage.tokenIssued(app.id());
            return new Response(token, ACCESS_TOKEN, "Bearer", exp.getEpochSecond() - now.getEpochSecond(), scope);
        } catch (EmbedException e) {
            usage.refused(claimed != null && apps.find(claimed).isPresent() ? claimed : EmbedUsage.UNKNOWN, e.errorCode().code());
            audit.record(claimed == null || claimed.isBlank() ? "-" : claimed, "embed-token-refused", claimed == null ? "" : claimed, e.error() + ": " + e.getMessage());
            throw e;
        }
    }

    private EmbedAppStore.App authenticate(String id, Request r, Jws.Parsed assertion) {
        EmbedAppStore.App app = apps.find(id).orElse(null);
        if (app == null || !app.enabled()) {
            throw new EmbedException(ErrorCode.EMBED_APP, 401, "invalid_client", "unknown or disabled host application");
        }
        if (assertion != null) {
            if (app.jwks() == null || !Jws.verify(assertion, Jws.keys(app.jwks()))) {
                throw new EmbedException(ErrorCode.EMBED_APP, 401, "invalid_client", "the client assertion is not signed by a key registered for this host application");
            }
            checkAssertion(app, assertion, "client assertion", true);
        } else if (!apps.secretMatches(id, r.basicSecret())) {
            throw new EmbedException(ErrorCode.EMBED_APP, 401, "invalid_client", "wrong client secret");
        }
        return app;
    }

    /** exp, iat, jti, audience and the host as issuer of a JWT the host signed; the jti is remembered until the JWT would have expired. */
    private void checkAssertion(EmbedAppStore.App app, Jws.Parsed t, String what, boolean client) {
        JsonNode c = t.claims();
        long now = clock.instant().getEpochSecond();
        long skew = MAX_ASSERTION_AGE_SKEW;
        long exp = c.path("exp").asLong(0);
        long iat = c.path("iat").asLong(0);
        String jti = c.path("jti").asText("");
        EmbedException refusal = null;
        if (!app.id().equals(c.path("iss").asText()) || client && !app.id().equals(c.path("sub").asText())) {
            refusal = bad(client, what + " is not issued by '" + app.id() + "'");
        } else if (exp == 0 || exp + skew < now) {
            refusal = bad(client, what + " has expired");
        } else if (iat == 0 || iat - skew > now || exp - iat > props.assertionMaxAge().toSeconds() + skew) {
            refusal = bad(client, what + " must carry iat and live at most " + props.assertionMaxAge().toSeconds() + " s");
        } else if (jti.isBlank()) {
            refusal = bad(client, what + " has no jti");
        } else if (!audienceNamed(c.path("aud"))) {
            refusal = bad(client, what + " is not addressed to this server (aud)");
        }
        if (refusal != null) {
            throw refusal;
        }
        String key = (client ? "c:" : "s:") + app.id() + ":" + jti;
        if (usedJti.putIfAbsent(key, exp + skew) != null) {
            throw bad(client, what + " was used before (jti)");
        }
        if (usedJti.size() > 10_000) {
            usedJti.values().removeIf(until -> until < now);
        }
    }

    private EmbedException bad(boolean client, String why) {
        return client ? new EmbedException(ErrorCode.EMBED_APP, 401, "invalid_client", why)
                : new EmbedException(ErrorCode.EMBED_TOKEN_INVALID, 400, "invalid_grant", why);
    }

    /** The assertion's audience is this server: its issuer, one of the audiences it makes tokens for, or its token endpoint. */
    private boolean audienceNamed(JsonNode aud) {
        List<String> given = new ArrayList<>();
        if (aud.isArray()) {
            aud.forEach(a -> given.add(a.asText()));
        } else if (aud.isTextual()) {
            given.add(aud.asText());
        }
        return given.stream().map(a -> a.replaceAll("/+$", "")).anyMatch(a -> a.equals(props.issuer()) || props.audiences().contains(a)
                || a.endsWith("/api/v1/embed/token"));
    }

    private List<String> scopes(EmbedAppStore.App app, String requested) {
        if (requested == null || requested.isBlank()) {
            return app.scopes().stream().filter(s -> props.scopes().containsKey(s)).toList();
        }
        List<String> out = new ArrayList<>();
        for (String s : requested.trim().split("\\s+")) {
            if (!props.scopes().containsKey(s) || !app.scopes().contains(s)) {
                throw new EmbedException(ErrorCode.BAD_REQUEST, 400, "invalid_scope", "the scope '" + s + "' is not one this host application may have; its scopes: "
                        + String.join(" ", app.scopes()));
            }
            if (!out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private User subject(EmbedAppStore.App app, Request r, String audience) {
        if (r.subjectToken() == null || r.subjectToken().isBlank() || r.subjectTokenType() == null) {
            throw new EmbedException(ErrorCode.BAD_REQUEST, 400, "invalid_request", "subject_token and subject_token_type say who the user is");
        }
        String name;
        switch (r.subjectTokenType()) {
            case ID_TOKEN -> {
                allowType(app, "id_token");
                if (!oidc.enabled()) {
                    throw new EmbedException(ErrorCode.EMBED_TOKEN_INVALID, 400, "invalid_grant", "ID tokens are not accepted: single sign-on is not enabled on this server");
                }
                List<String> audiences = new ArrayList<>(app.subjectAudiences());
                audiences.add(oidc.clientId());
                JsonNode claims;
                try {
                    claims = idTokens.verifyForExchange(r.subjectToken(), audiences);
                } catch (com.ash.drishti.common.DrishtiException e) {
                    throw new EmbedException(ErrorCode.EMBED_TOKEN_INVALID, 400, "invalid_grant", "the user's ID token was refused (signature, issuer, audience or expiry)");
                }
                name = firstText(claims, oidc.usernameClaim(), "email", "sub");
                try {
                    name = UserService.federatedName(name);
                } catch (com.ash.drishti.common.DrishtiException e) {
                    throw new EmbedException(ErrorCode.EMBED_TOKEN_INVALID, 400, "invalid_grant", "the ID token names no usable user");
                }
            }
            case JWT -> {
                allowType(app, "jwt");
                Jws.Parsed t = parse(r.subjectToken(), "invalid_grant", ErrorCode.EMBED_TOKEN_INVALID, 400);
                if (app.jwks() == null || !Jws.verify(t, Jws.keys(app.jwks()))) {
                    throw new EmbedException(ErrorCode.EMBED_TOKEN_INVALID, 400, "invalid_grant", "the user assertion is not signed by a key registered for this host application");
                }
                checkAssertion(app, t, "user assertion", false);
                name = t.claims().path("sub").asText("");
                if (name.isBlank()) {
                    throw new EmbedException(ErrorCode.EMBED_TOKEN_INVALID, 400, "invalid_grant", "the user assertion names no user (sub)");
                }
            }
            default -> throw new EmbedException(ErrorCode.BAD_REQUEST, 400, "invalid_request", "subject_token_type must be " + ID_TOKEN + " or " + JWT);
        }
        User u = users.find(name).orElse(null);
        if (u == null || !u.enabled()) {
            throw new EmbedException(ErrorCode.UNAUTHENTICATED, 400, "invalid_grant", "the user does not exist in this application or is disabled");
        }
        return u;
    }

    private static void allowType(EmbedAppStore.App app, String type) {
        if (!app.subjectTypes().contains(type)) {
            throw new EmbedException(ErrorCode.BAD_REQUEST, 400, "invalid_request", "this host application may not present '" + type + "' subject tokens");
        }
    }

    private static Jws.Parsed parse(String token, String error, ErrorCode code, int status) {
        try {
            return Jws.parse(token);
        } catch (IllegalArgumentException e) {
            throw new EmbedException(code, status, error, "the token is not a signed JWT");
        }
    }

    private static String firstText(JsonNode claims, String... names) {
        for (String n : names) {
            JsonNode at = claims;
            for (String part : n.split("\\.")) {
                at = at.path(part);
            }
            if (!at.asText("").isBlank()) {
                return at.asText();
            }
        }
        return "";
    }

    // ---- every call ----------------------------------------------------------------------------------------------------------

    /** Whether the bearer looks like an embed token (by its JOSE header), so the filter routes it here. */
    public static boolean looksLikeEmbed(String bearer) {
        try {
            return TYP.equals(Jws.parse(bearer).header().path("typ").asText());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Checks one embed call (see the class comment); throws the refusal with its DRS code. */
    public Authorized authorize(String bearer, String origin, String method, String path) {
        String[] known = new String[1];
        try {
            Authorized a = authorize(bearer, origin, method, path, known);
            usage.call(a.app());
            return a;
        } catch (EmbedException e) {
            usage.refused(known[0] == null ? EmbedUsage.UNKNOWN : known[0], e.errorCode().code());
            throw e;
        }
    }

    private Authorized authorize(String bearer, String origin, String method, String path, String[] known) {
        if (!props.enabled()) {
            throw new EmbedException(ErrorCode.EMBED_TOKEN_INVALID, 401, "invalid_token", "embedded views are not switched on on this server");
        }
        Jws.Parsed t;
        try {
            t = Jws.parse(bearer);
        } catch (IllegalArgumentException e) {
            throw new EmbedException(ErrorCode.EMBED_TOKEN_INVALID, 401, "invalid_token", "embed token malformed");
        }
        if (!TYP.equals(t.header().path("typ").asText()) || !keys.verify(t)) {
            throw new EmbedException(ErrorCode.EMBED_TOKEN_INVALID, 401, "invalid_token", "embed token invalid");
        }
        JsonNode c = t.claims();
        if (!TYP.equals(c.path("typ").asText(TYP)) || c.path("exp").asLong(0) <= clock.instant().getEpochSecond()) {
            throw new EmbedException(ErrorCode.EMBED_TOKEN_INVALID, 401, "invalid_token", "embed token expired");
        }
        if (!props.acceptsAudience(c.path("aud").asText("").replaceAll("/+$", ""))) {
            throw new EmbedException(ErrorCode.EMBED_TOKEN_INVALID, 401, "invalid_token", "embed token is for another audience");
        }
        EmbedAppStore.App app = apps.find(c.path("azp").asText("")).orElse(null);
        if (app != null) {
            known[0] = app.id();
        }
        if (app == null || !app.enabled()) {
            throw new EmbedException(ErrorCode.EMBED_APP, 403, "access_denied", "the host application is unknown or disabled");
        }
        User u = users.find(c.path("sub").asText("")).orElse(null);
        if (u == null || !u.enabled()) {
            throw new EmbedException(ErrorCode.UNAUTHENTICATED, 401, "invalid_token", "the user behind this token is disabled or gone");
        }
        if (origin != null && !origin.isBlank() && !app.allowsOrigin(origin)) {
            throw new EmbedException(ErrorCode.EMBED_ORIGIN, 403, "access_denied", "this origin is not one of the host application's origins");
        }
        List<String> granted = new ArrayList<>();
        for (String s : c.path("scope").asText("").split("\\s+")) {
            if (app.scopes().contains(s) && props.scopes().containsKey(s)) {
                granted.add(s);
            }
        }
        if (!opens(granted, method, path)) {
            throw new EmbedException(ErrorCode.FORBIDDEN, 403, "insufficient_scope", "an embed token may only read views: this call is not in its scopes ("
                    + String.join(" ", granted) + ")");
        }
        String kind = kindOf(path);
        if (kind != null && !app.allowsKind(kind)) {
            throw new EmbedException(ErrorCode.EMBED_KIND, 400, "access_denied", "the host application may not show the kind '" + kind + "'");
        }
        long wait = rates.take("app:" + app.id(), app.callsPerMinute());
        if (wait == 0) {
            wait = rates.take("user:" + app.id() + ":" + u.username(), app.userCallsPerMinute());
        }
        if (wait > 0) {
            throw new EmbedException(ErrorCode.EMBED_RATE_LIMITED, 429, "slow_down", "over the embed call rate; retry in " + wait + " s", wait);
        }
        apps.touch(app.id());
        return new Authorized(new Principal(u.username(), List.copyOf(u.roles()), app.id()), app.id(), c.path("jti").asText(""), granted,
                Instant.ofEpochSecond(c.path("exp").asLong()));
    }

    private boolean opens(List<String> granted, String method, String path) {
        for (String s : granted) {
            for (String p : props.scopes().getOrDefault(s, List.of())) {
                int sp = p.indexOf(' ');
                String m = sp < 0 ? "*" : p.substring(0, sp);
                String pat = sp < 0 ? p : p.substring(sp + 1).trim();
                if (("*".equals(m) || m.equals(method)) && matcher.match(pat, path)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The entity kind a call is about ({@code /api/v1/views/{kind}/...}), or null. */
    static String kindOf(String path) {
        String prefix = "/api/v1/views/";
        if (!path.startsWith(prefix)) {
            return null;
        }
        String rest = path.substring(prefix.length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private String randomText(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
