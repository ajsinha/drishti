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

import com.ash.drishti.net.config.ClusterSettings;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jgroups.protocols.TP;
import org.jgroups.protocols.pbcast.NAKACK2;
import org.jgroups.stack.Protocol;
import org.junit.jupiter.api.Test;

/**
 * Owner rule: <b>TCP only, no multicast anywhere</b> (CLUSTER_I5_DESIGN.md section 3.1). Every stack Drishti can build (each discovery,
 * with and without TLS) is built here and must hold no multicast or broadcast protocol, a transport that cannot multicast, the
 * diagnostics handler off (it listens on UDP multicast) and NAKACK2 without multicast retransmission; no JGroups XML stack is shipped in
 * any resource folder; and no settings file or example names a multicast discovery. A change that brings one in fails here.
 */
class JGroupsStackGuardTest {

    private static final Pattern XML_MULTICAST = Pattern.compile("<(UDP|MPING|PING|BPING)\\b|mcast_|ip_mcast");

    private static List<ClusterSettings> everyShippedVariant() {
        List<ClusterSettings> out = new ArrayList<>();
        out.add(ClusterSettings.read(Map.<String, String>of()::get).settings());
        out.add(ClusterSettings.read(Map.of("net.cluster.discovery", "file", "net.cluster.discovery.dir", "/var/lib/drishti/net/discovery")::get).settings());
        out.add(ClusterSettings.read(Map.of("net.cluster.discovery", "jdbc", "net.cluster.discovery.url", "jdbc:postgresql://db:5432/drishti")::get).settings());
        return out;
    }

    @Test
    void noStackDrishtiBuildsHoldsAMulticastProtocol() throws Exception {
        for (ClusterSettings s : everyShippedVariant()) {
            for (boolean tls : List.of(false, true)) {
                JGroupsStack.Spec spec = new JGroupsStack.Spec("guard", "10.11.12.13", 7946, List.of(new InetSocketAddress("10.11.12.14", 7946)), s,
                        tls ? javax.net.ssl.SSLContext.getDefault() : null, true, "a-test-cluster-key-of-at-least-32-bytes", "");
                List<Protocol> stack;
                try {
                    stack = JGroupsStack.protocols(spec);
                } catch (java.net.UnknownHostException e) {
                    continue;                                   // the address only needs to parse; it is never bound here
                }
                String where = s.cluster().discovery() + (tls ? " with TLS" : "");
                for (Protocol p : stack) {
                    assertThat(JGroupsStack.MULTICAST).as(where + ": " + p.getName()).doesNotContain(p.getName());
                }
                TP transport = (TP) stack.get(0);
                assertThat(transport.getName()).as(where).isEqualTo("TCP");
                assertThat(transport.supportsMulticasting()).as(where + ": the transport multicasts").isFalse();
                assertThat(transport.getDiagnosticsHandler()).as(where + ": a diagnostics handler").isNotNull();
                assertThat(transport.getDiagnosticsHandler().isEnabled()).as(where + ": the diagnostics handler listens on UDP multicast").isFalse();
                for (Protocol p : stack) {
                    if (p instanceof NAKACK2 n) {
                        assertThat(n.useMcastXmit()).as(where + ": NAKACK2 retransmits by multicast").isFalse();
                        assertThat(n.useMcastXmitReq()).as(where).isFalse();
                    }
                }
            }
        }
    }

    @Test
    void aMulticastDiscoveryIsRefusedByName() {
        for (String d : List.of("udp", "multicast", "mping", "ping")) {
            ClusterSettings.Read r = ClusterSettings.read(Map.of("net.cluster.discovery", d)::get);
            assertThat(r.problems()).anyMatch(p -> p.contains("multicast"));
            assertThat(r.settings().cluster().discovery()).isEqualTo("seeds");
        }
    }

    @Test
    void noJGroupsXmlStackAndNoMulticastSettingIsShipped() throws IOException {
        Path root = repoRoot();
        List<String> found = new ArrayList<>();
        for (String top : List.of("config", "net", "kernel", "packaging", "deploy", "bin")) {
            if (Files.isDirectory(root.resolve(top))) {
                scan(root, root.resolve(top), found);
            }
        }
        assertThat(found).as("files that bring multicast into the cluster").isEmpty();
    }

    private static void scan(Path root, Path top, List<String> found) throws IOException {
        try (Stream<Path> files = Files.walk(top)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("/target/") && !p.toString().contains("\\target\\") && !p.toString().contains("/.git/"))
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".xml") && !n.equals("pom.xml") || n.endsWith(".ini") || n.endsWith(".yaml") && p.toString().contains("net");
                    })
                    .forEach(p -> {
                        try {
                            String text = Files.readString(p, StandardCharsets.UTF_8);
                            if (XML_MULTICAST.matcher(text).find() || text.matches("(?s).*discovery\\s*=\\s*(udp|multicast|mping|ping)\\b.*")) {
                                found.add(root.relativize(p).toString());
                            }
                        } catch (IOException | java.io.UncheckedIOException e) {
                            // not text: not a stack
                        }
                    });
        }
    }

    /** The repository's root: the folder holding the parent pom and the net folder. */
    private static Path repoRoot() {
        String set = System.getProperty("drishti.repo.root");
        Path p = set != null ? Path.of(set) : Path.of("").toAbsolutePath();
        while (p != null && !(Files.isRegularFile(p.resolve("pom.xml")) && Files.isDirectory(p.resolve("net")))) {
            p = p.getParent();
        }
        return p == null ? Path.of("").toAbsolutePath() : p;
    }
}
