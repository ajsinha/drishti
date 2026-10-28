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
package com.ash.drishti.server.net;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.User;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.net.member.MemberState;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The cluster never ends with no enabled administrator (DRISHTI_NET.md section 11), on three real servers in one JVM, security on. The
 * last-administrator rule is checked where a change is made, so two nodes can each take away one of the last two administrators at the
 * same moment (their copying is held while they do): when the changes cross, every node keeps the same one enabled (the most recently
 * enabled), audits it and shows it on Admin → Health. Two deletions at the same moment keep both administrators, as new users.
 */
class NetLastAdminTest {

    static final String[] SECURITY = {"--drishti.security.enabled=true", "--drishti.security.secret=a-test-token-secret-of-at-least-32-bytes"};
    static final String SEEDED = "drishti-dev-admin";
    static final List<String> NODES = List.of("a", "b", "c");
    static NetNodes cluster;

    @BeforeAll
    static void start() throws Exception {
        Path root = com.ash.drishti.server.TestRun.root().resolve(Path.of("net-it", "last-admin-" + System.nanoTime()));
        int[] p = NetNodes.PORTS;
        NetNodes.folder(root.resolve("a/net"), p[0], List.of(), List.of(), List.of(p[1], p[2]));
        NetNodes.folder(root.resolve("b/net"), p[1], List.of(), List.of(), List.of(p[0], p[2]));
        NetNodes.folder(root.resolve("c/net"), p[2], List.of(), List.of(), List.of(p[0], p[1]));
        cluster = new NetNodes(root);
        cluster.start("a", p[0], SECURITY);
        cluster.start("b", p[1], SECURITY);
        cluster.start("c", p[2], SECURITY);
        assertThat(NetNodes.await(15_000, () -> NODES.stream().allMatch(n -> alive(n) == 2))).as("each node sees the other two alive").isTrue();
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    static long alive(String node) {
        return cluster.node(node).net().node().map(n -> n.table().alive().size()).orElse(0);
    }

    static UserService users(String node) {
        return cluster.node(node).bean(UserService.class);
    }

    /** The enabled administrators a node holds. */
    static Set<String> admins(String node) {
        return users(node).list("").stream().filter(u -> u.enabled() && u.roles().contains("admin")).map(User::username)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    static boolean everywhere(Set<String> enabledAdmins) {
        return NODES.stream().allMatch(n -> admins(n).equals(enabledAdmins));
    }

    static void hold(boolean on) {
        for (String n : List.of("a", "b")) {
            cluster.node(n).bean(StateSync.class).hold(on);
        }
    }

    @Test
    void twoNodesTakingAwayTheLastTwoAdministratorsAtOnceLeaveOneEnabledEverywhere() throws Exception {
        UserService a = users("a");
        for (String u : List.of("ada", "bob")) {
            a.create("test", u, new UserService.Profile(u, null, null, Set.of("admin"), true), "s3cret-pass-1", false);
        }
        assertThat(NetNodes.await(10_000, () -> NODES.stream().allMatch(n -> admins(n).containsAll(Set.of("ada", "bob")))))
                .as("ada and bob are administrators on every node").isTrue();
        // A disables the seeded administrator while it suspects C for a moment (a busy server's pause, as under a loaded test run): the
        // change is queued for C all the same and sent once C is heard again (a suspect member heard again never joins again, so no
        // catch-up would bring it; before 2.9.6 it was lost and C kept the seeded administrator enabled)
        StateSync sa = cluster.node("a").bean(StateSync.class);
        String cId = cluster.node("c").net().nodeId();
        sa.hold(true);
        int before = waiting(sa, cId);
        cluster.node("a").net().node().orElseThrow().table().mark(cId, MemberState.SUSPECT);
        a.setEnabled("test", SEEDED, false);
        assertThat(waiting(sa, cId)).as("the change is queued for the suspected member").isGreaterThan(before);
        sa.hold(false);
        converge(30_000, () -> everywhere(Set.of("ada", "bob")), "only ada and bob left enabled");

        // at the same moment A disables ada and B disables bob: each is allowed where it is made, one other administrator being left
        hold(true);
        a.setEnabled("test", "ada", false);
        Thread.sleep(20);
        users("b").setEnabled("test", "bob", false);
        assertThat(admins("a")).containsExactly("bob");
        assertThat(admins("b")).containsExactly("ada");
        hold(false);

        // the changes cross: every node keeps bob, the most recently enabled (disabled last), and agrees
        converge(10_000, () -> everywhere(Set.of("bob")), "bob kept enabled everywhere");
        Thread.sleep(1_500);
        assertThat(everywhere(Set.of("bob"))).as("and it stays so: %s", detail()).isTrue();
        assertThat(users("c").find("ada").orElseThrow().enabled()).isFalse();
        assertThat(NODES.stream().anyMatch(n -> cluster.node(n).bean(AuditLog.class).recent(50, "bob").stream()
                .anyMatch(e -> e.action().equals("last-admin-kept")))).as("the repair is audited").isTrue();
        assertThat(NODES.stream().anyMatch(n -> !((List<?>) cluster.node(n).bean(NetHealth.class).summary().get("warnings")).isEmpty()))
                .as("and shown on Admin → Health").isTrue();

        // two deletions at the same moment: ada is enabled again, then A deletes ada and B deletes bob
        a.setEnabled("test", "ada", true);
        converge(10_000, () -> everywhere(Set.of("ada", "bob")), "ada enabled again everywhere");
        hold(true);
        a.delete("test", "ada");
        Thread.sleep(20);
        users("b").delete("test", "bob");
        hold(false);
        converge(10_000, () -> NODES.stream().noneMatch(n -> admins(n).isEmpty()) && NODES.stream().map(NetLastAdminTest::admins).distinct()
                .count() == 1, "an administrator stands on every node, the same");
        Thread.sleep(1_500);
        assertThat(NODES.stream().map(NetLastAdminTest::admins).distinct().toList()).as(state().toString()).hasSize(1);
        assertThat(admins("a")).isNotEmpty();
    }

    /** The changes a node holds for a peer, not sent yet. */
    static int waiting(StateSync s, String peer) {
        Object n = ((Map<?, ?>) s.summary().get("waiting")).get(peer);
        return n == null ? 0 : (Integer) n;
    }

    /** Waits for the cluster to converge; when it does not, says what each node holds then (not when the wait began). */
    static void converge(long ms, java.util.function.BooleanSupplier ok, String what) throws InterruptedException {
        if (!NetNodes.await(ms, ok)) {
            org.junit.jupiter.api.Assertions.fail(what + " within " + ms + " ms; now: " + detail());
        }
    }

    /** Each node's enabled administrators, every administrator's record (enabled, created, updated) and what its user state has sent. */
    static String detail() {
        StringBuilder b = new StringBuilder();
        for (String n : NODES) {
            b.append("\n  ").append(n).append(' ').append(cluster.node(n).net().nodeId()).append(": ").append(admins(n));
            users(n).list("").stream().filter(u -> u.roles().contains("admin")).forEach(u -> b.append("\n    ").append(u.username())
                    .append(" enabled=").append(u.enabled()).append(" created=").append(u.createdAt()).append(" updated=").append(u.updatedAt()));
            b.append("\n    sync ").append(cluster.node(n).bean(StateSync.class).summary());
        }
        return b.toString();
    }

    static Map<String, Set<String>> state() {
        return NODES.stream().collect(Collectors.toMap(n -> n, NetLastAdminTest::admins));
    }
}
