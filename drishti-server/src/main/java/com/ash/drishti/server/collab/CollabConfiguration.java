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
package com.ash.drishti.server.collab;

import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.identity.AccessLog;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.CollabTx;
import com.ash.drishti.identity.collab.InboxStore;
import com.ash.drishti.identity.collab.ShareStore;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.PackAccess;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The collaboration services (COLLABORATION.md): sharing with a note, the directory, the inbox and its live hub. */
@Configuration(proxyBeanMethods = false)
public class CollabConfiguration {

    @Bean
    public Principals principals(UserService users, Entitlements entitlements) {
        return new Principals(users, entitlements);
    }

    @Bean
    public RateLimits collabRateLimits() {
        return new RateLimits();
    }

    @Bean(destroyMethod = "close")
    public InboxHub inboxHub(InboxStore store, CollabProperties props) {
        return new InboxHub(store, props.inbox().poll());
    }

    @Bean
    public InboxService inboxService(InboxStore store, ShareStore shares, Entitlements entitlements, Principals principals,
            CollabProperties props) {
        return new InboxService(store, shares, entitlements, principals, props.inbox().keep());
    }

    @Bean
    public InAppNotifier inAppNotifier(InboxService inbox) {
        return new InAppNotifier(inbox);
    }

    @Bean
    public DirectoryService directoryService(UserService users, PackAccess packs, Entitlements entitlements, Principals principals,
            CollabProperties props, RateLimits limits) {
        return new DirectoryService(users, packs, entitlements, principals, props, limits);
    }

    @Bean
    public ShareService shareService(ShareStore store, CollabTx tx, CollabProperties props, Entitlements entitlements, PackAccess packs,
            Principals principals, DirectoryService directory, UserService users, SourceRouter router, List<Notifier> notifiers,
            InboxHub hub, ObjectProvider<AccessLog> accessLog, RateLimits limits) {
        return new ShareService(store, tx, props, entitlements, packs, principals, directory, users, router, notifiers, hub,
                accessLog.getIfAvailable(), limits);
    }
}
