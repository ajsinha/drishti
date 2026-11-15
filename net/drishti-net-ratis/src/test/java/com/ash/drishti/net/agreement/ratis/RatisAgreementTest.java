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
package com.ash.drishti.net.agreement.ratis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.drishti.net.agreement.Agreement;
import com.ash.drishti.net.agreement.AgreementContract;
import com.ash.drishti.net.config.ClusterSettings;
import com.ash.drishti.net.config.HostAddress;
import com.ash.drishti.net.security.TestCerts;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link RatisAgreement} passes the agreement contract (CLUSTER_I5_DESIGN.md section 3; spike C0): three voters on this machine's real
 * address, mutual TLS with certificates of a private authority made by {@code keytool}, logs and snapshots under the test's folder. Without
 * TLS it refuses to start. Ports: {@code drishti.net.it.agreement-ports} (the first of three, default 18870).
 */
class RatisAgreementTest extends AgreementContract {

    private static final String HOST = HostAddress.primary().orElse(null);
    private static final int BASE = Integer.getInteger("drishti.net.it.agreement-ports", 18870);

    @TempDir
    Path dir;

    private static ClusterSettings.AgreementSettings settings(List<String> voters, Duration ttl, Duration renew) {
        return new ClusterSettings.AgreementSettings("ratis", voters, "", ttl, renew, Duration.ofSeconds(1), 1000);
    }

    @Override
    protected Nodes start(List<String> ids, Duration ttl, Duration renew) throws Exception {
        assumeTrue(HOST != null, "this machine has no address but loopback");
        TestCerts ca = TestCerts.authority(dir.resolve("ca-" + UUID.randomUUID()), "cluster-ca");
        Map<String, InetSocketAddress> voters = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            voters.put(ids.get(i), new InetSocketAddress(HOST, BASE + i));
        }
        String cluster = "ratis-" + UUID.randomUUID();
        Path logs = Files.createDirectories(dir.resolve("agreement-" + UUID.randomUUID()));
        Map<String, RatisAgreement> nodes = new LinkedHashMap<>();
        for (String id : ids) {
            nodes.put(id, new RatisAgreement(id, voters, cluster, settings(ids, ttl, renew), ca.issue(id, HOST).tls(), logs));
        }
        assertThat(AgreementContract.await(Duration.ofSeconds(30), () -> nodes.values().stream().allMatch(a -> a.health().decides())))
                .as("the group elects a leader and answers every node").isTrue();
        return new Nodes() {
            @Override
            public Agreement node(String id) {
                return nodes.get(id);
            }

            @Override
            public void crash(String id) {
                nodes.get(id).halt();
            }

            @Override
            public void loseReferee(String survivor) {
                nodes.forEach((id, a) -> {
                    if (!id.equals(survivor)) {
                        a.halt();
                    }
                });
            }

            @Override
            public void close() {
                nodes.values().forEach(RatisAgreement::close);
            }
        };
    }

    @Override
    protected Duration refereeLoss() {
        return Duration.ofSeconds(15);
    }

    @Test
    void withoutTlsTheRatisModeRefusesToStart() {
        Map<String, InetSocketAddress> voters = Map.of("a", new InetSocketAddress("10.0.0.1", 7947), "b", new InetSocketAddress("10.0.0.2", 7947),
                "c", new InetSocketAddress("10.0.0.3", 7947));
        assertThatThrownBy(() -> new RatisAgreement("a", voters, "x", settings(List.of("a", "b", "c"), Duration.ofSeconds(15), Duration.ofSeconds(5)), null,
                dir)).isInstanceOf(IllegalStateException.class).hasMessageContaining("mutual TLS");
    }

    @Test
    void theStateSurvivesARestartOfEveryVoterFromTheLogAndSnapshots() throws Exception {
        assumeTrue(HOST != null, "this machine has no address but loopback");
        TestCerts ca = TestCerts.authority(dir.resolve("ca-restart"), "cluster-ca");
        List<String> ids = List.of("a", "b", "c");
        Map<String, InetSocketAddress> voters = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            voters.put(ids.get(i), new InetSocketAddress(HOST, BASE + i));
        }
        Path logs = Files.createDirectories(dir.resolve("restart"));
        Map<String, TestCerts.Issued> certs = new LinkedHashMap<>();
        for (String id : ids) {
            certs.put(id, ca.issue(id, HOST));
        }
        Map<String, RatisAgreement> first = new LinkedHashMap<>();
        for (String id : ids) {
            first.put(id, new RatisAgreement(id, voters, "restart", settings(ids, Duration.ofSeconds(2), Duration.ofMillis(400)), certs.get(id).tls(), logs));
        }
        assertThat(AgreementContract.await(Duration.ofSeconds(30), () -> first.values().stream().allMatch(a -> a.health().decides()))).isTrue();
        for (int k = 0; k < 1200; k++) {                        // past snapshot-every (1000): a snapshot is taken
            assertThat(first.get("a").register().compareAndSet("k" + (k % 7), first.get("a").register().get("k" + (k % 7)).map(e -> e.version()).orElse(0L),
                    ("v" + k).getBytes(StandardCharsets.UTF_8), "ada")).isTrue();
        }
        first.values().forEach(RatisAgreement::close);
        Map<String, RatisAgreement> again = new LinkedHashMap<>();
        try {
            for (String id : ids) {
                again.put(id, new RatisAgreement(id, voters, "restart", settings(ids, Duration.ofSeconds(2), Duration.ofMillis(400)), certs.get(id).tls(), logs));
            }
            assertThat(AgreementContract.await(Duration.ofSeconds(30), () -> again.values().stream().allMatch(a -> a.health().decides()))).isTrue();
            assertThat(again.get("b").register().get("k0").orElseThrow().text()).isEqualTo("v1197");
        } finally {
            again.values().forEach(RatisAgreement::close);
        }
    }
}
