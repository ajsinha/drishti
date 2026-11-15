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

import com.ash.drishti.net.config.ClusterSettings;
import com.ash.drishti.settingsfile.SettingsFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cluster's six sections of the settings file (CLUSTER_I5_DESIGN.md section 4.4), read through the one resolver: the design's defaults,
 * every key, placeholders inside a value, absolute paths only, seeds on real addresses only, multicast refused, and the gossip-era keys
 * refused with their replacement.
 */
class ClusterSettingsTest {

    @TempDir
    Path dir;
    private final List<String> set = new ArrayList<>();

    @AfterEach
    void clear() {
        set.forEach(System::clearProperty);
        System.clearProperty(SettingsFile.PROPERTY);
    }

    @Test
    void theDefaultsAreTheDesigns() {
        ClusterSettings.Read r = ClusterSettings.read(Map.<String, String>of()::get);
        assertThat(r.problems()).isEmpty();
        ClusterSettings s = r.settings();
        assertThat(s).isEqualTo(ClusterSettings.DEFAULTS);
        assertThat(s.node().clusterPort()).isEqualTo(7946);
        assertThat(s.node().agreementPort()).isEqualTo(7947);
        assertThat(s.membership().heartbeat()).isEqualTo(Duration.ofSeconds(2));
        assertThat(s.membership().suspectAfter()).isEqualTo(Duration.ofSeconds(10));
        assertThat(s.membership().verify()).isEqualTo(Duration.ofMillis(1500));
        assertThat(s.agreement().mode()).isEqualTo("auto");
        assertThat(s.agreement().leaseTtl()).isEqualTo(Duration.ofSeconds(15));
        assertThat(s.agreement().leaseRenew()).isEqualTo(Duration.ofSeconds(5));
        assertThat(s.tls().configured()).isFalse();
        assertThat(s.tls().clientAuth()).isEqualTo("need");
        assertThat(s.engines().callTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(s.cluster().discovery()).isEqualTo("seeds");
    }

    @Test
    void everySectionIsReadFromTheSettingsFileWithItsPlaceholders() throws Exception {
        Path ini = dir.resolve("site.ini");
        Files.writeString(ini, """
                [roots]
                drishti.data.dir=/var/lib/drishti/data

                [net.cluster]
                name=risk-prod
                environment=prod
                secret-file=/etc/drishti/net/secret
                seeds=10.20.1.31:7946,10.20.1.32:7946

                [net.node]
                id=drishti-a
                cluster-port=7950
                agreement-port=7951
                role=witness

                [net.membership]
                heartbeat=1s
                suspect-after=5s

                [net.agreement]
                mode=ratis
                voters=drishti-a,drishti-b,drishti-c
                dir=${drishti.data.dir}/net/agreement
                lease-ttl=20s
                lease-renew=5s

                [net.tls]
                ca-file=/etc/drishti/tls/cluster-ca.pem
                cert-file=/etc/drishti/tls/${net.node.id}.pem
                key-file=/etc/drishti/tls/${net.node.id}.key
                client-auth=need
                reload=off

                [net.engines]
                call-timeout=45s
                failover=off
                breaker.failures=7
                """);
        System.setProperty(SettingsFile.PROPERTY, ini.toString());
        set.addAll(SettingsFile.applyFromSystemProperty());
        ClusterSettings.Read r = ClusterSettings.read(null);
        assertThat(r.problems()).isEmpty();
        ClusterSettings s = r.settings();
        assertThat(s.cluster().name()).isEqualTo("risk-prod");
        assertThat(s.cluster().environment()).isEqualTo("prod");
        assertThat(s.cluster().secretFile()).isEqualTo("/etc/drishti/net/secret");
        assertThat(s.cluster().seeds()).containsExactly("10.20.1.31:7946", "10.20.1.32:7946");
        assertThat(s.node()).isEqualTo(new ClusterSettings.Node("drishti-a", 7950, 7951, "witness"));
        assertThat(s.membership().heartbeat()).isEqualTo(Duration.ofSeconds(1));
        assertThat(s.agreement().voters()).containsExactly("drishti-a", "drishti-b", "drishti-c");
        assertThat(s.agreement().dir()).isEqualTo("/var/lib/drishti/data/net/agreement");
        assertThat(s.tls().certFile()).isEqualTo("/etc/drishti/tls/drishti-a.pem");
        assertThat(s.tls().reload()).isFalse();
        assertThat(s.engines().callTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(s.engines().failover()).isFalse();
        assertThat(s.engines().breakerFailures()).isEqualTo(7);
    }

    @Test
    void aRelativePathIsRefusedNeverTakenUnderTheWorkingFolder() {
        ClusterSettings.Read r = ClusterSettings.read(Map.of("net.tls.ca-file", "tls/ca.pem", "net.tls.cert-file", "/etc/a.pem",
                "net.tls.key-file", "/etc/a.key", "net.agreement.dir", "data/agreement")::get);
        assertThat(r.problems()).anyMatch(p -> p.startsWith("net.tls.ca-file") && p.contains("relative"));
        assertThat(r.problems()).anyMatch(p -> p.startsWith("net.agreement.dir") && p.contains("relative"));
        assertThat(r.settings().agreement().dir()).isEmpty();
        assertThat(ClusterSettings.read(Map.of("net.agreement.dir", "D:\\drishti\\agreement")::get).problems()).as("a Windows drive is absolute").isEmpty();
    }

    @Test
    void aSeedOnALoopbackAddressOrWithoutAPortIsRefused() {
        ClusterSettings.Read r = ClusterSettings.read(Map.of("net.cluster.seeds", "127.0.0.1:7946,localhost:7946,10.20.1.31,10.20.1.32:7946")::get);
        assertThat(r.settings().cluster().seeds()).containsExactly("10.20.1.32:7946");
        assertThat(r.problems()).hasSize(3);
    }

    @Test
    void theGossipEraKeysAreRefusedWithTheirReplacement() {
        ClusterSettings.Read r = ClusterSettings.read(Map.of("net.membership.interval", "1s", "net.node.gossip-port", "auto")::get);
        assertThat(r.problems()).anyMatch(p -> p.contains("net.membership.interval") && p.contains("net.membership.heartbeat"));
        assertThat(r.problems()).anyMatch(p -> p.contains("net.node.gossip-port") && p.contains("net.node.cluster-port"));
    }

    @Test
    void halfTheTlsFilesOrTimingsThatCannotWorkAreRefused() {
        assertThat(ClusterSettings.read(Map.of("net.tls.ca-file", "/etc/ca.pem")::get).problems()).anyMatch(p -> p.contains("all three"));
        assertThat(ClusterSettings.read(Map.of("net.agreement.lease-ttl", "4s", "net.agreement.lease-renew", "3s")::get).problems())
                .anyMatch(p -> p.contains("half of lease-ttl"));
        assertThat(ClusterSettings.read(Map.of("net.membership.heartbeat", "5s", "net.membership.suspect-after", "6s")::get).problems())
                .anyMatch(p -> p.contains("two heartbeats"));
        assertThat(ClusterSettings.read(Map.of("net.node.cluster-port", "7946", "net.node.agreement-port", "7946")::get).problems())
                .anyMatch(p -> p.contains("port of its own"));
        assertThat(ClusterSettings.read(Map.of("net.agreement.voters", "a,b,c,d")::get).problems()).anyMatch(p -> p.contains("three or five"));
    }
}
