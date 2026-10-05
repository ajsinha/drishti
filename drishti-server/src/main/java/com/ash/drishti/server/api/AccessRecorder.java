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

import com.ash.drishti.identity.AccessLog;
import com.ash.drishti.server.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Records every read of data that succeeded into the access log: views, raw documents, history, searches and CSV
 * exports, with who, what and which business date. After the response, never in its way. The console's own service
 * identity is not recorded (it only verifies sign-ins). {@code drishti.access-log.enabled=false} turns it off.
 */
@Configuration
public class AccessRecorder implements WebMvcConfigurer, HandlerInterceptor {

    /** Route pattern to the action recorded. */
    static final Map<String, String> ACTIONS = Map.ofEntries(
            Map.entry("/api/v1/views/{kind}/{id}", "view"),                    // its live stream is the same look: not recorded again
            Map.entry("/api/v1/views/{kind}/{id}/panels/{panel}/records", "view"),   // a panel's rows for its Pivot tab
            Map.entry("/api/v1/entities/{kind}/{id}/raw", "raw"),
            Map.entry("/api/v1/history/{kind}/{id}/diff", "history"),
            Map.entry("/api/v1/history/{kind}/{id}/series", "history"),
            Map.entry("/api/v1/search", "search"),
            Map.entry("/api/v1/search/compare", "search"),
            Map.entry("/api/v1/search/columns/{kind}", "search"),              // Calc's drishti.columns(): the fields read are the detail
            Map.entry("/api/v1/search/pivot/{kind}", "search"),                // a search's Pivot tab, and its drill-downs
            Map.entry("/api/v1/search/pivot/{kind}/drill", "search"),
            Map.entry("/api/v1/search/csv", "export"));

    /** A view opened through a share's link carries this header: the access-log row says {@code share:sh_...}, and the recipient's open is recorded. */
    public static final String SHARE_HEADER = "X-Drishti-Share";

    private final ObjectProvider<AccessLog> log;
    private final boolean enabled;
    private final ObjectProvider<com.ash.drishti.server.collab.ShareService> shares;

    public AccessRecorder(ObjectProvider<AccessLog> log, @Value("${drishti.access-log.enabled:true}") boolean enabled,
            ObjectProvider<com.ash.drishti.server.collab.ShareService> shares) {
        this.log = log;
        this.enabled = enabled;
        this.shares = shares;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (enabled) {
            registry.addInterceptor(this).addPathPatterns("/api/v1/views/**", "/api/v1/entities/**", "/api/v1/history/**", "/api/v1/search/**",
                    "/api/v1/search");
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (ex != null || response.getStatus() / 100 != 2) {
            return;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String action = pattern == null ? null : ACTIONS.get(pattern.toString());
        Principal who = request.getAttribute(Principal.ATTRIBUTE) instanceof Principal p ? p : null;
        AccessLog sink = log.getIfAvailable();
        if (action == null || who == null || who.roles().contains("service") || sink == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String kind = vars == null ? null : vars.get("kind");
        String id = vars == null ? null : vars.get("id");
        String detail = action.equals("history") ? request.getParameter("path")
                : request.getParameter("q") != null ? request.getParameter("q") : request.getParameter("paths");
        String date = request.getHeader(AsOfResolver.HEADER);
        if (date == null || date.isBlank()) {
            date = request.getParameter("asOf");
        }
        String share = request.getHeader(SHARE_HEADER);
        if ("view".equals(action) && share != null && com.ash.drishti.identity.collab.Ulid.valid(share.trim(), "sh_")) {
            detail = "share:" + share.trim();
            com.ash.drishti.server.collab.ShareService svc = shares.getIfAvailable();
            if (svc != null) {
                svc.opened(share.trim(), who.user());
            }
        }
        if (kind == null && detail != null) {                     // a search: its mnemonic or kind is the first word
            kind = detail.trim().split("\\s+", 2)[0];
        }
        sink.record(new AccessLog.Event(Instant.now(), who.user(), action, kind, id, detail, date == null || date.isBlank() ? null : date));
    }
}
