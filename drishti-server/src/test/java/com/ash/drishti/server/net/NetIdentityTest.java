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

import com.ash.drishti.identity.ApiTokenStore;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.RoleStore;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The identity across nodes (DRISHTI_NET.md section 11) on three real servers in one JVM, security on: a user an administrator creates on
 * A (with a role defined on A and an API token) signs in through the console's calls on A, A stops, and the console keeps them signed in
 * on B; a user disabled and another deleted on B while A is down are disabled and deleted on A when it comes back (A's stale copies never
 * bring them back on B); a node that joins later is given the users, the role and the deletions, and keeps the cluster's administrator
 * over its own freshly seeded one.
 */
class NetIdentityTest {

    static final String TOKEN_SECRET = "a-test-token-secret-of-at-least-32-bytes";
    static final String ADMIN = "drishti-dev-admin";
    static NetNodes cluster;
    static Path root;
    static final ObjectMapper JSON = new ObjectMapper();

    static final String[] SECURITY = {"--drishti.security.enabled=true", "--drishti.security.secret=" + TOKEN_SECRET};

    @BeforeAll
    static void start() throws Exception {
        root = Path.of("target", "net-it", "identity-" + System.nanoTime()).toAbsolutePath();
        int[] p = NetNodes.PORTS;
        NetNodes.folder(root.resolve("a/net"), p[0], List.of(), List.of(), List.of(p[1]));
        NetNodes.folder(root.resolve("b/net"), p[1], List.of(), List.of(), List.of(p[0]));
        NetNodes.folder(root.resolve("c/net"), p[2], List.of(), List.of(), List.of(p[1]));
        cluster = new NetNodes(root);
        cluster.start("a", p[0], SECURITY);
        cluster.start("b", p[1], SECURITY);
        assertThat(NetNodes.await(10_000, () -> alive("a") == 1 && alive("b") == 1)).isTrue();
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

    /** A token as the console mints it, signed with the token secret every node shares. */
    static String token(String node, String user, String... roles) {
        return cluster.node(node).bean(TokenVerifier.class).mint(user, List.of(roles), 300);
    }

    static HttpResponse<String> call(String node, String method, String path, String body, String bearer) throws Exception {
        return cluster.call(method, cluster.node(node).url() + path, body, "Authorization", "Bearer " + bearer);
    }

    static int sessionCheck(String node, String id) throws Exception {
        return call(node, "GET", "/api/v1/auth/sessions/" + id, null, token(node, "console", Entitlements.SERVICE)).statusCode();
    }

    @Test
    void aUserKnownToOneNodeStaysSignedInAfterTheConsoleFallsOver() throws Exception {
        String admin = token("a", ADMIN, "admin");
        // an administrator defines a role and creates two users on A
        var role = call("a", "PUT", "/api/v1/admin/role-definitions/desk-emea", "{\"description\":\"EMEA desk\",\"kinds\":[\"*\"]}", admin);
        assertThat(role.statusCode()).as(role.body()).isEqualTo(200);
        for (String u : List.of("rita", "sam")) {
            var made = call("a", "POST", "/api/v1/admin/users", "{\"username\":\"" + u + "\",\"displayName\":\"" + u
                    + "\",\"roles\":[\"desk-emea\"],\"password\":\"s3cret-pass-1\",\"mustChangePassword\":false}", admin);
            assertThat(made.statusCode()).as(made.body()).isEqualTo(201);
        }
        // rita signs in through the console on A: the console verifies her password and opens a session there
        String service = token("a", "console", Entitlements.SERVICE);
        var login = call("a", "POST", "/api/v1/auth/login", "{\"username\":\"rita\",\"password\":\"s3cret-pass-1\"}", service);
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        var opened = call("a", "POST", "/api/v1/auth/sessions", "{\"username\":\"rita\",\"seconds\":3600}", service);
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        String session = JSON.readTree(opened.body()).path("id").asText();
        // and a script of hers holds an API token made on A
        String apiToken = cluster.node("a").bean(ApiTokenStore.class).create("rita", "nightly report", 30).secret();

        UserService onB = cluster.node("b").bean(UserService.class);
        assertThat(NetNodes.await(5_000, () -> onB.find("sam").isPresent() && onB.find("rita").map(u -> u.lastLoginAt() != null).orElse(false)))
                .as("the users made on A are on B, with the sign-in").isTrue();
        assertThat(onB.find("rita").orElseThrow().passwordHash()).isEqualTo(cluster.node("a").bean(UserService.class).find("rita").orElseThrow().passwordHash());
        assertThat(cluster.node("b").bean(RoleStore.class).find("desk-emea")).as("the role defined on A").isPresent();
        assertThat(NetNodes.await(5_000, () -> cluster.node("b").bean(ApiTokenStore.class).verify(apiToken).isPresent())).isTrue();
        assertThat(cluster.node("b").bean(AuditLog.class).recent(200, "rita")).anySatisfy(e -> assertThat(e.actor()).startsWith("net:"));

        // A stops: the console falls over to B, and rita is still signed in there
        cluster.stop("a");
        assertThat(sessionCheck("b", session)).as("the console's session check on B").isEqualTo(200);
        var me = call("b", "GET", "/api/v1/auth/me", null, token("b", "rita", "desk-emea"));
        assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
        assertThat(me.body()).contains("desk-emea");
        var script = call("b", "GET", "/api/v1/auth/me", null, apiToken);
        assertThat(script.statusCode()).as("her API token on B: " + script.body()).isEqualTo(200);
        var again = call("b", "POST", "/api/v1/auth/login", "{\"username\":\"rita\",\"password\":\"s3cret-pass-1\"}", token("b", "console", Entitlements.SERVICE));
        assertThat(again.statusCode()).as("she can sign in again on B with her password").isEqualTo(200);

        // while A is down, an administrator on B disables rita and deletes sam
        String adminB = token("b", ADMIN, "admin");
        assertThat(call("b", "POST", "/api/v1/admin/users/rita/enabled", "{\"enabled\":false}", adminB).statusCode()).isEqualTo(200);
        assertThat(call("b", "DELETE", "/api/v1/admin/users/sam", null, adminB).statusCode()).isEqualTo(204);
        assertThat(sessionCheck("b", session)).isEqualTo(401);

        // A comes back with its stale copies: it learns both, and its copies bring nobody back on B
        cluster.start("a", NetNodes.PORTS[0], SECURITY);
        UserService onA = cluster.node("a").bean(UserService.class);
        assertThat(NetNodes.await(10_000, () -> onA.find("sam").isEmpty() && onA.find("rita").map(u -> !u.enabled()).orElse(false)))
                .as("A learns the deletion and the disabling made while it was down").isTrue();
        assertThat(sessionCheck("a", session)).as("her session does not stand on A either").isEqualTo(401);
        assertThat(call("a", "GET", "/api/v1/auth/me", null, apiToken).statusCode()).as("nor her token").isEqualTo(401);
        Thread.sleep(1_500);                               // A's catch-up has reached B by now
        assertThat(onB.find("sam")).as("A's stale copy did not bring sam back on B").isEmpty();
        assertThat(onB.find("rita").orElseThrow().enabled()).isFalse();

        // a node that joins later is given the users, the role and the deletion; the cluster's administrator stands over its seeded one
        cluster.start("c", NetNodes.PORTS[2], SECURITY);
        UserService onC = cluster.node("c").bean(UserService.class);
        String clusterAdmin = onB.find(ADMIN).orElseThrow().passwordHash();
        assertThat(NetNodes.await(10_000, () -> onC.find("rita").isPresent()
                && onC.find(ADMIN).map(u -> u.passwordHash().equals(clusterAdmin)).orElse(false))).isTrue();
        assertThat(onC.find("rita").orElseThrow().enabled()).isFalse();
        assertThat(onC.find("sam")).isEmpty();
        assertThat(cluster.node("c").bean(RoleStore.class).find("desk-emea")).isPresent();
        Thread.sleep(1_500);
        assertThat(onB.find(ADMIN).orElseThrow().passwordHash()).as("C's seeded administrator replaced nobody").isEqualTo(clusterAdmin);
    }
}
