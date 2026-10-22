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

    /**
     * The audit trail: the content store ({@code drishti.audit.store=content}, the default; per-node day segments merged when read,
     * CONTENT_STORE.md section 9; the table's rows are imported once) or, one node only, the identity database ({@code jpa}).
     */
    @Bean
    public JpaAuditLog auditLog(IdentityRepositories.Audit audit, com.ash.drishti.identity.collab.Seal.Store seals,
            com.ash.drishti.identity.content.ContentStore content, org.springframework.core.env.Environment env) {
        JpaAuditLog log = new JpaAuditLog(audit, seals);
        if (!"jpa".equalsIgnoreCase(env.getProperty("drishti.audit.store", "content").trim())) {
            ContentAuditLog c = new ContentAuditLog(content, nodeName(env));
            String marker = "system/imports/audit-jpa.json";
            if (new com.ash.drishti.identity.content.ContentDocs(content).create(marker, java.util.Map.of("at", System.currentTimeMillis()))) {
                try {
                    int n = log.copyTo(c);                               // the marker first: only one node copies
                    if (n > 0) {
                        org.slf4j.LoggerFactory.getLogger(IdentityConfiguration.class).info("audit: imported {} events into the content store", n);
                    }
                } catch (RuntimeException e) {
                    content.deleteLatest(marker);
                    throw e;
                }
            }
            log.useContent(c);
        }
        return log;
    }

    /** This node's name: {@code drishti.net.node-id}, else host and port. */
    static String nodeName(org.springframework.core.env.Environment env) {
        String node = env.getProperty("drishti.net.node-id", "");
        return node.isBlank() ? com.ash.drishti.identity.content.ContentStoreNodes.localName(env.getProperty("server.port", "")) : node;
    }

    @Bean
    public JpaUserStore userStore(IdentityRepositories.Users users, TransactionTemplate identityTransactions) {
        return new JpaUserStore(users, identityTransactions);
    }

    @Bean
    public PreferenceStore preferenceStore(IdentityRepositories.Preferences prefs, TransactionTemplate identityTransactions) {
        return new JpaPreferenceStore(prefs, identityTransactions, 64 * 1024, 50);
    }

    /**
     * The content store (docs/architecture/CONTENT_STORE.md): user content every node of a cluster can reach, configured in its own file
     * ({@code drishti.store.file}, {@code config/store/store.yaml}).
     */
    @Bean(destroyMethod = "close")
    @org.springframework.context.annotation.Primary
    public com.ash.drishti.identity.content.ContentStore contentStore(org.springframework.core.env.Environment env) {
        java.nio.file.Path file = java.nio.file.Path.of(env.getProperty("drishti.store.file", "./config/store/store.yaml"));
        java.nio.file.Path data = java.nio.file.Path.of(env.getProperty("drishti.data.dir", "./data"));
        com.ash.drishti.identity.content.ContentStoreSettings s = com.ash.drishti.identity.content.ContentStoreSettings.load(file, data, env::resolvePlaceholders);
        String root = env.getProperty("drishti.store.root", "");
        if (!root.isBlank()) {
            s = s.withFsRoot(java.nio.file.Path.of(root));
        }
        com.ash.drishti.identity.content.ContentStore store = s.open();
        String node = env.getProperty("drishti.net.node-id", "");
        com.ash.drishti.identity.content.ContentStoreNodes.announce(store,
                node.isBlank() ? com.ash.drishti.identity.content.ContentStoreNodes.localName(env.getProperty("server.port", "")) : node);
        org.slf4j.LoggerFactory.getLogger(IdentityConfiguration.class).info("content store: {} (settings {})", store.root(), s.origin());
        return store;
    }

    /**
     * The node's one poller of the content store's changes (CONTENT_STORE.md section 11): the shared configuration overlays and the
     * pack state listen to it, so an edit made on one node is applied on every node within {@code drishti.store.watch-ms}.
     */
    @Bean(destroyMethod = "close")
    public com.ash.drishti.identity.content.ContentWatch contentWatch(com.ash.drishti.identity.content.ContentStore store,
            org.springframework.core.env.Environment env) {
        return new com.ash.drishti.identity.content.ContentWatch(store, env.getProperty("drishti.store.watch-ms", Long.class, 2000L)).start();
    }

    /**
     * The Build workbench's Designs: the content store ({@code drishti.builder.designs.store=content}, the default; the old file folder and
     * database rows are imported once at start), or, one node only, files ({@code file}) or this database ({@code jpa}).
     */
    @Bean
    public com.ash.drishti.identity.design.DesignStore designStore(com.ash.drishti.identity.design.DesignProperties props,
            IdentityRepositories.Designs designs, IdentityRepositories.DesignSamples samples, TransactionTemplate identityTransactions,
            com.ash.drishti.identity.content.ContentStore content) {
        com.ash.drishti.identity.design.JpaDesignStore jpa = new com.ash.drishti.identity.design.JpaDesignStore(designs, samples, identityTransactions);
        if (props.content()) {
            com.ash.drishti.identity.design.ContentDesignStore store = new com.ash.drishti.identity.design.ContentDesignStore(content);
            com.ash.drishti.identity.design.DesignImport.fromFiles(java.nio.file.Path.of(props.dir()), store);
            com.ash.drishti.identity.design.DesignImport.fromDatabase(jpa, store);
            return store;
        }
        return props.jpa() ? jpa : new com.ash.drishti.identity.design.FileDesignStore(java.nio.file.Path.of(props.dir()));
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
     * The content store with multi-document transactions (CONTENT_STORE.md section 9): what collaboration and the admin data write
     * through. Interrupted commits on any node are finished at start and on the identity refresh interval.
     */
    @Bean
    public com.ash.drishti.identity.content.TxContentStore contentTransactions(com.ash.drishti.identity.content.ContentStore content) {
        com.ash.drishti.identity.content.TxContentStore tx = new com.ash.drishti.identity.content.TxContentStore(content);
        tx.recover();
        return tx;
    }

    /**
     * Collaboration in the content store ({@code drishti.collab.store=content}, the default): the stores, after the one-time import
     * of the identity database's collaboration tables and of the {@code <data>/collab} files. Empty for {@code jpa} and {@code file}.
     */
    @Bean
    public com.ash.drishti.identity.collab.CollabImport.Content collabContent(com.ash.drishti.identity.collab.CollabProperties props,
            com.ash.drishti.identity.content.TxContentStore content, IdentityRepositories.Threads threads, IdentityRepositories.Comments comments,
            IdentityRepositories.Revisions revisions, IdentityRepositories.Mentions mentions, IdentityRepositories.Follows follows,
            IdentityRepositories.NoteLinks links, IdentityRepositories.Shares shares, IdentityRepositories.ShareRecipients recipients,
            IdentityRepositories.Inbox inbox, IdentityRepositories.Outbox outbox, IdentityRepositories.Holds holds,
            IdentityRepositories.Seals seals, TransactionTemplate identityTransactions) {
        if (!props.content()) {
            return new com.ash.drishti.identity.collab.CollabImport.Content(null);
        }
        com.ash.drishti.identity.collab.CollabImport.Target target = com.ash.drishti.identity.collab.CollabImport.Target.of(content);
        com.ash.drishti.identity.collab.CollabImport.run(new com.ash.drishti.identity.collab.CollabImport.Source("jpa", new com.ash.drishti.identity.collab.JpaThreadStore(threads, comments, revisions, mentions, follows, links,
                identityTransactions), new com.ash.drishti.identity.collab.JpaShareStore(shares, recipients, identityTransactions), new com.ash.drishti.identity.collab.JpaInboxStore(inbox,
                identityTransactions), new com.ash.drishti.identity.collab.JpaOutboxStore(outbox, identityTransactions), new com.ash.drishti.identity.collab.JpaHoldStore(holds, identityTransactions),
                new com.ash.drishti.identity.collab.JpaSealStore(seals, identityTransactions)), target);
        java.nio.file.Path dir = java.nio.file.Path.of(props.dir());
        if (java.nio.file.Files.isDirectory(dir.resolve("threads")) || java.nio.file.Files.isDirectory(dir.resolve("shares"))
                || java.nio.file.Files.isDirectory(dir.resolve("inbox")) || java.nio.file.Files.isDirectory(dir.resolve("outbox"))
                || java.nio.file.Files.isRegularFile(dir.resolve("seals.log"))) {
            com.ash.drishti.identity.collab.CollabImport.run(new com.ash.drishti.identity.collab.CollabImport.Source("file", new com.ash.drishti.identity.collab.FileThreadStore(dir), new com.ash.drishti.identity.collab.FileShareStore(dir),
                    new com.ash.drishti.identity.collab.FileInboxStore(dir, Integer.MAX_VALUE), new com.ash.drishti.identity.collab.FileOutboxStore(dir), new com.ash.drishti.identity.collab.FileHoldStore(dir), new com.ash.drishti.identity.collab.FileSealStore(dir)),
                    target);
        }
        return new com.ash.drishti.identity.collab.CollabImport.Content(new com.ash.drishti.identity.collab.CollabImport.Target(content, target.threads(), target.shares(),
                new com.ash.drishti.identity.collab.ContentInboxStore(content, props.inbox().keep()), target.outbox(), target.holds(), target.seals()));
    }

    /**
     * Where collaboration is kept ({@code drishti.collab.store}): the content store (default, every node of a cluster), or, for one
     * node only, the identity database ({@code jpa}) or files ({@code file}). Files are refused beside a shared (PostgreSQL) database.
     */
    @Bean
    public com.ash.drishti.identity.collab.CollabTx collabTx(com.ash.drishti.identity.collab.CollabProperties props, IdentityProperties id,
            TransactionTemplate identityTransactions, com.ash.drishti.identity.collab.CollabImport.Content collabContent) {
        requireSingleServerForFiles(props, id);
        if (collabContent.isPresent()) {
            return com.ash.drishti.identity.collab.CollabTx.of(collabContent.get().store());
        }
        return props.jpa() ? com.ash.drishti.identity.collab.CollabTx.of(identityTransactions) : com.ash.drishti.identity.collab.CollabTx.serial();
    }

    /** Tamper-evidence seals for threads, comment rows, holds and the audit trail, next to the rows they cover. */
    @Bean
    public com.ash.drishti.identity.collab.Seal.Store sealStore(com.ash.drishti.identity.collab.CollabProperties props,
            IdentityRepositories.Seals seals, TransactionTemplate identityTransactions, com.ash.drishti.identity.collab.CollabImport.Content collabContent) {
        if (collabContent.isPresent()) {
            return collabContent.get().seals();
        }
        return props.jpa() ? new com.ash.drishti.identity.collab.JpaSealStore(seals, identityTransactions)
                : new com.ash.drishti.identity.collab.FileSealStore(java.nio.file.Path.of(props.dir()));
    }

    @Bean
    public com.ash.drishti.identity.collab.ShareStore shareStore(com.ash.drishti.identity.collab.CollabProperties props,
            IdentityRepositories.Shares shares, IdentityRepositories.ShareRecipients recipients, TransactionTemplate identityTransactions,
            com.ash.drishti.identity.collab.CollabImport.Content collabContent) {
        if (collabContent.isPresent()) {
            return collabContent.get().shares();
        }
        return props.jpa() ? new com.ash.drishti.identity.collab.JpaShareStore(shares, recipients, identityTransactions)
                : new com.ash.drishti.identity.collab.FileShareStore(java.nio.file.Path.of(props.dir()));
    }

    @Bean
    public com.ash.drishti.identity.collab.InboxStore inboxStore(com.ash.drishti.identity.collab.CollabProperties props,
            IdentityRepositories.Inbox inbox, TransactionTemplate identityTransactions, com.ash.drishti.identity.collab.CollabImport.Content collabContent) {
        if (collabContent.isPresent()) {
            return collabContent.get().inbox();
        }
        return props.jpa() ? new com.ash.drishti.identity.collab.JpaInboxStore(inbox, identityTransactions)
                : new com.ash.drishti.identity.collab.FileInboxStore(java.nio.file.Path.of(props.dir()), props.inbox().keep());
    }

    @Bean
    public com.ash.drishti.identity.collab.OutboxStore outboxStore(com.ash.drishti.identity.collab.CollabProperties props,
            IdentityRepositories.Outbox outbox, TransactionTemplate identityTransactions, com.ash.drishti.identity.collab.CollabImport.Content collabContent) {
        if (collabContent.isPresent()) {
            return collabContent.get().outbox();
        }
        return props.jpa() ? new com.ash.drishti.identity.collab.JpaOutboxStore(outbox, identityTransactions)
                : new com.ash.drishti.identity.collab.FileOutboxStore(java.nio.file.Path.of(props.dir()));
    }

    @Bean
    public com.ash.drishti.identity.collab.ThreadStore threadStore(com.ash.drishti.identity.collab.CollabProperties props,
            IdentityRepositories.Threads threads, IdentityRepositories.Comments comments, IdentityRepositories.Revisions revisions,
            IdentityRepositories.Mentions mentions, IdentityRepositories.Follows follows, IdentityRepositories.NoteLinks links,
            TransactionTemplate identityTransactions, com.ash.drishti.identity.collab.Seal.Store seals, com.ash.drishti.identity.collab.CollabImport.Content collabContent) {
        com.ash.drishti.identity.collab.ThreadStore inner = collabContent.isPresent() ? collabContent.get().threads()
                : props.jpa() ? new com.ash.drishti.identity.collab.JpaThreadStore(threads, comments, revisions, mentions, follows, links, identityTransactions)
                : new com.ash.drishti.identity.collab.FileThreadStore(java.nio.file.Path.of(props.dir()));
        return com.ash.drishti.identity.collab.Seals.sealing(inner, seals);
    }

    @Bean
    public com.ash.drishti.identity.collab.HoldStore holdStore(com.ash.drishti.identity.collab.CollabProperties props,
            IdentityRepositories.Holds holds, TransactionTemplate identityTransactions, com.ash.drishti.identity.collab.Seal.Store seals,
            com.ash.drishti.identity.collab.CollabImport.Content collabContent) {
        com.ash.drishti.identity.collab.HoldStore inner = collabContent.isPresent() ? collabContent.get().holds()
                : props.jpa() ? new com.ash.drishti.identity.collab.JpaHoldStore(holds, identityTransactions) : new com.ash.drishti.identity.collab.FileHoldStore(java.nio.file.Path.of(props.dir()));
        return com.ash.drishti.identity.collab.Seals.sealing(inner, seals);
    }

    public static void requireSingleServerForFiles(com.ash.drishti.identity.collab.CollabProperties props, IdentityProperties id) {
        if ("file".equals(props.store()) && !id.sqlite()) {
            throw new IllegalStateException("drishti.collab.store=file keeps shares on this server only, but drishti.identity.database-url "
                    + "is a shared database: use drishti.collab.store=jpa");
        }
    }

    /**
     * Business intelligence (Rupaka): workspaces, items, versions and marks. {@code drishti.bi.store: content} (the default) keeps them in the content
     * store, which every node of a cluster reaches, importing the identity database's {@code drishti_bi_*} rows once; {@code jpa} keeps them in
     * this node's identity database (docs/architecture/CONTENT_STORE.md section 9).
     */
    @Bean
    public com.ash.drishti.identity.bi.BiStore biStore(IdentityRepositories.BiWorkspaces workspaces, IdentityRepositories.BiMembers members,
            IdentityRepositories.BiItems items, IdentityRepositories.BiVersions versions, IdentityRepositories.BiRevisions revisions,
            IdentityRepositories.BiEvents events, IdentityRepositories.BiUserItems marks, IdentityRepositories.BiUsages usage, IdentityRepositories.BiSettings settings,
            TransactionTemplate identityTransactions, com.ash.drishti.identity.content.ContentStore content, org.springframework.core.env.Environment env) {
        com.ash.drishti.identity.bi.JpaBiStore jpa = new com.ash.drishti.identity.bi.JpaBiStore(workspaces, members, items, versions, revisions, events, marks,
                usage, settings, identityTransactions);
        String raw = env.getProperty("drishti.bi.store", "content").trim();
        if (raw.equals(System.getenv("DRISHTI_BI_STORE")) && !"jpa".equalsIgnoreCase(raw) && !"content".equalsIgnoreCase(raw)) {
            raw = "content";        // DRISHTI_BI_STORE is the BI store folder (drishti.bi.query.store-dir); relaxed binding also offers it here
        }
        String kind = raw.toLowerCase(java.util.Locale.ROOT);
        if ("jpa".equals(kind)) {
            return jpa;
        }
        if (!"content".equals(kind)) {
            throw new IllegalStateException("drishti.bi.store is '" + kind + "': use content (the default) or jpa");
        }
        String node = env.getProperty("drishti.net.node-id", "");
        com.ash.drishti.identity.bi.ContentBiStore store = new com.ash.drishti.identity.bi.ContentBiStore(content,
                node.isBlank() ? com.ash.drishti.identity.content.ContentStoreNodes.localName(env.getProperty("server.port", "")) : node,
                org.springframework.boot.convert.DurationStyle.detectAndParse(env.getProperty("drishti.bi.store-list-ttl", "1s")).toMillis());
        com.ash.drishti.identity.bi.BiImport.fromDatabase(jpa, store);
        return store;
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
    public PackStateStore packStateStore(IdentityRepositories.PackStates states, TransactionTemplate identityTransactions, JpaAuditLog auditLog,
            com.ash.drishti.identity.content.ContentStore content, org.springframework.core.env.Environment env) {
        boolean jpa = "jpa".equalsIgnoreCase(env.getProperty("drishti.packs.state-store", "content").trim());
        return new PackStateStore(states, identityTransactions, auditLog, jpa ? null : content);
    }

    /** Host applications for embedded views (Drishti Elements); the limits are the defaults a new registration gets. */
    @Bean
    public EmbedAppStore embedAppStore(IdentityRepositories.EmbedApps apps, TransactionTemplate identityTransactions, JpaAuditLog auditLog,
            org.springframework.core.env.Environment env, com.ash.drishti.identity.content.ContentStore content) {
        EmbedAppRows rows = EmbedAppRows.of(apps);
        if (!"jpa".equalsIgnoreCase(env.getProperty("drishti.embed.store", "content").trim())) {
            EmbedAppRows.Content c = new EmbedAppRows.Content(content);
            c.importFrom(apps);
            rows = c;
        }
        return new EmbedAppStore(rows, identityTransactions, auditLog,
                Boolean.parseBoolean(env.getProperty("drishti.embed.allow-wildcard-origins", "false")),
                Integer.parseInt(env.getProperty("drishti.embed.token-max-seconds", "900")),
                Integer.parseInt(env.getProperty("drishti.embed.limits.views-per-minute", "600")),
                Integer.parseInt(env.getProperty("drishti.embed.limits.views-per-user-per-minute", "60")));
    }

    /**
     * Servers that share one database (PostgreSQL) see each other's role and pack changes within
     * {@code drishti.identity.refresh-seconds} (15): the snapshots every check reads are re-read on that interval.
     */
    @Bean(destroyMethod = "shutdownNow")
    public java.util.concurrent.ScheduledExecutorService identityRefresher(RoleStore roles, PackStateStore packs, EmbedAppStore embedApps,
            org.springframework.core.env.Environment env, com.ash.drishti.identity.content.TxContentStore contentTransactions) {
        long every = Long.parseLong(env.getProperty("drishti.identity.refresh-seconds", "15"));
        var exec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("drishti-identity-refresh").factory());
        exec.scheduleWithFixedDelay(() -> {
            try {
                roles.refresh();
                packs.refresh();
                embedApps.refresh();
                contentTransactions.recover();
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
