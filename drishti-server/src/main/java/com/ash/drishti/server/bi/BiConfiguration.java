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
import com.ash.drishti.server.bi.gov.ImpactAnalyzer;
import com.ash.drishti.server.collab.InboxHub;
import com.ash.drishti.server.collab.InboxService;
import com.ash.drishti.server.collab.Principals;
import com.ash.drishti.server.governance.GovernanceProperties;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.PackAccess;
import com.ash.drishti.server.security.RoleCatalog;
import com.ash.drishti.server.security.SecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans of business intelligence phases 1 and 4: the powers, the BI folder reader, the service, and publishing and governance. */
@Configuration
@EnableConfigurationProperties({BiProperties.class, BiGovProperties.class, com.ash.drishti.server.bi.ai.BiAiProperties.class})
public class BiConfiguration {

    @Bean
    public BiAccess biAccess(SecurityProperties security, RoleCatalog roles) {
        return new BiAccess(security, roles);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public BiFolder biFolder(BiProperties props) {
        return new BiFolder(props.dir(), props.folderRefresh(), props.watch());
    }

    @Bean
    public ImpactAnalyzer biImpact(BiStore store, BiFolder folder) {
        return new ImpactAnalyzer(store, folder);
    }

    @Bean
    public BiNotifier biNotifier(BiGovProperties props, InboxService inbox, InboxHub hub, UserService users, Principals principals, BiAccess access, Entitlements entitlements,
            BiStore store) {
        return new BiNotifier(props, inbox, hub, users, principals, access, entitlements, store);
    }

    @Bean
    public BiService biService(BiProperties props, BiStore store, BiFolder folder, BiAccess access, AuditLog audit, UserService users, RoleCatalog roles,
            PackAccess packs, Entitlements entitlements, GovernanceProperties governance, SecurityProperties security, BiGovProperties gov, ImpactAnalyzer impact,
            BiNotifier notifier) {
        return new BiService(props, store, folder, access, audit, users, roles, packs, entitlements, governance, security, gov, impact, notifier);
    }

    @Bean
    public BiBundles biBundles(BiService bi, BiStore store, BiAccess access, ImpactAnalyzer impact, BiGovProperties gov, BiProperties props, AuditLog audit, UserService users,
            RoleCatalog roles, PackAccess packs) {
        return new BiBundles(bi, store, access, impact, gov, props, audit, users, roles, packs);
    }
}
