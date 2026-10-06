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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.EmbedAppStore;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Embedded views, server side: the token exchange a host's backend calls ({@code POST /api/v1/embed/token}, RFC 8693, answered in
 * RFC 6749 section 5.2 form), the public key set, the call the console makes to re-check a token, the origins the console allows
 * for CORS, and the administrators' registry of host applications. Every call of an embed token is checked by
 * {@link EmbedTokenService#authorize} in the token filter.
 */
@RestController
public class EmbedController {

    /** {@code POST /api/v1/admin/embed/apps/{id}/rotate-secret}: how long the old secret keeps working (seconds, default 3600). */
    public record Rotation(Long graceSeconds) {}

    private final EmbedTokenService tokens;
    private final EmbedAppStore apps;
    private final Entitlements entitlements;

    public EmbedController(EmbedTokenService tokens, EmbedAppStore apps, Entitlements entitlements) {
        this.tokens = tokens;
        this.apps = apps;
        this.entitlements = entitlements;
    }

    /** The exchange. Public in the sense of the token filter (the host authenticates in the request itself). */
    @PostMapping(value = "/api/v1/embed/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Object> token(HttpServletRequest req, @RequestParam Map<String, String> form) {
        String basicId = null;
        String basicSecret = null;
        String auth = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String[] up = new String(Base64.getDecoder().decode(auth.substring(6).trim()), StandardCharsets.UTF_8).split(":", 2);
                basicId = up[0];
                basicSecret = up.length > 1 ? up[1] : "";
            } catch (IllegalArgumentException e) {
                return refusal(new EmbedException(ErrorCode.EMBED_APP, 401, "invalid_client", "malformed Basic credentials"));
            }
        }
        try {
            EmbedTokenService.Response out = tokens.exchange(new EmbedTokenService.Request(basicId, basicSecret, form.get("client_assertion_type"),
                    form.get("client_assertion"), form.get("grant_type"), form.get("subject_token"), form.get("subject_token_type"),
                    form.get("audience"), form.get("scope"), req.getHeader(HttpHeaders.ORIGIN)));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(out);
        } catch (EmbedException e) {
            return refusal(e);
        }
    }

    private static ResponseEntity<Object> refusal(EmbedException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.error());
        body.put("error_description", e.getMessage());
        body.put("code", e.errorCode().code());
        ResponseEntity.BodyBuilder b = ResponseEntity.status(e.status()).cacheControl(CacheControl.noStore());
        if (e.status() == 401) {
            b.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"drishti-embed\"");
        }
        if (e.retryAfter() > 0) {
            b.header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfter()));
        }
        return b.body(body);
    }

    /** The server's public key for embed tokens, for the console. */
    @GetMapping("/api/v1/embed/jwks")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePublic()).body(tokens.keys().jwks());
    }

    /** What the console asks every {@code embed.recheck_seconds} for a stream it holds open: the token passed every check just now. */
    @GetMapping("/api/v1/embed/check")
    public Map<String, Object> check(HttpServletRequest req, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Object g = req.getAttribute(EmbedTokenService.GRANT_ATTRIBUTE);
        if (!(g instanceof EmbedTokenService.Authorized a)) {
            throw new DrishtiException(ErrorCode.EMBED_TOKEN_INVALID, "this call carries no embed token");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("app", a.app());
        out.put("user", p.user());
        out.put("jti", a.jti());
        out.put("scopes", a.scopes());
        out.put("expiresAt", a.expiresAt());
        out.put("expiresIn", Math.max(0, a.expiresAt().getEpochSecond() - java.time.Instant.now().getEpochSecond()));
        return out;
    }

    /** The origins of every enabled host application: the console's CORS allow-list (service identity only). */
    @GetMapping("/api/v1/embed/apps/origins")
    public Map<String, Object> origins(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireService(p);
        return Map.of("origins", tokens.enabled() ? apps.enabledOrigins() : List.of());
    }

    // ---- the registry (administrators) -------------------------------------------------------------------------------------

    @GetMapping("/api/v1/admin/embed/apps")
    public List<EmbedAppStore.App> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return apps.all();
    }

    @GetMapping("/api/v1/admin/embed/apps/{id}")
    public EmbedAppStore.App one(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return apps.find(id).orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no host application '" + id + "'"));
    }

    /** Registers an application; the client secret is in this answer only. */
    @PostMapping("/api/v1/admin/embed/apps")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public EmbedAppStore.Created create(@RequestBody EmbedAppStore.Draft draft, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return apps.create(checked(draft), p.user());
    }

    @PutMapping("/api/v1/admin/embed/apps/{id}")
    public EmbedAppStore.App update(@PathVariable String id, @RequestBody EmbedAppStore.Draft draft, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return apps.update(id, checked(draft), p.user());
    }

    @PostMapping("/api/v1/admin/embed/apps/{id}/rotate-secret")
    public EmbedAppStore.Created rotate(@PathVariable String id, @RequestBody(required = false) Rotation body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return apps.rotateSecret(id, body == null || body.graceSeconds() == null ? 3600 : Math.max(0, body.graceSeconds()), p.user());
    }

    @DeleteMapping("/api/v1/admin/embed/apps/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        apps.delete(id, p.user());
    }

    /** Scopes must be ones this server defines; the key set must be a valid JWK set. */
    private EmbedAppStore.Draft checked(EmbedAppStore.Draft d) {
        if (d.scopes() != null) {
            for (String s : d.scopes()) {
                if (!tokens.properties().scopes().containsKey(s.trim())) {
                    throw new DrishtiException(ErrorCode.BAD_REQUEST, "unknown scope '" + s + "'; choose from " + tokens.properties().scopes().keySet().stream().sorted().toList());
                }
            }
        }
        if (d.jwks() != null && !d.jwks().isBlank()) {
            try {
                Jws.keys(d.jwks());
            } catch (IllegalArgumentException e) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "jwks: " + e.getMessage());
            }
        }
        return d;
    }
}
