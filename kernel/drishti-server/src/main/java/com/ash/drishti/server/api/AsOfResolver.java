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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.engine.time.BusinessDates;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Gives any controller parameter of type {@link AsOf} the request's business date: the {@code X-Drishti-As-Of}
 * header (the console sends the date the user picked), else the {@code asOf} query parameter, else the current
 * business date. {@code X-Drishti-Known-At} / {@code knownAt} ask for data as known at an instant. The result is
 * always concrete (a business date is set) and has been checked against the history window.
 */
@Configuration
public class AsOfResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    public static final String HEADER = "X-Drishti-As-Of";
    public static final String KNOWN_AT_HEADER = "X-Drishti-Known-At";
    private final BusinessDates dates;

    public AsOfResolver(BusinessDates dates) {
        this.dates = dates;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == AsOf.class;
    }

    @Override
    public AsOf resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request, WebDataBinderFactory binders) {
        return read(request.getHeader(HEADER), request.getParameter("asOf"), request.getHeader(KNOWN_AT_HEADER), request.getParameter("knownAt"),
                dates);
    }

    /**
     * A request's date box: the header, else the query parameter, for the business date and for "known at" (one reading for the REST
     * paths and for the person of a typed kernel call).
     */
    public static AsOf read(String dateHeader, String dateParam, String knownAtHeader, String knownAtParam, BusinessDates dates) {
        return dates.parse(first(dateHeader, dateParam), first(knownAtHeader, knownAtParam));
    }

    private static String first(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
