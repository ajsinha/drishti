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
package com.ash.drishti.server.security;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.security.*}. Off by default for local development; production turns it on and supplies
 * the shared secret through the environment ({@code DRISHTI_TOKEN_SECRET}), never a tracked file.
 *
 * @param enabled require a signed bearer token on every {@code /api/**} call
 * @param secret HS256 key shared with the console (at least 32 bytes)
 * @param clockSkew tolerated clock difference when checking expiry
 * @param roles role name to what it may see and do
 * @param tokenReadPosts POST paths (ant patterns) that only read, so a personal API token may call them; every other non-GET
 *     request stays refused for a token (SEC-15)
 * @param tokenScopes the write scopes a personal API token may be given, by name: what each is for and the {@code METHOD path}
 *     patterns it opens ({@code *} for any method); a token with none of them only reads (TokenScopes)
 * @param tokenNever {@code METHOD path} patterns no token may ever call, whatever its scopes (minting tokens, users, sign-in,
 *     personal and collaboration state); every non-GET endpoint must be in {@code token-read-posts}, a scope or here
 * @param tokenWriteMaxDays longest life of a token that has a write scope, which must expire (default 90)
 * @param redact field names masked for roles without {@code raw}, on every path that shows or reads a value (Entitlements). An
 *     entry with dots ({@code lifecycle.timeline.description}) names a field by the end of its path (arrays are not a step)
 * @param maskCopies also replace, in the other text of the same document, exact copies of a masked field's value (default false)
 * @param maskCopiesMinLength shortest text value scrubbed as a copy; numbers need at least four digits
 * @param maskCopiesMaxNodes documents with more nodes than this are not scanned for copies (their masked fields are still masked)
 * @param maskCopiesMaxValues most distinct masked values scrubbed per document
 */
@ConfigurationProperties("drishti.security")
public record SecurityProperties(Boolean enabled, String secret, Duration clockSkew, Map<String, Role> roles, List<String> redact,
        List<String> tokenReadPosts, Map<String, TokenScope> tokenScopes, List<String> tokenNever, Integer tokenWriteMaxDays, Boolean maskCopies, Integer maskCopiesMinLength, Integer maskCopiesMaxNodes, Integer maskCopiesMaxValues) {

    public SecurityProperties {
        enabled = enabled != null && enabled;
        clockSkew = clockSkew == null ? Duration.ofSeconds(30) : clockSkew;
        roles = roles == null ? Map.of() : Map.copyOf(roles);
        redact = redact == null ? List.of() : List.copyOf(redact);
        tokenReadPosts = tokenReadPosts == null ? List.of() : List.copyOf(tokenReadPosts);
        tokenScopes = tokenScopes == null ? Map.of() : Map.copyOf(tokenScopes);
        tokenNever = tokenNever == null ? List.of() : List.copyOf(tokenNever);
        tokenWriteMaxDays = tokenWriteMaxDays == null ? 90 : Math.max(1, tokenWriteMaxDays);
        maskCopies = maskCopies != null && maskCopies;
        maskCopiesMinLength = maskCopiesMinLength == null ? 3 : Math.max(1, maskCopiesMinLength);
        maskCopiesMaxNodes = maskCopiesMaxNodes == null ? 50_000 : maskCopiesMaxNodes;
        maskCopiesMaxValues = maskCopiesMaxValues == null ? 64 : maskCopiesMaxValues;
    }

    /** A write scope of a personal API token: the words the account page shows and the requests it opens. */
    public record TokenScope(String description, List<String> allow) {
        public TokenScope {
            description = description == null ? "" : description;
            allow = allow == null ? List.of() : List.copyOf(allow);
        }
    }

    /** Whether a personal API token may POST to the path: it is on the {@code token-read-posts} allow-list. */
    public boolean tokenMayPost(String path) {
        var matcher = new org.springframework.util.AntPathMatcher();
        return tokenReadPosts.stream().anyMatch(pattern -> matcher.match(pattern, path));
    }

    /**
     * @param kinds entity kinds the role may open; {@code *} for all
     * @param raw sees every field: nothing in {@code redact} is masked for the role, anywhere
     * @param author may save Sutras from Studio (with governance on, a save is a proposal for review)
     * @param admin may manage users and read the audit log; also approves Sutras
     * @param approve may approve or reject proposed Sutras (never their own, with four-eyes on)
     * @param calc may use Calc: Python in the browser over what the role may open (PYTHON_CALC.md)
     * @param layout may customise layouts (layout mode, personal layouts); true unless set to false (viewer is)
     * @param collaborate may share, comment and mention; true unless set to false
     * @param compliance may place legal holds, export the collaboration record and read any share; false unless set
     */
    public record Role(List<String> kinds, Boolean raw, Boolean author, Boolean admin, Boolean approve, Boolean calc, Boolean layout, Boolean collaborate, Boolean compliance) {
        public Role {
            kinds = kinds == null ? List.of() : List.copyOf(kinds);
            raw = raw != null && raw;
            author = author != null && author;
            admin = admin != null && admin;
            approve = approve != null && approve;
            calc = calc != null && calc;
            layout = layout == null || layout;
            collaborate = collaborate == null || collaborate;
            compliance = compliance != null && compliance;
        }
    }
}
