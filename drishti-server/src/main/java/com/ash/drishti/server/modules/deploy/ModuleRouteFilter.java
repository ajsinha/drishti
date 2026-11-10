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
package com.ash.drishti.server.modules.deploy;

import com.ash.drishti.server.security.RequestPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The gate of deployed modules' APIs (KERNEL_AND_MODULES.md sections 12.2 step 1 and 12.10), after placement's guard: a removed module's
 * {@code /api/v1/<prefix>/…} answers 410 Gone {@code DRS-1028} with {@code Cache-Control: no-store}; a switched-off one 404
 * {@code DRS-1025}; a request to a module swapping versions on this node waits for the swap (at most {@code waitMillis}); every request
 * that goes in is counted, so an unload drains it.
 */
public final class ModuleRouteFilter extends OncePerRequestFilter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ModuleNode node;
    private final long waitMillis;

    public ModuleRouteFilter(ModuleNode node, long waitMillis) {
        this.node = node;
        this.waitMillis = waitMillis;
    }

    private Optional<ModuleNode.Entry> entry(HttpServletRequest req) {
        String path = RequestPaths.routed(req);
        if (!path.startsWith("/api/v1/")) {
            return Optional.empty();
        }
        String rest = path.substring("/api/v1/".length());
        String prefix = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
        return node.byPrefix(prefix);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return entry(request).isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        ModuleNode.Entry e = entry(req).orElseThrow();
        if (e.state() == ModuleNode.State.REMOVED) {
            problem(res, 410, "DRS-1028", "module not installed", e.title() + " (" + e.id() + ") is no longer installed on this system; saved work"
                    + " that used it is kept and comes back when it is reinstalled", e);
            return;
        }
        if (e.state() == ModuleNode.State.OFF) {
            problem(res, 404, "DRS-1025", "module off", "the module '" + e.id() + "' is switched off: ask your administrator", e);
            return;
        }
        if (node.swapping(e.id()) && !node.awaitSwap(e.id(), waitMillis)) {
            problem(res, 503, "DRS-1032", "module switching", e.title() + " is switching versions on this node; try again in a moment", e);
            return;
        }
        node.enter(e.id());
        try {
            chain.doFilter(req, res);
        } finally {
            node.leave(e.id());
        }
    }

    private static void problem(HttpServletResponse res, int status, String code, String title, String detail, ModuleNode.Entry e)
            throws IOException {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "about:blank");
        p.put("title", title);
        p.put("status", status);
        p.put("detail", code + " " + detail);
        p.put("code", code);
        p.put("module", e.id());
        res.setStatus(status);
        res.setHeader("Cache-Control", "no-store");
        res.setContentType("application/problem+json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(JSON.writeValueAsString(p));
    }
}
