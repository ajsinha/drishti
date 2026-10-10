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

import com.ash.drishti.server.RetryService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What is failing and being retried, and "Retry now" (docs/architecture/OPERATIONS.md, Retries). Admin only; a personal API
 * token needs the {@code packs:admin} scope for the POST. Every retry-now is audited.
 */
@RestController
@RequestMapping("/api/v1/admin/retry")
public class RetryController {

    private final RetryService retry;
    private final Entitlements entitlements;

    public RetryController(RetryService retry, Entitlements entitlements) {
        this.retry = retry;
        this.entitlements = entitlements;
    }

    /** Every failing pack, connector, AI provider and MCP server: attempt, seconds to the next retry, last error. */
    @GetMapping
    public Map<String, Object> status(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return retry.snapshot();
    }

    /** {@code area}: packs, connectors, ai, mcp or all; {@code name}: one connector (optional). */
    @PostMapping("/{area}")
    public Map<String, Object> retryNow(@PathVariable String area, @RequestParam(required = false) String name,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return retry.retryNow(area, name, p.user());
    }
}
