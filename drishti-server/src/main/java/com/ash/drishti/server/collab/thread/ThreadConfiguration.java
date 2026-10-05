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
package com.ash.drishti.server.collab.thread;

import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.NoteStore;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.CollabTx;
import com.ash.drishti.identity.collab.NoteImport;
import com.ash.drishti.identity.collab.ThreadStore;
import com.ash.drishti.server.collab.DirectoryService;
import com.ash.drishti.server.collab.InboxHub;
import com.ash.drishti.server.collab.Notifier;
import com.ash.drishti.server.collab.Principals;
import com.ash.drishti.server.collab.RateLimits;
import com.ash.drishti.server.security.Entitlements;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Comment threads (COLLABORATION.md): rendering, audience, the service, the notes import and the deprecated notes facade. */
@Configuration(proxyBeanMethods = false)
public class ThreadConfiguration {

    @Bean
    public PinnedDocs pinnedDocs(SourceRouter router) {
        return new PinnedDocs(router);
    }

    @Bean
    public CommentRenderer commentRenderer(Entitlements entitlements, PinnedDocs docs) {
        return new CommentRenderer(entitlements, docs);
    }

    @Bean
    public Audience commentAudience(UserService users, DirectoryService directory, Principals principals, Entitlements entitlements,
            CollabProperties props) {
        return new Audience(users, directory, principals, entitlements, props);
    }

    @Bean
    public ThreadService threadService(ThreadStore store, CollabTx tx, CollabProperties props, Entitlements entitlements, Principals principals,
            SourceRouter router, CommentRenderer renderer, PinnedDocs pinned, Audience audience, List<Notifier> notifiers, InboxHub hub,
            RateLimits limits, AuditLog audit) {
        return new ThreadService(store, tx, props, entitlements, principals, router, renderer, pinned, audience, notifiers, hub, limits, audit,
                Clock.systemUTC());
    }

    /** Imports the notes of earlier releases once (every start imports only what is new), then serves {@code /notes} over threads. */
    @Bean
    public NoteFacade noteFacade(ThreadService threads, Entitlements entitlements, NoteStore notes, ThreadStore store, CollabTx tx, AuditLog audit,
            CollabProperties props) {
        if (props.enabled()) {
            NoteImport.run(notes, store, tx, audit);
        }
        return new NoteFacade(threads, entitlements);
    }
}
