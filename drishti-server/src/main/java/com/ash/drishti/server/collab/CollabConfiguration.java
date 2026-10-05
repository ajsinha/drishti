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
import com.ash.drishti.server.collab.thread.ThreadService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.PackAccess;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.identity.collab.OutboxStore;
import com.ash.drishti.server.collab.mail.EmailNotifier;
import com.ash.drishti.server.collab.mail.ItemRenderer;
import com.ash.drishti.server.collab.mail.MailContentPolicy;
import com.ash.drishti.server.collab.mail.MailRenderer;
import com.ash.drishti.server.collab.mail.MailTemplates;
import com.ash.drishti.server.collab.mail.MailTransport;
import com.ash.drishti.server.collab.mail.NotifyPrefs;
import com.ash.drishti.server.collab.mail.CommentItemRenderer;
import com.ash.drishti.server.collab.mail.OutboxChannel;
import com.ash.drishti.server.collab.mail.OutboxDispatcher;
import com.ash.drishti.server.collab.bridge.BridgeNotifier;
import com.ash.drishti.server.collab.thread.CommentRenderer;
import com.ash.drishti.identity.collab.ThreadStore;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.server.collab.mail.ShareItemRenderer;
import com.ash.drishti.server.collab.snapshot.SnapshotService;
import com.ash.drishti.server.security.SecurityProperties;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

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
            CollabProperties props, ObjectProvider<ThreadService> threads) {
        return new InboxService(store, shares, entitlements, principals, props.inbox().keep(), threads::getIfAvailable);
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
            InboxHub hub, ObjectProvider<AccessLog> accessLog, RateLimits limits, ThreadService threads, SnapshotService snapshots) {
        return new ShareService(store, tx, props, entitlements, packs, principals, directory, users, router, notifiers, hub,
                accessLog.getIfAvailable(), limits, threads, snapshots);
    }

    /** Watermarked snapshots: drawn only when {@code drishti.collab.snapshots.enabled} (or the kind's pack) says so. */
    @Bean(destroyMethod = "close")
    public SnapshotService snapshotService(CollabProperties props, ViewPipeline pipeline, Entitlements entitlements, Principals principals,
            PackAccess packs, ShareStore shares, ObjectProvider<AccessLog> accessLog) {
        return new SnapshotService(props, pipeline, entitlements, principals, packs, shares, accessLog.getIfAvailable());
    }

    @Bean
    public NotifyPrefs notifyPrefs(PreferenceStore preferences) {
        return new NotifyPrefs(preferences);
    }

    @Bean
    public MailContentPolicy mailContentPolicy(CollabProperties props, PackAccess packs) {
        return new MailContentPolicy(props, packs);
    }

    /** The email channel: off until enabled, with an SMTP host and a console URL; refuses to start when sign-in is off (identities are not real). */
    @Bean
    public EmailNotifier emailNotifier(CollabProperties props, OutboxStore outbox, Principals principals, NotifyPrefs prefs,
            ObjectProvider<JavaMailSender> sender, SecurityProperties security) {
        if (props.email().enabled() && !security.enabled()) {
            throw new IllegalStateException("drishti.collab.email.enabled needs drishti.security.enabled: with sign-in off the user names are "
                    + "not verified, so mail must not be sent to the addresses they map to");
        }
        return new EmailNotifier(props, outbox, principals, prefs, sender.getIfAvailable() != null);
    }

    @Bean
    public LinkBuilder linkBuilder(CollabProperties props) {
        return new LinkBuilder(props.consoleUrl());
    }

    /** Panel titles for emails and bridge posts, read from the view as the reader would get it. */
    @Bean
    public PanelTitles panelTitles(ViewPipeline pipeline, Entitlements entitlements) {
        return new PanelTitles(PanelTitles.fromPipeline(pipeline, entitlements), entitlements);
    }

    @Bean
    public ShareItemRenderer shareItemRenderer(ShareStore shares, Principals principals, Entitlements entitlements, MailContentPolicy policy,
            NotifyPrefs prefs, CollabProperties props, PanelTitles titles, SnapshotService snapshots) {
        return new ShareItemRenderer(shares, principals, entitlements, policy, prefs, props.consoleUrl(), titles, snapshots);
    }

    @Bean
    public CommentItemRenderer mentionItemRenderer(ThreadStore threads, Principals principals, Entitlements entitlements, CommentRenderer comments,
            MailContentPolicy policy, NotifyPrefs prefs, PanelTitles titles, LinkBuilder links) {
        return new CommentItemRenderer("mention", threads, principals, entitlements, comments, policy, prefs, titles, links);
    }

    @Bean
    public CommentItemRenderer replyItemRenderer(ThreadStore threads, Principals principals, Entitlements entitlements, CommentRenderer comments,
            MailContentPolicy policy, NotifyPrefs prefs, PanelTitles titles, LinkBuilder links) {
        return new CommentItemRenderer("reply", threads, principals, entitlements, comments, policy, prefs, titles, links);
    }

    /** Removes inbox rows older than {@code inbox.keep-days}. */
    @Bean(destroyMethod = "close", initMethod = "start")
    public InboxPurge inboxPurge(InboxStore store, CollabProperties props) {
        return new InboxPurge(store, props, java.time.Clock.systemUTC());
    }

    @Bean(destroyMethod = "close")
    public OutboxDispatcher outboxDispatcher(OutboxStore outbox, CollabProperties props, Principals principals, List<ItemRenderer> renderers,
            ObjectProvider<JavaMailSender> sender, io.micrometer.core.instrument.MeterRegistry meters, EmailNotifier email,
            List<OutboxChannel> channels, BridgeNotifier bridges, @Value("${drishti.branding.product:Drishti}") String product) {
        String from = props.email().from();
        String host = from.contains("@") ? from.substring(from.indexOf('@') + 1) : "localhost";
        JavaMailSender smtp = sender.getIfAvailable();
        MailTransport transport = smtp != null ? MailTransport.smtp(smtp) : (m, f) -> {
            throw new IllegalStateException("no SMTP host: set spring.mail.host");
        };
        OutboxDispatcher d = new OutboxDispatcher(outbox, props, principals, renderers, channels,
                new MailRenderer(new MailTemplates(props.email().templatesDir()), product, host), transport, meters, java.time.Clock.systemUTC());
        if (email.available() || bridges.available()) {
            d.start();
        }
        return d;
    }
}
