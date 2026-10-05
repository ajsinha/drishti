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
import com.ash.drishti.identity.ApiTokenStore;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.SecurityProperties;
import com.ash.drishti.server.security.TokenScopes;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Personal API tokens: a user makes one for a script, a notebook or a spreadsheet ({@code POST /api/v1/me/tokens}); the
 * secret is in that answer only. A token reads as its user, and writes only within the scopes it was made with (TokenScopes). Administrators see and revoke everyone's.
 */
@RestController
public class ApiTokenController {

    /** {"name": "Risk notebook", "days": 90, "scopes": ["design:write"]}; days blank for no expiry (read tokens only); scopes blank for read. */
    public record NewToken(String name, Integer days, List<String> scopes) {}

    /** What the account page offers: the scopes an administrator defined, and the longest life of a write token. */
    public record ScopeInfo(String name, String description) {}

    public record ScopeChoices(List<ScopeInfo> scopes, int writeMaxDays) {}

    private final ApiTokenStore tokens;
    private final Entitlements entitlements;
    private final UserService users;
    private final SecurityProperties props;
    private final TokenScopes scopes;

    public ApiTokenController(ApiTokenStore tokens, Entitlements entitlements, UserService users, SecurityProperties props) {
        this.props = props;
        this.scopes = new TokenScopes(props);
        this.tokens = tokens;
        this.entitlements = entitlements;
        this.users = users;
    }

    @GetMapping("/api/v1/me/tokens")
    public List<ApiTokenStore.TokenView> mine(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return tokens.of(p.user());
    }

    @GetMapping("/api/v1/me/tokens/scopes")
    public ScopeChoices scopeChoices() {
        return new ScopeChoices(props.tokenScopes().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .map(e -> new ScopeInfo(e.getKey(), e.getValue().description())).toList(), props.tokenWriteMaxDays());
    }

    @PostMapping("/api/v1/me/tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiTokenStore.Created create(@RequestBody NewToken req, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        // a disabled user makes no tokens, whatever a token still in flight says (QA 2026-10-01 SEC-01)
        if (users.find(p.user()).filter(u -> !u.enabled()).isPresent()) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "the account '" + p.user() + "' is disabled");
        }
        List<String> chosen = req.scopes() == null ? List.of() : req.scopes().stream().filter(x -> x != null && !x.isBlank())
                .map(String::trim).distinct().toList();
        for (String s : chosen) {
            if (!scopes.known(s)) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "unknown scope '" + s + "'; choose from read" + props.tokenScopes().keySet().stream().sorted().map(k -> ", " + k).reduce("", String::concat));
            }
        }
        List<String> write = chosen.stream().filter(s -> !TokenScopes.READ.equals(s)).toList();
        if (!write.isEmpty() && (req.days() == null || req.days() > props.tokenWriteMaxDays())) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a token with a write scope must expire within " + props.tokenWriteMaxDays() + " days");
        }
        return tokens.create(p.user(), req.name(), req.days(), write);
    }

    @DeleteMapping("/api/v1/me/tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeMine(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if (!tokens.revoke(id, p.user(), p.user())) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "no active token " + id + " of yours");
        }
    }

    @GetMapping("/api/v1/admin/tokens")
    public List<ApiTokenStore.TokenView> all(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return tokens.all();
    }

    @DeleteMapping("/api/v1/admin/tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        if (!tokens.revoke(id, null, p.user())) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "no active token " + id);
        }
    }
}
