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
package com.ash.drishti.server.loads;

import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.alerts.AlertEngine;
import com.ash.drishti.server.collab.InboxHub;
import com.ash.drishti.server.collab.InboxService;
import com.ash.drishti.server.collab.Principals;
import com.ash.drishti.server.collab.mail.EmailNotifier;
import com.ash.drishti.server.collab.mail.NotifyPrefs;
import com.ash.drishti.server.security.Entitlements;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Data loads: the "batch landed" signal, its history, and the expectations that flag late data. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LoadsProperties.class)
public class LoadsConfiguration {

    @Bean
    public LoadStore loadStore(LoadsProperties props) {
        return new LoadStore(Path.of(props.dir()), props.keep());
    }

    @Bean
    public LoadsConfig.Overrides loadsOverrides(LoadsProperties props) {
        return new LoadsConfig.Overrides(Path.of(props.dir()), props.defaultNotifyRoles(), props.smokeMax());
    }

    @Bean
    public LoadNotifier loadNotifier(UserService users, Principals principals, Entitlements entitlements, InboxService inbox, InboxHub hub, EmailNotifier email) {
        return new LoadNotifier(users, principals, entitlements, inbox, hub, email, Clock.systemUTC());
    }

    @Bean
    @SuppressWarnings("java:S107")
    public LoadService loadService(PackRegistry packs, LoadStore store, LoadsConfig.Overrides configs, LoadsProperties props, SourceRegistry sources,
            SourceRouter router, ViewPipeline pipeline, AlertEngine alerts, LoadNotifier notifier, BusinessDates dates, AuditLog audit) {
        return new LoadService(packs, store, configs, props, sources, router, pipeline, alerts, notifier, dates, audit, Clock.systemUTC());
    }

    @Bean(destroyMethod = "close")
    public ExpectationService expectationService(LoadService loads, LoadNotifier notifier, BusinessDates dates, LoadsProperties props) {
        ExpectationService s = new ExpectationService(loads, notifier, dates, Clock.systemUTC());
        return props.scheduler() ? s.start() : s;
    }

    /** The email of a load or of late data. */
    @Bean
    public LoadItemRenderer loadItemRenderer(LoadStore store, Entitlements entitlements, NotifyPrefs prefs, CollabProperties collab) {
        return new LoadItemRenderer(store, entitlements, prefs, collab.consoleUrl());
    }
}
