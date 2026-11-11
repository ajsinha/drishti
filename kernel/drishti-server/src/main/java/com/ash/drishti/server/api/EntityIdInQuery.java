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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * An entity's id in the query instead of the path, for ids a path cannot carry: one with a {@code /} or {@code \} (the
 * server refuses them percent-encoded in a path, as the servlet container does, SEC-02), or one that is {@code .} or
 * {@code ..}. Every endpoint under {@code /api/v1} that names an entity as {@code {kind}/{id}} also answers with
 * {@code ~} in place of the id and the id as the {@code id} query parameter:
 * {@code GET /api/v1/views/trade/~?id=sl%2Fash-6} is the view of trade {@code sl/ash-6} (QA 2026-10-01, DATA-21).
 *
 * <p>The id is swapped into the path variables after the handler is chosen and before its arguments are read, so the
 * handler, entitlements and the access log ({@link AccessRecorder}) all see the real id. {@code ~} without an
 * {@code id} parameter is the id {@code ~} itself.
 */
@Configuration
public class EntityIdInQuery implements WebMvcConfigurer, HandlerInterceptor {

    /** The path segment that stands for "the id is in the query". */
    public static final String MARK = "~";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/**").order(Ordered.HIGHEST_PRECEDENCE);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object attr = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attr instanceof Map<?, ?> vars) || !vars.containsKey("kind") || !MARK.equals(vars.get("id"))) {
            return true;
        }
        String id = request.getParameter("id");
        if (id == null || id.isEmpty()) {
            return true;
        }
        Map<String, Object> swapped = new LinkedHashMap<>();
        vars.forEach((k, v) -> swapped.put(String.valueOf(k), v));
        swapped.put("id", id);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, swapped);
        return true;
    }
}
