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

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.governance.GovernanceProperties;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.PackAccess;
import com.ash.drishti.server.security.RoleCatalog;
import com.ash.drishti.server.security.SecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans of business intelligence phase 1: the powers, the BI folder reader and the service. */
@Configuration
@EnableConfigurationProperties(BiProperties.class)
public class BiConfiguration {

    @Bean
    public BiAccess biAccess(SecurityProperties security, RoleCatalog roles) {
        return new BiAccess(security, roles);
    }

    @Bean
    public BiFolder biFolder(BiProperties props) {
        return new BiFolder(props.dir(), props.folderRefresh());
    }

    @Bean
    public BiService biService(BiProperties props, BiStore store, BiFolder folder, BiAccess access, AuditLog audit, UserService users, RoleCatalog roles,
            PackAccess packs, Entitlements entitlements, GovernanceProperties governance, SecurityProperties security) {
        return new BiService(props, store, folder, access, audit, users, roles, packs, entitlements, governance, security);
    }
}
