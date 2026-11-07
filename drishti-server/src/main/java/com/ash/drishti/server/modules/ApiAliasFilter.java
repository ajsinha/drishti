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

import com.ash.drishti.server.security.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Serves the old API prefixes the modules list ({@link ApiAlias}) as their new ones, before every other filter: the request is the same
 * request under the new path from here on (the token's scopes, the engine gate, the controllers and the audit see the new path), and the
 * answer carries {@code Deprecation: true} and {@code Link: <new path>; rel="successor-version"}. Each old prefix is logged once.
 * Generic: the core names no module's path; the aliases are the modules' beans.
 */
public final class ApiAliasFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(ApiAliasFilter.class);

    private final List<ApiAlias> aliases;
    private final Set<String> told = ConcurrentHashMap.newKeySet();

    public ApiAliasFilter(List<ApiAlias> aliases) {
        this.aliases = List.copyOf(aliases);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return aliases.isEmpty() || !RequestPaths.routed(request).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String routed = RequestPaths.routed(req);
        for (ApiAlias a : aliases) {
            String to = a.rewrite(routed);
            if (to != null) {
                if (told.add(a.from())) {
                    LOG.info("{} is the old path of {}: answered for this release, then removed; move clients to the new path", a.from(), a.to());
                }
                res.setHeader("Deprecation", "true");
                res.setHeader("Link", "<" + req.getContextPath() + to + ">; rel=\"successor-version\"");
                chain.doFilter(new Moved(req, to), res);
                return;
            }
        }
        chain.doFilter(req, res);
    }

    /** The request under its new path. */
    static final class Moved extends HttpServletRequestWrapper {

        private final String path;

        Moved(HttpServletRequest req, String path) {
            super(req);
            this.path = path;
        }

        @Override
        public String getRequestURI() {
            return getContextPath() + path;
        }

        @Override
        public StringBuffer getRequestURL() {
            HttpServletRequest r = (HttpServletRequest) getRequest();
            StringBuffer u = new StringBuffer(r.getScheme()).append("://").append(r.getServerName());
            int port = r.getServerPort();
            if (port > 0 && !("http".equals(r.getScheme()) && port == 80) && !("https".equals(r.getScheme()) && port == 443)) {
                u.append(':').append(port);
            }
            return u.append(getRequestURI());
        }

        @Override
        public String getServletPath() {
            return path;
        }

        @Override
        public String getPathInfo() {
            return null;
        }
    }
}
