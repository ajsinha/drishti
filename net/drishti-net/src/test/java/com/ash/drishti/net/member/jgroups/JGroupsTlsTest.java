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
package com.ash.drishti.net.member.jgroups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.drishti.net.security.TestCerts;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The membership over mutual TLS (CLUSTER_I5_DESIGN.md section 10; spike C0): members with certificates of the cluster's authority form one
 * view and exchange vitals; a member whose certificate another authority signed never enters the view.
 */
class JGroupsTlsTest {

    @TempDir
    Path dir;

    @Test
    void membersWithTheClustersCertificatesJoinAndAStrangersCertificateDoesNot() throws Exception {
        String host = JGroupsMembershipTest.HOST;
        assumeTrue(host != null, "this machine has no address but loopback");
        int base = JGroupsMembershipTest.BASE + 6;
        TestCerts ca = TestCerts.authority(dir.resolve("ca"), "cluster-ca");
        TestCerts other = TestCerts.authority(dir.resolve("other"), "other-ca");
        String cluster = "tls-" + UUID.randomUUID();
        List<InetSocketAddress> seeds = List.of(new InetSocketAddress(host, base), new InetSocketAddress(host, base + 1));
        List<JGroupsMembership> members = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            TestCerts.Issued cert = ca.issue("n" + i, host);
            members.add(new JGroupsMembership("n" + i, "server", new JGroupsStack.Spec(cluster, host, base + i, seeds,
                    JGroupsMembershipTest.settings(), cert.tls().context(), true, JGroupsMembershipTest.KEY, "")));
        }
        TestCerts.Issued stranger = other.issue("stranger", host);
        JGroupsMembership intruder = new JGroupsMembership("stranger", "server", new JGroupsStack.Spec(cluster, host, base + 2, seeds,
                JGroupsMembershipTest.settings(), stranger.tls().context(), true, JGroupsMembershipTest.KEY, ""));
        try {
            members.forEach(JGroupsMembership::join);
            assertThat(MembershipContractAccess.await(Duration.ofSeconds(20), () -> members.get(0).view().size() == 2 && members.get(1).view().size() == 2))
                    .isTrue();
            List<String> got = Collections.synchronizedList(new ArrayList<>());
            members.get(1).onMessage("vitals", (from, b) -> got.add(from + ":" + new String(b, StandardCharsets.UTF_8)));
            members.get(0).publish("vitals", "over-tls".getBytes(StandardCharsets.UTF_8));
            assertThat(MembershipContractAccess.await(Duration.ofSeconds(5), () -> got.contains("n0:over-tls"))).isTrue();
            try {
                intruder.join();
            } catch (IllegalStateException refused) {
                // the handshake fails: the expected outcome
            }
            Thread.sleep(3000);
            assertThat(members.get(0).view().contains("stranger")).isFalse();
        } finally {
            intruder.close();
            members.forEach(JGroupsMembership::close);
        }
    }

    /** The contract's polling wait, for a test outside the contract. */
    static final class MembershipContractAccess {
        private MembershipContractAccess() {
        }

        static boolean await(Duration max, java.util.function.BooleanSupplier ok) throws InterruptedException {
            long end = System.nanoTime() + max.toNanos();
            while (System.nanoTime() < end) {
                if (ok.getAsBoolean()) {
                    return true;
                }
                Thread.sleep(50);
            }
            return ok.getAsBoolean();
        }
    }
}
