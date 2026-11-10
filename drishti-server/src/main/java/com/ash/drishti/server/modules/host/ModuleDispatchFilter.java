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
package com.ash.drishti.server.modules.host;

import com.ash.drishti.module.runtime.RouteTable;
import com.ash.drishti.server.security.RequestPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Request dispatch to the loaded modules (docs/architecture/KERNEL_AND_MODULES.md sections 4.5 and 9): the live route table answers every
 * request under a module's namespace before the kernel's own dispatcher sees it. A module's API ({@code /api/v1/<prefix>/...}) goes to the
 * module's own controllers, in its child context; a module's page is read through {@code /api/v1/modules/pages/<id>/<path>} (the console's
 * {@code /m/<id>/<path>}). A route that moved answers 308 with its new path, a module switched off 404, a removed one 410 (its tombstone);
 * a path no module holds goes on to the kernel. Runs after the caller is known.
 */
public final class ModuleDispatchFilter extends OncePerRequestFilter {

    /** Where the kernel serves a module's page: the console's {@code /m/<id>/<path>} under the API. */
    public static final String PAGES = "/api/v1/modules/pages";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final KernelModuleHost host;

    public ModuleDispatchFilter(KernelModuleHost host) {
        this.host = host;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !host.hasRoutes() || !RequestPaths.routed(request).startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String routed = RequestPaths.routed(req);
        boolean page = routed.startsWith(PAGES + "/");
        String path = page ? "/m" + routed.substring(PAGES.length()) : routed;
        Optional<RouteTable.Match> match = host.routes().lookup(path);
        if (match.isEmpty()) {
            if (page) {
                problem(res, 404, "DRS-1025", "no such page", "no loaded module answers " + path, path);
                return;
            }
            chain.doFilter(req, res);
            return;
        }
        RouteTable.Match m = match.get();
        RouteTable.Route r = m.route();
        switch (m.status()) {
            case 404 -> problem(res, 404, "DRS-1025", "module off", "the module '" + r.module() + "' is switched off here", path);
            case 410 -> problem(res, 410, "DRS-1028", "module removed", "the module '" + r.module() + "' was removed from this server; work that "
                    + "names it needs module " + r.module() + " again", path);
            case 308 -> {
                String to = host.routes().snapshot().of(r.module()).stream().filter(x -> x.id().equals(r.target())).findFirst()
                        .map(RouteTable.Route::path).orElse(null);
                if (to == null) {
                    problem(res, 404, "DRS-1025", "moved nowhere", "route " + r.id() + " moved to " + r.target() + ", which " + r.module()
                            + " does not have", path);
                    return;
                }
                res.setStatus(308);
                res.setHeader("Location", page ? PAGES + to.substring(2) : to);
            }
            default -> {
                if (r.kind() == RouteTable.Kind.API) {
                    if (!host.dispatch(r.module(), req, res)) {
                        problem(res, 404, "DRS-1025", "no such endpoint", "the module '" + r.module() + "' has no endpoint " + req.getMethod()
                                + " " + path, path);
                    }
                } else if (r.kind() == RouteTable.Kind.PAGE && page) {
                    servePage(res, r, path);
                } else {
                    chain.doFilter(req, res);
                }
            }
        }
    }

    private void servePage(HttpServletResponse res, RouteTable.Route r, String path) throws IOException {
        Path file = host.pageFile(r.module(), r.target()).orElse(null);
        if (file == null || !Files.isRegularFile(file)) {
            problem(res, 404, "DRS-1025", "no such page", "the module '" + r.module() + "' has no page " + r.target(), path);
            return;
        }
        res.setStatus(200);
        res.setContentType("text/html;charset=UTF-8");
        res.setHeader("X-Drishti-Module", r.module());
        res.setHeader("X-Drishti-Route", r.id());
        res.getOutputStream().write(Files.readString(file, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));
    }

    private static void problem(HttpServletResponse res, int status, String code, String title, String detail, String path) throws IOException {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "about:blank");
        p.put("title", title);
        p.put("status", status);
        p.put("detail", code + " " + detail);
        p.put("code", code);
        p.put("path", path);
        res.setStatus(status);
        res.setContentType("application/problem+json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(JSON.writeValueAsString(p));
    }
}
