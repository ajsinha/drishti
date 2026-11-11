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
package com.ash.drishti.server.bi;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * "View as readers see it": a request to a BI endpoint that carries {@code as=published} reads the published version of every item it opens, even for an author
 * who would otherwise be given the working draft (see {@link BiService#bodyFor}). The data is still drawn as the person asking, so the masks and row rules of the
 * author stand in for the reader's. The flag lives for the one request thread and is cleared when the request ends; it never changes what is stored.
 */
@Configuration
public class BiReaderView implements WebMvcConfigurer {

    /** The query parameter that asks for the published version. */
    public static final String PARAM = "as";
    public static final String PUBLISHED = "published";
    private static final ThreadLocal<Boolean> ON = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** True while the current request asked for the published version. */
    public static boolean published() {
        return ON.get();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                ON.set(PUBLISHED.equals(request.getParameter(PARAM)));
                return true;
            }

            @Override
            public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
                ON.remove();
            }
        }).addPathPatterns("/api/v1/bi/**");
    }
}
