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
package com.ash.drishti.server.modules;

import com.ash.drishti.registry.AccessRegistry;
import com.ash.drishti.registry.Subject;
import com.ash.drishti.server.security.Principal;
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
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The API of a module that is not loaded here answers {@code 404 DRS-1025} in words, and the API of a function that is off (its module off,
 * or switched off by an administrator) {@code 404 DRS-1026}; a function the person may not use answers {@code 403 DRS-1027} (everyone may,
 * until per-person grants in 2.9.11.i). The API of a module (docs/architecture/ACCESS_MODEL.md section 2: "404
 * module not licensed" for a module that is off), never a bare 404 and never 403: nothing is forbidden, the module is not here. The
 * module's prefixes come from its manifest; whether it was loaded is decided at start.
 */
public final class ModuleGateFilter extends OncePerRequestFilter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AccessRegistry registry;
    private final Predicate<String> loaded;

    /**
     * @param registry the access registry (which module answers a path, and why it is off)
     * @param loaded   whether a module's code was loaded at this server's start
     */
    public ModuleGateFilter(AccessRegistry registry, Predicate<String> loaded) {
        this.registry = registry;
        this.loaded = loaded;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RequestPaths.routed(request).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String path = RequestPaths.routed(req);
        Optional<String> module = registry.moduleOfApi(path);
        if (module.isEmpty() || loaded.test(module.get())) {
            Optional<String> function = registry.functionOfApi(path);
            if (function.isEmpty()) {
                chain.doFilter(req, res);
                return;
            }
            String w = function.get();
            Optional<String> off = registry.functionOffReason(w);
            if (off.isPresent()) {
                problem(res, 404, "DRS-1026", "function off", "the function " + w + " is not on here: " + off.get(), "function", w);
                return;
            }
            Principal p = req.getAttribute(Principal.ATTRIBUTE) instanceof Principal x ? x : null;
            if (p != null && !registry.functionAllowed(new Subject(p.user(), Set.of(), Set.copyOf(p.roles()), false), w)) {
                problem(res, 403, "DRS-1027", "function not allowed", w + " is not available to you: ask your administrator", "function", w);
                return;
            }
            chain.doFilter(req, res);
            return;
        }
        String id = module.get();
        String why = registry.module(id).map(s -> s.on() ? "it was off when this server started; it loads at the next start" : s.reason())
                .orElse("it is not installed");
        String how = " (config/modules/" + id + ".yaml or DRISHTI_MODULES_" + id.toUpperCase(java.util.Locale.ROOT).replace('-', '_')
                + "_ENABLED, then a restart)";
        problem(res, 404, "DRS-1025", "module off", "the module '" + id + "' is not on here: " + why + how, "module", id);
    }

    private static void problem(HttpServletResponse res, int status, String code, String title, String detail, String key, String value)
            throws IOException {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "about:blank");
        p.put("title", title);
        p.put("status", status);
        p.put("detail", code + " " + detail);
        p.put("code", code);
        p.put(key, value);
        res.setStatus(status);
        res.setContentType("application/problem+json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(JSON.writeValueAsString(p));
    }
}
