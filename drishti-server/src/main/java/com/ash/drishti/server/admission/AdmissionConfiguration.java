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
package com.ash.drishti.server.admission;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.security.Principal;
import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/** Busy protection (docs/admin/OPERATIONS.md, "Busy protection"): the gate, the counter of requests being answered. */
@Configuration
@EnableConfigurationProperties(AdmissionProperties.class)
public class AdmissionConfiguration {

    @Bean
    public RequestsInFlight requestsInFlight() {
        return new RequestsInFlight();
    }

    @Bean
    public FilterRegistrationBean<RequestsInFlight> requestsInFlightFilter(RequestsInFlight f) {
        FilterRegistrationBean<RequestsInFlight> r = new FilterRegistrationBean<>(f);
        r.addUrlPatterns("/api/*");
        r.setOrder(-21);                                          // outermost: every request the server answers
        return r;
    }

    @Bean
    public AdmissionGate admissionGate(AdmissionProperties props, RequestsInFlight inFlight, ObjectProvider<AuditLog> audit) {
        Path data = Path.of(com.ash.drishti.api.DataDir.root()).toAbsolutePath().normalize();
        return new AdmissionGate(props, Signals.live(inFlight.counter(), data), System::currentTimeMillis, audit.getIfAvailable(),
                AdmissionConfiguration::requester);
    }

    /** The signed-in user of the request being answered on this thread; null for work the server starts itself. */
    static String requester() {
        RequestAttributes a = RequestContextHolder.getRequestAttributes();
        if (a != null && a.getAttribute(Principal.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof Principal p) {
            return p.user();
        }
        return null;
    }
}
