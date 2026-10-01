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
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
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
 * secret is in that answer only. A token reads as its user and never writes. Administrators see and revoke everyone's.
 */
@RestController
public class ApiTokenController {

    /** {"name": "Risk notebook", "days": 90}; days blank for no expiry. */
    public record NewToken(String name, Integer days) {}

    private final ApiTokenStore tokens;
    private final Entitlements entitlements;

    public ApiTokenController(ApiTokenStore tokens, Entitlements entitlements) {
        this.tokens = tokens;
        this.entitlements = entitlements;
    }

    @GetMapping("/api/v1/me/tokens")
    public List<ApiTokenStore.TokenView> mine(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return tokens.of(p.user());
    }

    @PostMapping("/api/v1/me/tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiTokenStore.Created create(@RequestBody NewToken req, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return tokens.create(p.user(), req.name(), req.days());
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
