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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * The identity database, checked the same way on every database it supports: the JPA entities validate against the
 * schema file (start-up fails otherwise), and users, roles, saved documents and the audit trail behave identically.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class IdentityStoreContract {

    private ConfigurableApplicationContext ctx;
    private final ObjectMapper json = new ObjectMapper();

    /** {@code drishti.identity.*} for the database under test. */
    protected abstract java.util.Map<String, Object> database();

    @BeforeAll
    void start() {
        ctx = open();
    }

    private ConfigurableApplicationContext open() {
        AnnotationConfigApplicationContext c = new AnnotationConfigApplicationContext();
        java.util.Map<String, Object> props = new java.util.HashMap<>(database());
        props.put("drishti.identity.iterations", "1000");
        props.put("drishti.identity.seed-admin", "true");
        props.put("drishti.alerts.keep", "60");
        props.put("drishti.identity.users-file", "target/no-legacy-" + System.nanoTime() + "/users.json");
        c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", props));
        c.registerBean(RoleNames.class, () -> RoleNames.of(Set.of("admin", "trader", "risk")));
        c.register(IdentityConfiguration.class);
        c.refresh();
        return c;
    }

    @AfterAll
    void stop() {
        ctx.close();
    }

    private <T> T bean(Class<T> type) {
        return ctx.getBean(type);
    }

    @Test
    void schemaIsIdempotentSoAStartAgainstAnExistingDatabaseChangesNothing() {
        ConfigurableApplicationContext again = open();
        try {
            assertThat(again.getBean(JpaUserStore.class).find("drishti-dev-admin")).isPresent();
        } finally {
            again.close();
        }
    }

    @Test
    void usersKeepRolesAndTellNoPacksFromDefaultPacks() {
        JpaUserStore store = bean(JpaUserStore.class);
        Instant now = Instant.parse("2026-09-30T10:15:30Z");
        store.put(new User("u-defaults", "Default Packs", "a@b.co", "Rates", Set.of("trader"), true, false, "h", 0, null, now, now, null, null, null));
        store.put(new User("u-none", "No Packs", "", "", Set.of("trader", "risk"), true, true, "h", 2, now.plusSeconds(60), now, now, now, now, Set.of()));
        store.put(new User("u-some", "Some", "", "", Set.of(), false, false, "h", 0, null, now, now, null, null, Set.of("finance", "trading")));

        User d = store.find("u-defaults").orElseThrow();
        assertThat(d.packs()).isNull();
        assertThat(d.roles()).containsExactly("trader");
        assertThat(d.createdAt()).isEqualTo(now);
        User n = store.find("u-none").orElseThrow();
        assertThat(n.packs()).isEmpty();
        assertThat(n.roles()).containsExactlyInAnyOrder("trader", "risk");
        assertThat(n.lockedUntil()).isEqualTo(now.plusSeconds(60));
        assertThat(n.failedAttempts()).isEqualTo(2);
        assertThat(store.find("u-some").orElseThrow().packs()).containsExactlyInAnyOrder("finance", "trading");

        store.put(new User("u-some", "Some", "", "", Set.of("risk"), true, false, "h2", 0, null, now, now, null, null, Set.of("finance")));
        User s = store.find("u-some").orElseThrow();
        assertThat(s.roles()).containsExactly("risk");
        assertThat(s.packs()).containsExactly("finance");
        assertThat(store.delete("u-some")).isTrue();
        assertThat(store.delete("u-some")).isFalse();
        assertThat(store.find("u-some")).isEmpty();
    }

    @Test
    void theServiceSeedsAndManagesUsersThroughTheDatabase() {
        UserService users = bean(UserService.class);
        assertThat(users.list(null)).extracting(User::username).contains("drishti-dev-admin");
        users.create("drishti-dev-admin", "dana", new UserService.Profile("Dana", "dana@desk.co", "Credit", Set.of("risk"), true, null),
                "long-enough-pw-1", false);
        assertThat(users.authenticate("dana", "long-enough-pw-1").username()).isEqualTo("dana");
        assertThatThrownBy(() -> users.create("drishti-dev-admin", "eve", new UserService.Profile("Eve", "", "", Set.of("nope"), true, null),
                "long-enough-pw-1", false)).isInstanceOf(DrishtiException.class).hasMessageContaining("unknown role");
        assertThat(bean(AuditLog.class).recent(10, "dana")).extracting(AuditLog.Event::action).contains("user-created", "login");
    }

    @Test
    void designsRoundTripInTheDatabase() {
        com.ash.drishti.identity.design.DesignStoreChecks.roundTrip(new com.ash.drishti.identity.design.JpaDesignStore(
                bean(com.ash.drishti.identity.db.IdentityRepositories.Designs.class),
                bean(com.ash.drishti.identity.db.IdentityRepositories.DesignSamples.class),
                bean(org.springframework.transaction.support.TransactionTemplate.class)));
    }

    @Test
    void sharesAndInboxRoundTripInTheDatabase() {
        com.ash.drishti.identity.collab.CollabStoreChecks.shares(bean(com.ash.drishti.identity.collab.ShareStore.class));
        com.ash.drishti.identity.collab.CollabStoreChecks.inbox(bean(com.ash.drishti.identity.collab.InboxStore.class));
    }

    @Test
    void recordNowWritesAnAccessRowAtOnceAndRollsBackWithItsTransaction() {
        AccessLog log = bean(AccessLog.class);
        var tx = bean(com.ash.drishti.identity.collab.CollabTx.class);
        String who = "rn" + System.nanoTime();
        Instant at = Instant.now();
        tx.run(() -> {
            log.recordNow(new AccessLog.Event(at, who, "share", "trade", "MX-1", "sh_X to 1", "2026-09-30"));
            return null;
        });
        assertThat(log.find(new AccessLog.Filter(who, "share", null, null, null, null, 10))).hasSize(1);
        String other = "rb" + System.nanoTime();
        assertThatThrownBy(() -> tx.run(() -> {
            log.recordNow(new AccessLog.Event(at, other, "share", "trade", "MX-1", "x", null));
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(log.find(new AccessLog.Filter(other, "share", null, null, null, null, 10))).isEmpty();
    }

    @Test
    void savedDocumentsAreBoundedAndPerUser() throws Exception {
        PreferenceStore prefs = bean(PreferenceStore.class);
        prefs.put("pat", "workspaces", "Rates", json.readTree("{\"layout\":\"2x2\",\"panes\":[\"TRD T-1\"]}"));
        prefs.put("pat", "workspaces", "credit", json.readTree("{\"layout\":\"2col\"}"));
        prefs.put("sam", "workspaces", "Rates", json.readTree("{\"layout\":\"1\"}"));
        assertThat(prefs.keys("pat", "workspaces")).containsExactly("credit", "Rates");
        assertThat(prefs.get("pat", "workspaces", "Rates").orElseThrow().path("panes").get(0).asText()).isEqualTo("TRD T-1");
        assertThatThrownBy(() -> prefs.put("pat", "workspaces", "../escape", json.readTree("{}"))).isInstanceOf(DrishtiException.class);
        assertThatThrownBy(() -> prefs.put("pat", "big", "x", json.readTree("\"" + "x".repeat(70_000) + "\""))).isInstanceOf(DrishtiException.class);
        assertThat(prefs.users()).contains("pat", "sam");
        assertThat(prefs.delete("pat", "workspaces", "credit")).isTrue();
        prefs.forget("sam");
        assertThat(prefs.keys("sam", "workspaces")).isEmpty();
        assertThat(prefs.keys("pat", "workspaces")).containsExactly("Rates");
    }

    @Test
    void auditIsNewestFirstAndFiltersBySubjectOrActor() {
        AuditLog audit = bean(AuditLog.class);
        audit.record("ops", "cache-purged", "trades", "");
        audit.record("ops", "user-updated", "lee", "roles");
        audit.record("lee", "login", "lee", "");
        assertThat(audit.recent(2, null)).extracting(AuditLog.Event::action).containsExactly("login", "user-updated");
        assertThat(audit.recent(10, "lee")).extracting(AuditLog.Event::action).containsExactly("login", "user-updated");
        assertThat(audit.recent(10, "ops")).extracting(AuditLog.Event::action).startsWith("user-updated", "cache-purged");
    }

    @Test
    void administratorsDefineRolesAndTheyAreReadFromASnapshot() {
        RoleStore roles = bean(RoleStore.class);
        roles.save(new RoleDefinition("credit-analyst", "reads credit", List.of("counterparty", "credit-curve"), false, false, false, false,
                false, false, true, false, false, null, null), "drishti-dev-admin", Set.of("admin"));
        RoleDefinition r = roles.find("credit-analyst").orElseThrow();
        assertThat(r.kinds()).containsExactly("counterparty", "credit-curve");
        assertThat(r.mayOpen("counterparty")).isTrue();
        assertThat(r.mayOpen("trade")).isFalse();
        assertThat(r.updatedBy()).isEqualTo("drishti-dev-admin");
        assertThat(r.calc()).isFalse();
        assertThat(r.layout()).as("saved without layout: the no-layout power round-trips").isFalse();

        roles.save(new RoleDefinition("credit-analyst", "reads credit and trades", List.of("*"), true, false, false, false, true, true, true, false, false, null, null),
                "ops", Set.of("admin"));
        assertThat(roles.find("credit-analyst").orElseThrow().raw()).isTrue();
        assertThat(roles.find("credit-analyst").orElseThrow().calc()).as("the calc power round-trips (drishti_role_power)").isTrue();
        roles.refresh();
        assertThat(roles.find("credit-analyst").orElseThrow().calc()).isTrue();
        assertThat(roles.find("credit-analyst").orElseThrow().layout()).isTrue();
        assertThat(roles.find("credit-analyst").orElseThrow().mayOpen("trade")).isTrue();
        assertThatThrownBy(() -> roles.save(new RoleDefinition("admin", "", List.of("*"), false, false, false, false, false, true, true, false, false, null, null), "ops",
                Set.of("admin"))).hasMessageContaining("built-in");
        assertThatThrownBy(() -> roles.save(new RoleDefinition("Bad Name", "", List.of("*"), false, false, false, false, false, true, true, false, false, null, null), "ops",
                Set.of())).isInstanceOf(DrishtiException.class);
        assertThat(roles.delete("credit-analyst", "ops")).isTrue();
        assertThat(roles.find("credit-analyst")).isEmpty();
        assertThat(bean(AuditLog.class).recent(5, "credit-analyst")).extracting(AuditLog.Event::action)
                .containsExactly("role-deleted", "role-updated", "role-created");
    }

    @Test
    void concurrentUsersAndDocumentsNeverLoseAWrite() throws Exception {
        PreferenceStore prefs = bean(PreferenceStore.class);
        UserService users = bean(UserService.class);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> work = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                int n = i;
                work.add(() -> {
                    users.create("drishti-dev-admin", "conc" + n, new UserService.Profile("C " + n, "", "", Set.of("trader"), true, null),
                            "long-enough-pw-1", false);
                    for (int k = 0; k < 5; k++) {
                        prefs.put("conc" + n, "monitors", "m" + k, json.readTree("{\"k\":" + k + "}"));
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(work)) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }
        for (int i = 0; i < 16; i++) {
            assertThat(users.list(null)).extracting(User::username).contains("conc" + i);
            assertThat(prefs.keys("conc" + i, "monitors")).hasSize(5);
        }
    }

    @Test
    void firedAlertsAreKeptNewestFirstAndPrunedPerUser() {
        AlertHistory h = bean(AlertHistory.class);
        Instant t = Instant.parse("2026-09-30T14:00:00Z");
        AlertHistory.Alert first = h.record(t, "al", "big-mtm", "trade", "T-1", "warn", "MTM above 1m", 7);
        AlertHistory.Alert second = h.record(t.plusSeconds(1), "al", "big-mtm", "trade", "T-2", "critical", "MTM above 5m", 8);
        assertThat(second.seq()).isGreaterThan(first.seq());
        assertThat(h.recent("al", 10)).extracting(AlertHistory.Alert::id).containsExactly("T-2", "T-1");
        assertThat(h.recent("al", 1).get(0).severity()).isEqualTo("critical");
        assertThat(h.recent("nobody", 10)).isEmpty();

        for (int i = 0; i < 120; i++) {
            h.record(t.plusSeconds(10 + i), "bo", "r", "trade", "T-" + i, "info", "m" + i, i);
        }
        List<AlertHistory.Alert> kept = h.recent("bo", 1000);
        assertThat(kept.size()).isBetween(60, 60 + 49);                           // pruned to the newest 60 every 50 inserts
        assertThat(kept.get(0).message()).isEqualTo("m119");
        h.forget("bo");
        assertThat(h.recent("bo", 10)).isEmpty();
        assertThat(h.recent("al", 10)).hasSize(2);
    }

    @Test
    void apiTokensKeepOnlyAHashAndStopWhenRevoked() {
        ApiTokenStore t = bean(ApiTokenStore.class);
        ApiTokenStore.Created c = t.create("tess", "Notebook", 7);
        assertThat(c.secret()).matches("drk_[A-Za-z0-9]{12}_[A-Za-z0-9_-]{43}");
        assertThat(t.verify(c.secret())).contains("tess");
        assertThat(t.verify(c.secret() + "x")).isEmpty();
        assertThat(t.verify("drk_nothing")).isEmpty();
        assertThat(t.of("tess")).singleElement().satisfies(v -> {
            assertThat(v.name()).isEqualTo("Notebook");
            assertThat(v.expiresAt()).isAfter(v.createdAt());
            assertThat(v.active()).isTrue();
        });
        assertThat(t.revoke(c.token().id(), "someone-else", "x")).isFalse();      // only its owner (or an admin)
        assertThat(t.revoke(c.token().id(), "tess", "tess")).isTrue();
        assertThat(t.verify(c.secret())).isEmpty();
        assertThatThrownBy(() -> t.create("tess", " ", null)).isInstanceOf(DrishtiException.class);
        assertThatThrownBy(() -> t.create("tess", "x", 999)).isInstanceOf(DrishtiException.class);
    }

    @Test
    void consoleSessionsEndAtSignOutAndForTheirUser() {
        SessionStore s = bean(SessionStore.class);
        SessionStore.Session a = s.open("sam", java.time.Duration.ofHours(1));
        SessionStore.Session b = s.open("sam", java.time.Duration.ofHours(1));
        SessionStore.Session c = s.open("kim", java.time.Duration.ofHours(1));
        assertThat(a.id()).isNotEqualTo(b.id()).hasSizeGreaterThanOrEqualTo(32);
        assertThat(s.find(a.id())).get().extracting(SessionStore.Session::user).isEqualTo("sam");
        assertThat(s.find(a.id() + "x")).isEmpty();
        assertThat(s.end(a.id())).isTrue();                              // sign-out
        assertThat(s.find(a.id())).isEmpty();
        assertThat(s.end(a.id())).isFalse();
        assertThat(s.endAll("sam", "admin", "disabled")).isEqualTo(1);    // every other session of the user
        assertThat(s.find(b.id())).isEmpty();
        assertThat(s.find(c.id())).isPresent();
        assertThatThrownBy(() -> s.open("kim", java.time.Duration.ofDays(30))).isInstanceOf(DrishtiException.class);
        assertThat(bean(AuditLog.class).recent(10, "sam")).extracting(AuditLog.Event::action).contains("signed-out", "sessions-ended");
    }

    @Test
    void notesBelongToTheirEntityAndOnlyTheirAuthorEditsThem() {
        NoteStore n = bean(NoteStore.class);
        NoteStore.Note a = n.add("trade", "T-NOTE-1", null, "tess", "  Restated on 28 Sep after the fixing correction. ");
        NoteStore.Note b = n.add("trade", "T-NOTE-1", "$.mtm", "ravi", "MTM includes the CVA adjustment");
        n.add("trade", "T-NOTE-2", null, "tess", "another trade");
        assertThat(n.of("trade", "T-NOTE-1")).extracting(NoteStore.Note::body)
                .containsExactly("Restated on 28 Sep after the fixing correction.", "MTM includes the CVA adjustment");
        assertThat(n.of("trade", "T-NOTE-1").get(1).path()).isEqualTo("$.mtm");
        assertThatThrownBy(() -> n.edit(a.id(), "ravi", "mine now")).isInstanceOf(DrishtiException.class);
        assertThat(n.edit(a.id(), "tess", "Restated twice").body()).isEqualTo("Restated twice");
        assertThatThrownBy(() -> n.delete(b.id(), "tess", false)).isInstanceOf(DrishtiException.class);
        n.delete(b.id(), "tess", true);                                         // an administrator may
        assertThat(n.of("trade", "T-NOTE-1")).hasSize(1);
        assertThatThrownBy(() -> n.add("trade", "T-NOTE-1", null, "tess", "  ")).isInstanceOf(DrishtiException.class);
        assertThatThrownBy(() -> n.add("trade", "T-NOTE-1", "mtm", "tess", "x")).isInstanceOf(DrishtiException.class);
        assertThatThrownBy(() -> n.add("trade", "T-NOTE-1", null, "tess", "x".repeat(2001))).isInstanceOf(DrishtiException.class);
        assertThat(bean(JpaAuditLog.class).recent(50, "trade/T-NOTE-1")).extracting(AuditLog.Event::action)
                .contains("note.add", "note.edit", "note.delete");
    }

    @Test
    void theAccessLogRecordsReadsAndFindsThemByEntityUserAndTime() {
        AccessLog log = bean(AccessLog.class);
        java.time.Instant t0 = java.time.Instant.parse("2026-09-30T14:00:00Z");
        log.record(new AccessLog.Event(t0, "tess", "view", "trade", "T-ACC-1", null, null));
        log.record(new AccessLog.Event(t0.plusSeconds(60), "ravi", "raw", "trade", "T-ACC-1", null, "2026-09-29"));
        log.record(new AccessLog.Event(t0.plusSeconds(120), "tess", "search", "trade", null, "TRD where mtm < 0", null));
        log.record(new AccessLog.Event(java.time.Instant.now().minus(java.time.Duration.ofDays(400)), "old", "view", "trade", "T-ACC-1", null, null));
        log.flush();
        assertThat(log.find(new AccessLog.Filter(null, null, "trade", "T-ACC-1", t0.minusSeconds(1), null, 50)))
                .extracting(AccessLog.Event::user).containsExactly("ravi", "tess");                 // newest first
        assertThat(log.find(new AccessLog.Filter("tess", "search", null, null, null, null, 50))).singleElement()
                .satisfies(e -> assertThat(e.detail()).isEqualTo("TRD where mtm < 0"));
        assertThat(log.find(new AccessLog.Filter("ravi", null, null, null, null, null, 50)).get(0).businessDate()).isEqualTo("2026-09-29");
        assertThat(log.prune()).isGreaterThanOrEqualTo(1);                                         // older than the retention
        assertThat(log.find(new AccessLog.Filter("old", null, null, null, null, null, 50))).isEmpty();
        assertThat(log.stats()).containsEntry("dropped", 0L);
    }
}
