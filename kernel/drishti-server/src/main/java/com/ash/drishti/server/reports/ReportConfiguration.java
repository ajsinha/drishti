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
package com.ash.drishti.server.reports;

import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.identity.JpaAuditLog;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.server.api.SearchController;
import com.ash.drishti.server.security.SecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Scheduled reports ({@code drishti.reports}). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReportProperties.class)
public class ReportConfiguration {

    @Bean(destroyMethod = "close")
    public ReportService reportService(PreferenceStore store, SearchController search, BusinessDates dates, UserService users, JpaAuditLog audit,
            ReportProperties props, SecurityProperties security) {
        return new ReportService(store, search, dates, users, audit, props, security.enabled());
    }
}
