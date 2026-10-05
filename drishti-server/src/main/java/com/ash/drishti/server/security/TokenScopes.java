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

import java.util.Collection;
import java.util.List;
import org.springframework.util.AntPathMatcher;

/**
 * The one place that decides what a personal API token may call. A token always reads (GET, HEAD and the allow-listed
 * read-only POSTs); anything else needs a scope whose {@code METHOD path} patterns (configuration:
 * {@code drishti.security.token-scopes}) cover the request, and no write in {@code token-never} is ever allowed. What a
 * scope opens is only the door: the controller behind it still checks the roles its user holds now, so a token never
 * exceeds its user. A request no scope covers is refused, so a new endpoint is closed to tokens until it is declared.
 */
public final class TokenScopes {

    /** The scope every token has: reads only. */
    public static final String READ = "read";

    /** The outcome for one request. */
    public enum Verdict { ALLOWED, NEEDS_SCOPE, NEVER }

    private final SecurityProperties props;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public TokenScopes(SecurityProperties props) {
        this.props = props;
    }

    /** Whether the request only reads, so a token needs no scope for it. */
    public boolean reads(String method, String path) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)
                || "POST".equals(method) && props.tokenMayPost(path);
    }

    /** Whether a scope name is one an administrator has defined, or {@code read}. */
    public boolean known(String scope) {
        return READ.equals(scope) || props.tokenScopes().containsKey(scope);
    }

    /** Whether the endpoint is declared: a read, in {@code token-never}, or opened by some scope. Used by the guard test. */
    public boolean declared(String method, String path) {
        return reads(method, path) || matches(props.tokenNever(), method, path)
                || props.tokenScopes().values().stream().anyMatch(s -> matches(s.allow(), method, path));
    }

    public Verdict check(Collection<String> granted, String method, String path) {
        if (reads(method, path)) {
            return Verdict.ALLOWED;
        }
        if (matches(props.tokenNever(), method, path)) {
            return Verdict.NEVER;
        }
        for (String g : granted) {
            SecurityProperties.TokenScope s = props.tokenScopes().get(g);
            if (s != null && matches(s.allow(), method, path)) {
                return Verdict.ALLOWED;
            }
        }
        return Verdict.NEEDS_SCOPE;
    }

    /** The scopes that would open the request, for the error message. */
    public List<String> opening(String method, String path) {
        return props.tokenScopes().entrySet().stream().filter(e -> matches(e.getValue().allow(), method, path))
                .map(java.util.Map.Entry::getKey).sorted().toList();
    }

    private boolean matches(List<String> patterns, String method, String path) {
        for (String p : patterns) {
            int sp = p.indexOf(' ');
            String m = sp < 0 ? "*" : p.substring(0, sp);
            String pat = sp < 0 ? p : p.substring(sp + 1).trim();
            if (("*".equals(m) || m.equals(method)) && matcher.match(pat, path)) {
                return true;
            }
        }
        return false;
    }
}
