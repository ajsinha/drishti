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
package com.ash.drishti.server.collab.compliance;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.CollabTx;
import com.ash.drishti.identity.collab.HoldStore;
import com.ash.drishti.identity.collab.ShareStore;
import com.ash.drishti.identity.collab.ThreadStore;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.PackAccess;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Retention, legal holds, the compliance export and chain verification (COLLABORATION.md, Retention, legal hold and export). */
@Configuration(proxyBeanMethods = false)
public class ComplianceConfiguration {

    @Bean
    public HoldService holdService(HoldStore store, ThreadStore threads, Entitlements entitlements, AuditLog audit, CollabProperties props) {
        return new HoldService(store, threads, entitlements, audit, props, Clock.systemUTC());
    }

    @Bean
    public ChainVerifier chainVerifier(ThreadStore threads, ShareStore shares) {
        return new ChainVerifier(threads, shares);
    }

    /** Runs on a schedule only when some retention is configured (the default keeps everything forever). */
    @Bean(destroyMethod = "close", initMethod = "start")
    public CollabPurge collabPurge(CollabProperties props, ThreadStore threads, ShareStore shares, HoldService holds, PackAccess packs,
            Entitlements entitlements, AuditLog audit, CollabTx tx) {
        return new CollabPurge(props, threads, shares, holds, packs, entitlements, audit, tx, Clock.systemUTC());
    }

    @Bean(destroyMethod = "close")
    public ExportService exportService(CollabProperties props, ThreadStore threads, ShareStore shares, HoldStore holds, UserService users,
            Entitlements entitlements, AuditLog audit, ChainVerifier verifier, ObjectProvider<BuildProperties> build) {
        BuildProperties b = build.getIfAvailable();
        return new ExportService(props, threads, shares, () -> holds.list(false), users, entitlements, audit, verifier, Clock.systemUTC(),
                b == null ? null : b.getVersion());
    }
}
