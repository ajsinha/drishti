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
package com.ash.drishti.server.api;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.User;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.identity.UserView;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.SecurityProperties;
import com.ash.drishti.server.security.oidc.IdTokenVerifier;
import com.ash.drishti.server.security.oidc.OidcProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Single sign-on (W20): the console hands over the ID token it received from the provider, with the nonce it sent;
 * the server verifies the token itself, maps the provider's groups to Drishti roles and signs the user in. Only the
 * console's service identity may call this.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class OidcController {

    /** @param idToken the provider's ID token @param nonce the nonce the console put in the authorization request */
    public record OidcSignIn(String idToken, String nonce) {}

    private static final Logger LOG = LoggerFactory.getLogger(OidcController.class);
    private final OidcProperties props;
    private final IdTokenVerifier verifier;
    private final UserService users;
    private final Entitlements entitlements;
    private final SecurityProperties security;

    public OidcController(OidcProperties props, IdTokenVerifier verifier, UserService users, Entitlements entitlements, SecurityProperties security) {
        this.props = props;
        this.verifier = verifier;
        this.users = users;
        this.entitlements = entitlements;
        this.security = security;
    }

    @PostMapping("/oidc")
    public UserView signIn(@RequestBody OidcSignIn req, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireService(p);
        if (!props.enabled()) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "single sign-on is not enabled on this server");
        }
        JsonNode claims;
        try {
            claims = verifier.verify(req.idToken(), req.nonce());
        } catch (DrishtiException e) {
            LOG.info("single sign-on refused: {}", e.getSuppressed().length > 0 ? e.getSuppressed()[0].getMessage() : e.getMessage());
            users.recordAudit("system", "login-sso-refused", "", e.getSuppressed().length > 0 ? e.getSuppressed()[0].getMessage() : "");
            throw e;
        }
        String username = firstText(claims, props.usernameClaim(), "email", "sub");
        Set<String> roles = roles(claims);
        if (roles.isEmpty()) {
            users.recordAudit("system", "login-sso-refused", username, "no Drishti role for the provider's groups");
            throw new DrishtiException(ErrorCode.BAD_CREDENTIALS, "your account has no role in Drishti; ask an administrator");
        }
        User u = users.federated(username, firstText(claims, props.displayClaim(), "name"), claims.path("email").asText(""), roles,
                props.rolesFromProvider(), props.issuer());
        return UserView.of(u, Instant.now());
    }

    /** The provider's groups mapped to known Drishti roles; the default roles when none map. */
    Set<String> roles(JsonNode claims) {
        Set<String> out = new LinkedHashSet<>();
        for (String g : groups(at(claims, props.groupsClaim()))) {
            props.roleMap().getOrDefault(g, List.of()).forEach(out::add);
        }
        if (out.isEmpty()) {
            out.addAll(props.defaultRoles());
        }
        out.removeIf(r -> {
            boolean unknown = security.enabled() && !users.knownRoles().contains(r);
            if (unknown) {
                LOG.warn("drishti.security.oidc.role-map names role '{}', which is neither a built-in role nor one defined in Admin → Roles", r);
            }
            return unknown;
        });
        return out;
    }

    private static List<String> groups(JsonNode n) {
        if (n.isArray()) {
            List<String> out = new java.util.ArrayList<>();
            n.forEach(x -> out.add(x.asText()));
            return out;
        }
        return n.isTextual() ? List.of(n.asText().split("[ ,]+")) : List.of();
    }

    /** A claim by dotted path ({@code realm_access.roles}). */
    private static JsonNode at(JsonNode claims, String path) {
        JsonNode n = claims;
        for (String part : path.split("\\.")) {
            n = n.path(part);
        }
        return n;
    }

    private static String firstText(JsonNode claims, String... names) {
        for (String n : names) {
            String v = at(claims, n).asText("");
            if (!v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
