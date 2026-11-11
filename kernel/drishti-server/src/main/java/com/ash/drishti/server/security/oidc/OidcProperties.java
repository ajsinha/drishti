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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Single sign-on with an OpenID Connect provider (W20). The console runs the browser flow; the server verifies the
 * ID token itself (signature against the provider's keys, issuer, audience, expiry, nonce) before it signs anyone in.
 *
 * @param enabled accept sign-ins from the provider
 * @param issuer the provider's issuer URL (its discovery document is {@code <issuer>/.well-known/openid-configuration})
 * @param clientId this application's client id: the ID token's audience
 * @param jwksUri the provider's key set, when it should not be discovered
 * @param usernameClaim the claim that becomes the Drishti user name ({@code preferred_username}, {@code email}, …)
 * @param displayClaim the claim shown as the user's name
 * @param groupsClaim the claim listing the user's groups or roles at the provider
 * @param roleMap provider group to Drishti roles
 * @param defaultRoles roles for a signed-in user whose groups map to nothing (empty: such users are refused)
 * @param rolesFromProvider the provider's groups replace the user's roles at every sign-in (else only on first sign-in)
 * @param algorithms signature algorithms accepted (never {@code none} or a shared-secret HMAC)
 * @param clockSkew tolerance for {@code exp}, {@code iat} and {@code nbf}
 */
@ConfigurationProperties("drishti.security.oidc")
public record OidcProperties(Boolean enabled, String issuer, String clientId, String jwksUri, String usernameClaim, String displayClaim,
        String groupsClaim, Map<String, List<String>> roleMap, List<String> defaultRoles, Boolean rolesFromProvider, Set<String> algorithms,
        Duration clockSkew) {

    public static final Set<String> SAFE_ALGORITHMS = Set.of("RS256", "RS384", "RS512", "PS256", "PS384", "PS512", "ES256", "ES384", "ES512");

    public OidcProperties {
        enabled = enabled != null && enabled;
        issuer = issuer == null ? "" : issuer.replaceAll("/+$", "");
        clientId = clientId == null ? "" : clientId;
        usernameClaim = usernameClaim == null || usernameClaim.isBlank() ? "preferred_username" : usernameClaim;
        displayClaim = displayClaim == null || displayClaim.isBlank() ? "name" : displayClaim;
        groupsClaim = groupsClaim == null || groupsClaim.isBlank() ? "groups" : groupsClaim;
        roleMap = roleMap == null ? Map.of() : Map.copyOf(roleMap);
        defaultRoles = defaultRoles == null ? List.of() : List.copyOf(defaultRoles);
        rolesFromProvider = rolesFromProvider == null || rolesFromProvider;
        algorithms = algorithms == null || algorithms.isEmpty() ? Set.of("RS256", "ES256") : Set.copyOf(algorithms);
        if (!SAFE_ALGORITHMS.containsAll(algorithms)) {
            throw new IllegalStateException("drishti.security.oidc.algorithms may only list " + SAFE_ALGORITHMS + ": " + algorithms);
        }
        clockSkew = clockSkew == null ? Duration.ofSeconds(60) : clockSkew;
    }
}
