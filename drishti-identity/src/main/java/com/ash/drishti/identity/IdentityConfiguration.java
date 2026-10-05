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
package com.ash.drishti.identity;

import com.ash.drishti.identity.db.IdentityRepositories;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Beans contributed by {@code drishti-identity}: users, roles, preferences and the audit trail, all in the identity
 * database ({@link IdentityDatabase}). The server supplies {@link RoleNames}: built-in roles plus administrators' roles.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({IdentityProperties.class, com.ash.drishti.identity.design.DesignProperties.class,
        com.ash.drishti.identity.collab.CollabProperties.class})
@Import(IdentityDatabase.class)
public class IdentityConfiguration {

    @Bean
    public JpaAuditLog auditLog(IdentityRepositories.Audit audit) {
        return new JpaAuditLog(audit);
    }

    @Bean
    public JpaUserStore userStore(IdentityRepositories.Users users, TransactionTemplate identityTransactions) {
        return new JpaUserStore(users, identityTransactions);
    }

    @Bean
    public PreferenceStore preferenceStore(IdentityRepositories.Preferences prefs, TransactionTemplate identityTransactions) {
        return new JpaPreferenceStore(prefs, identityTransactions, 64 * 1024, 50);
    }

    /** The Build workbench's Designs: files ({@code drishti.builder.designs.store=file}, the default) or this database ({@code jpa}). */
    @Bean
    public com.ash.drishti.identity.design.DesignStore designStore(com.ash.drishti.identity.design.DesignProperties props,
            IdentityRepositories.Designs designs, IdentityRepositories.DesignSamples samples, TransactionTemplate identityTransactions) {
        return props.jpa() ? new com.ash.drishti.identity.design.JpaDesignStore(designs, samples, identityTransactions)
                : new com.ash.drishti.identity.design.FileDesignStore(java.nio.file.Path.of(props.dir()));
    }

    @Bean
    public com.ash.drishti.identity.design.DesignService designService(com.ash.drishti.identity.design.DesignStore store,
            com.ash.drishti.identity.design.DesignProperties props) {
        return new com.ash.drishti.identity.design.DesignService(store, props);
    }

    @Bean(destroyMethod = "close")
    public com.ash.drishti.identity.design.DesignSweeper designSweeper(com.ash.drishti.identity.design.DesignService designs,
            com.ash.drishti.identity.design.DesignProperties props) {
        return new com.ash.drishti.identity.design.DesignSweeper(designs, props.sweepInterval());
    }

    /**
     * Where shares and inbox rows are kept ({@code drishti.collab.store}): the identity database (default) or files. Files are
     * for one server: with a shared (PostgreSQL) database the server refuses to start, because several servers would not see
     * each other's writes.
     */
    @Bean
    public com.ash.drishti.identity.collab.CollabTx collabTx(com.ash.drishti.identity.collab.CollabProperties props, IdentityProperties id,
            TransactionTemplate identityTransactions) {
        requireSingleServerForFiles(props, id);
        return props.jpa() ? com.ash.drishti.identity.collab.CollabTx.of(identityTransactions) : com.ash.drishti.identity.collab.CollabTx.serial();
    }

    @Bean
    public com.ash.drishti.identity.collab.ShareStore shareStore(com.ash.drishti.identity.collab.CollabProperties props,
            IdentityRepositories.Shares shares, IdentityRepositories.ShareRecipients recipients, TransactionTemplate identityTransactions) {
        return props.jpa() ? new com.ash.drishti.identity.collab.JpaShareStore(shares, recipients, identityTransactions)
                : new com.ash.drishti.identity.collab.FileShareStore(java.nio.file.Path.of(props.dir()));
    }

    @Bean
    public com.ash.drishti.identity.collab.InboxStore inboxStore(com.ash.drishti.identity.collab.CollabProperties props,
            IdentityRepositories.Inbox inbox, TransactionTemplate identityTransactions) {
        return props.jpa() ? new com.ash.drishti.identity.collab.JpaInboxStore(inbox, identityTransactions)
                : new com.ash.drishti.identity.collab.FileInboxStore(java.nio.file.Path.of(props.dir()), props.inbox().keep());
    }

    @Bean
    public com.ash.drishti.identity.collab.OutboxStore outboxStore(com.ash.drishti.identity.collab.CollabProperties props,
            IdentityRepositories.Outbox outbox, TransactionTemplate identityTransactions) {
        return props.jpa() ? new com.ash.drishti.identity.collab.JpaOutboxStore(outbox, identityTransactions)
                : new com.ash.drishti.identity.collab.FileOutboxStore(java.nio.file.Path.of(props.dir()));
    }

    @Bean
    public com.ash.drishti.identity.collab.ThreadStore threadStore(com.ash.drishti.identity.collab.CollabProperties props,
            IdentityRepositories.Threads threads, IdentityRepositories.Comments comments, IdentityRepositories.Revisions revisions,
            IdentityRepositories.Mentions mentions, IdentityRepositories.Follows follows, IdentityRepositories.NoteLinks links,
            TransactionTemplate identityTransactions) {
        return props.jpa() ? new com.ash.drishti.identity.collab.JpaThreadStore(threads, comments, revisions, mentions, follows, links,
                identityTransactions) : new com.ash.drishti.identity.collab.FileThreadStore(java.nio.file.Path.of(props.dir()));
    }

    public static void requireSingleServerForFiles(com.ash.drishti.identity.collab.CollabProperties props, IdentityProperties id) {
        if (!props.jpa() && !id.sqlite()) {
            throw new IllegalStateException("drishti.collab.store=file keeps shares on this server only, but drishti.identity.database-url "
                    + "is a shared database: use drishti.collab.store=jpa");
        }
    }

    @Bean
    public RoleStore roleStore(IdentityRepositories.Roles roles, TransactionTemplate identityTransactions, JpaAuditLog auditLog) {
        return new RoleStore(roles, identityTransactions, auditLog);
    }

    @Bean
    public ApiTokenStore apiTokenStore(IdentityRepositories.ApiTokens tokens, TransactionTemplate identityTransactions, JpaAuditLog auditLog) {
        return new ApiTokenStore(tokens, identityTransactions, auditLog);
    }

    /** Console sign-in sessions: opened at sign-in, checked per request by the console, ended at sign-out. */
    @Bean
    public SessionStore sessionStore(IdentityRepositories.Sessions sessions, TransactionTemplate identityTransactions, JpaAuditLog auditLog) {
        return new SessionStore(sessions, identityTransactions, auditLog);
    }

    /** Who read what ({@code drishti.access-log.keep-days}, 90; {@code queue}, 100000 events waiting at most). */
    @Bean(destroyMethod = "close")
    public AccessLog accessLog(IdentityRepositories.Access access, TransactionTemplate identityTransactions,
            @org.springframework.beans.factory.annotation.Value("${drishti.access-log.keep-days:90}") int keepDays,
            @org.springframework.beans.factory.annotation.Value("${drishti.access-log.queue:100000}") int queue) {
        return new AccessLog(access, identityTransactions, keepDays, queue);
    }

    @Bean
    public NoteStore noteStore(IdentityRepositories.Notes notes, TransactionTemplate identityTransactions, JpaAuditLog auditLog) {
        return new NoteStore(notes, identityTransactions, auditLog);
    }

    /** Fired alerts, the newest {@code drishti.alerts.keep} (1000) per user. */
    @Bean
    public AlertHistory alertHistory(IdentityRepositories.Alerts alerts, TransactionTemplate identityTransactions,
            org.springframework.core.env.Environment env) {
        return new AlertHistory(alerts, identityTransactions, Integer.parseInt(env.getProperty("drishti.alerts.keep", "1000")));
    }

    @Bean
    public PackStateStore packStateStore(IdentityRepositories.PackStates states, TransactionTemplate identityTransactions, JpaAuditLog auditLog) {
        return new PackStateStore(states, identityTransactions, auditLog);
    }

    /**
     * Servers that share one database (PostgreSQL) see each other's role and pack changes within
     * {@code drishti.identity.refresh-seconds} (15): the snapshots every check reads are re-read on that interval.
     */
    @Bean(destroyMethod = "shutdownNow")
    public java.util.concurrent.ScheduledExecutorService identityRefresher(RoleStore roles, PackStateStore packs,
            org.springframework.core.env.Environment env) {
        long every = Long.parseLong(env.getProperty("drishti.identity.refresh-seconds", "15"));
        var exec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("drishti-identity-refresh").factory());
        exec.scheduleWithFixedDelay(() -> {
            try {
                roles.refresh();
                packs.refresh();
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(IdentityConfiguration.class).warn("identity refresh failed: {}", e.getMessage());
            }
        }, every, every, java.util.concurrent.TimeUnit.SECONDS);
        return exec;
    }

    @Bean
    public UserService userService(JpaUserStore store, JpaAuditLog auditLog, PreferenceStore preferences, IdentityProperties props,
            RoleNames roles) {
        LegacyImport.run(props, store, auditLog, preferences);
        UserService s = new UserService(store, new PasswordHasher(props.iterations()), auditLog, props, roles);
        s.seedIfEmpty();
        return s;
    }
}
