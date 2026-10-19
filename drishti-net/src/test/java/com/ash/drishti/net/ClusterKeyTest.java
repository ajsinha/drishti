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
package com.ash.drishti.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.net.config.AccessSettings;
import com.ash.drishti.net.member.NetClock;
import com.ash.drishti.net.security.ClusterKey;
import com.ash.drishti.net.security.OnBehalfOf;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The cluster credential, its rollover, on-behalf-of tokens and nodes per role (DRISHTI_NET.md section 9). */
class ClusterKeyTest {

    private static final String OLD = "the-old-cluster-key-at-least-32-bytes-long";
    private static final String NEW = "the-new-cluster-key-at-least-32-bytes-long";
    private final AtomicLong now = new AtomicLong(1_760_000_000_000L);
    private final NetClock clock = now::get;

    private String sig(ClusterKey k, String path) {
        return k.sign("GET", path, now.get(), "n-a", ClusterKey.bodyHash(null));
    }

    @Test
    void aCallSignedWithTheKeyIsTakenAndAnyOtherRefused() {
        ClusterKey k = new ClusterKey(NEW, "", clock);
        String s = sig(k, "/api/v1/net/ping");
        assertThat(k.verify("GET", "/api/v1/net/ping", Long.toString(now.get()), "n-a", ClusterKey.bodyHash(null), s).ok()).isTrue();
        // another path, node or body: refused
        assertThat(k.verify("GET", "/api/v1/net/digest", Long.toString(now.get()), "n-a", ClusterKey.bodyHash(null), s).ok()).isFalse();
        assertThat(k.verify("GET", "/api/v1/net/ping", Long.toString(now.get()), "n-b", ClusterKey.bodyHash(null), s).ok()).isFalse();
        assertThat(k.verify("GET", "/api/v1/net/ping", Long.toString(now.get()), "n-a", ClusterKey.bodyHash("x".getBytes()), s).ok()).isFalse();
        // a node without the key
        ClusterKey stranger = new ClusterKey("someone-elses-key-that-is-32-bytes-or-more", "", clock);
        assertThat(k.verify("GET", "/p", Long.toString(now.get()), "n-a", ClusterKey.bodyHash(null), sig(stranger, "/p")).reason()).contains("bad signature");
        // a replay outside the window
        long then = now.get();
        String old = sig(k, "/p");
        now.addAndGet(61_000);
        assertThat(k.verify("GET", "/p", Long.toString(then), "n-a", ClusterKey.bodyHash(null), old).reason()).contains("60 s");
    }

    @Test
    void aRollingKeyChangeInThreeStepsNeverRefusesACallAndSaysWhoStillUsesTheOld() {
        // the three steps of docs/admin/NET.md: accept the new key, sign with it, drop the old one; each a rolling restart
        ClusterKey[] steps = {new ClusterKey(OLD, "", clock), new ClusterKey(OLD, NEW, clock), new ClusterKey(NEW, OLD, clock), new ClusterKey(NEW, "", clock)};
        // during each rollout, nodes are at step i or i+1: every call between them is taken
        for (int i = 0; i + 1 < steps.length; i++) {
            for (ClusterKey a : new ClusterKey[] {steps[i], steps[i + 1]}) {
                for (ClusterKey b : new ClusterKey[] {steps[i], steps[i + 1]}) {
                    assertThat(b.verify("GET", "/p", Long.toString(now.get()), "n-a", ClusterKey.bodyHash(null), sig(a, "/p")).ok())
                            .as("rollout %d", i + 1).isTrue();
                }
            }
        }
        // a node at step 2 that hears the old key remembers who used it: the rollover is complete when nobody has for a while
        ClusterKey.Verdict v = steps[2].verify("GET", "/p", Long.toString(now.get()), "n-a", ClusterKey.bodyHash(null), sig(steps[1], "/p"));
        assertThat(v.previous()).isTrue();
        assertThat(steps[2].previousUses()).containsKey("n-a");
        assertThat(steps[2].withKeys(NEW, OLD).previousUses()).containsKey("n-a");   // a reload keeps the rollover's progress
        assertThat(steps[2].withKeys(NEW, "").previousUses()).isEmpty();
        // skipping the first step would refuse calls: a node with only the old key cannot read the new one
        assertThat(steps[0].verify("GET", "/p", Long.toString(now.get()), "n-a", ClusterKey.bodyHash(null), sig(steps[2], "/p")).ok()).isFalse();
    }

    @Test
    void anOnBehalfOfTokenLivesThirtySecondsForOneNode() {
        ClusterKey k = new ClusterKey(NEW, "", clock);
        String t = OnBehalfOf.mint(k, "rita", List.of("risk"), null, "n-b", "r-1", "n-a");
        OnBehalfOf.Claims c = OnBehalfOf.verify(k, t, "n-a");
        assertThat(c.sub()).isEqualTo("rita");
        assertThat(c.roles()).containsExactly("risk");
        assertThat(c.origin()).isEqualTo("n-b");
        assertThatThrownBy(() -> OnBehalfOf.verify(k, t, "n-c")).hasMessageContaining("not this one");
        String forged = t.substring(0, t.lastIndexOf('.')).replace('A', 'B') + t.substring(t.lastIndexOf('.'));
        assertThatThrownBy(() -> OnBehalfOf.verify(k, forged, "n-a")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> OnBehalfOf.verify(new ClusterKey("someone-elses-key-that-is-32-bytes-or-more", "", clock), t, "n-a"))
                .hasMessageContaining("not signed");
        now.addAndGet(31_000);
        assertThatThrownBy(() -> OnBehalfOf.verify(k, t, "n-a")).hasMessageContaining("expired");
    }

    @Test
    void aRoleReachesOnlyTheNodesItLists() {
        AccessSettings a = new AccessSettings(Map.of("trader", List.of("n-a", "n-b"), "risk", List.of("region:emea")));
        assertThat(a.reaches(List.of("trader"), "n-a", List.of())).isTrue();
        assertThat(a.reaches(List.of("trader"), "n-c", List.of())).isFalse();
        assertThat(a.reaches(List.of("risk"), "n-c", List.of("region:emea"))).isTrue();
        assertThat(a.reaches(List.of("risk"), "n-c", List.of("region:apac"))).isFalse();
        assertThat(a.reaches(List.of("trader", "risk"), "n-c", List.of("region:emea"))).isTrue();
        assertThat(a.reaches(List.of("ops"), "n-c", List.of())).isTrue();          // a role not listed is not limited
        assertThat(a.reaches(List.of("*"), "n-z", List.of())).isTrue();             // security off
        assertThat(AccessSettings.OPEN.reaches(List.of("trader"), "n-z", List.of())).isTrue();
    }
}
