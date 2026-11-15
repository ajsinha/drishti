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

import com.ash.drishti.net.config.ClusterSettings;
import com.ash.drishti.net.config.HostAddress;
import com.ash.drishti.net.member.Membership;
import com.ash.drishti.net.member.MembershipContract;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

/**
 * {@link JGroupsMembership} passes the membership contract on this machine's real address (never loopback), each member on its own cluster
 * port; the join token keeps a node without the key out. Ports: {@code drishti.net.it.cluster-ports} (the first of a block of six, default
 * 18850; the failure detector takes each port + 100), so a drill's stage and a builder's run never collide.
 */
public class JGroupsMembershipTest extends MembershipContract {

    static final String KEY = "a-test-cluster-key-of-at-least-32-bytes";
    static final String HOST = HostAddress.primary().orElse(null);
    static final int BASE = Integer.getInteger("drishti.net.it.cluster-ports", 18850);

    /** Short timings so the suite takes seconds: heartbeat 500 ms, suspect after 2 s, verify 500 ms. */
    static ClusterSettings settings() {
        Map<String, String> m = Map.of("net.membership.heartbeat", "500ms", "net.membership.suspect-after", "2s", "net.membership.verify", "500ms",
                "net.membership.join-timeout", "2s", "net.membership.merge-every", "2s");
        ClusterSettings.Read r = ClusterSettings.read(m::get);
        assertThat(r.problems()).isEmpty();
        return r.settings();
    }

    /** Members of one fresh cluster name on ports {@code BASE + offset ...}, each seeded with all of them. */
    static List<JGroupsMembership> members(int size, int offset, String key, SSLContext tls) {
        assumeTrue(HOST != null, "this machine has no address but loopback: a cluster member never binds one");
        String cluster = "test-" + UUID.randomUUID();
        List<InetSocketAddress> seeds = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            seeds.add(new InetSocketAddress(HOST, BASE + offset + i));
        }
        List<JGroupsMembership> out = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            JGroupsStack.Spec spec = new JGroupsStack.Spec(cluster, HOST, BASE + offset + i, seeds, settings(), tls, true, key, "");
            out.add(new JGroupsMembership("n" + i, "server", spec));
        }
        return out;
    }

    @Override
    protected Group group(int size) {
        List<JGroupsMembership> members = members(size, 0, KEY, null);
        return new Group() {
            @Override
            public Membership member(int i) {
                return members.get(i);
            }

            @Override
            public int size() {
                return members.size();
            }

            @Override
            public void kill(int i) {
                members.get(i).crash();
            }

            @Override
            public void close() {
                members.forEach(JGroupsMembership::close);
            }
        };
    }

    @Override
    protected Duration killDetection() {
        return Duration.ofSeconds(8);
    }

    @Test
    void aNodeWithoutTheClusterKeyNeverEntersTheView() throws Exception {
        List<JGroupsMembership> good = members(2, 3, KEY, null);
        JGroupsStack.Spec bad = new JGroupsStack.Spec(clusterOf(good), HOST, BASE + 5,
                List.of(new InetSocketAddress(HOST, BASE + 3)), settings(), null, true, "another-key-of-at-least-32-bytes-long", "");
        JGroupsMembership intruder = new JGroupsMembership("intruder", "server", bad);
        try {
            good.forEach(JGroupsMembership::join);
            assertThat(await(Duration.ofSeconds(20), () -> good.get(0).view().size() == 2)).isTrue();
            try {
                intruder.join();
            } catch (IllegalStateException refused) {
                // refused by AUTH at the coordinator: the expected outcome
            }
            Thread.sleep(3000);
            assertThat(good.get(0).view().contains("intruder")).isFalse();
            assertThat(good.get(1).view().contains("intruder")).isFalse();
        } finally {
            intruder.close();
            good.forEach(JGroupsMembership::close);
        }
    }

    private static String clusterOf(List<JGroupsMembership> members) {
        return members.get(0).clusterName();
    }
}
