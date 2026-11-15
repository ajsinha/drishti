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

import com.ash.drishti.net.config.ClusterSettings;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import org.jgroups.protocols.AUTH;
import org.jgroups.protocols.FD_ALL3;
import org.jgroups.protocols.FD_SOCK2;
import org.jgroups.protocols.FILE_PING;
import org.jgroups.protocols.FRAG4;
import org.jgroups.protocols.JDBC_PING2;
import org.jgroups.protocols.MERGE3;
import org.jgroups.protocols.SYM_ENCRYPT;
import org.jgroups.protocols.TCP;
import org.jgroups.protocols.TCPPING;
import org.jgroups.protocols.UNICAST3;
import org.jgroups.protocols.VERIFY_SUSPECT2;
import org.jgroups.protocols.pbcast.GMS;
import org.jgroups.protocols.pbcast.NAKACK2;
import org.jgroups.protocols.pbcast.STABLE;
import org.jgroups.stack.DiagnosticsHandler;
import org.jgroups.stack.Protocol;
import org.jgroups.util.TLSClientAuth;

/**
 * The membership's JGroups stack, built in code from the settings (CLUSTER_I5_DESIGN.md section 3.1; no XML file is shipped), bottom to
 * top. <b>TCP only, never multicast</b> (owner rule): the transport is {@code TCP}, discovery is {@code TCPPING} on the seed list (or
 * {@code FILE_PING}, {@code JDBC_PING2}, {@code NATIVE_S3_PING} when the site asks), the diagnostics handler (which listens on UDP
 * multicast) is off, and NAKACK2 never retransmits by multicast. {@link #MULTICAST} names what may never appear; the guard test
 * ({@code JGroupsStackGuardTest}) builds every shipped variant and fails when one does.
 *
 * <table>
 *   <caption>The protocols</caption>
 *   <tr><th>Protocol</th><th>For</th></tr>
 *   <tr><td>TCP (TLS when [net.tls] is configured)</td><td>the transport on cluster-port, bound to the advertised address</td></tr>
 *   <tr><td>TCPPING | FILE_PING | JDBC_PING2 | NATIVE_S3_PING</td><td>discovery</td></tr>
 *   <tr><td>MERGE3</td><td>partitions that see each other again become one view</td></tr>
 *   <tr><td>FD_SOCK2, FD_ALL3, VERIFY_SUSPECT2</td><td>failure detection: a closed socket at once, a silent member after suspect-after</td></tr>
 *   <tr><td>SYM_ENCRYPT</td><td>only without certificates: the channel encrypted with a key derived from the cluster key</td></tr>
 *   <tr><td>NAKACK2, UNICAST3, STABLE</td><td>reliable, ordered vitals</td></tr>
 *   <tr><td>AUTH</td><td>the join token: an HMAC with the cluster key ({@link ClusterKeyToken})</td></tr>
 *   <tr><td>GMS, FRAG4</td><td>views; large view messages in fragments</td></tr>
 * </table>
 */
public final class JGroupsStack {

    /** Protocols that use multicast or broadcast; none may be in a stack Drishti builds. */
    public static final Set<String> MULTICAST = Set.of("UDP", "MPING", "PING", "BPING", "SHARED_LOOPBACK", "SHARED_LOOPBACK_PING", "LOCAL_PING");

    /** The S3 discovery's class, in the optional {@code jgroups-aws} jar (put it in {@code lib/} to use {@code discovery=s3}). */
    static final String S3_PING = "org.jgroups.aws.s3.NATIVE_S3_PING";

    /**
     * What the stack is built from.
     *
     * @param clusterName {@code [net.cluster] name}: a node of another name never joins
     * @param bindHost the advertised host (a real address); the transport binds to it
     * @param port the cluster port
     * @param seeds the seeds and the members remembered in {@code members.json}
     * @param settings the cluster's settings (timings, discovery)
     * @param tls the cluster's TLS context, or null for none (the channel is then encrypted with a key from the cluster key)
     * @param clientAuthNeeded mutual TLS
     * @param key the cluster key (the join token and, without TLS, the encryption key)
     * @param previousKey the previous cluster key during a rotation, or empty
     */
    public record Spec(String clusterName, String bindHost, int port, List<InetSocketAddress> seeds, ClusterSettings settings, SSLContext tls,
            boolean clientAuthNeeded, String key, String previousKey) {
        public Spec {
            seeds = seeds == null ? List.of() : List.copyOf(seeds);
            previousKey = previousKey == null ? "" : previousKey;
            if (key == null || key.isEmpty()) {
                throw new IllegalArgumentException("the membership needs the cluster key (node.yaml secret: ${DRISHTI_NET_SECRET}, or [net.cluster] secret-file)");
            }
        }

        /** Never prints the keys. */
        @Override
        public String toString() {
            return "Spec[cluster=" + clusterName + ", bind=" + bindHost + ":" + port + ", seeds=" + seeds + ", tls=" + (tls != null) + "]";
        }
    }

    private JGroupsStack() {
    }

    /** The protocols, transport first, ready for {@code new JChannel(list)}. */
    public static List<Protocol> protocols(Spec spec) throws Exception {
        ClusterSettings.Timings t = spec.settings().membership();
        InetAddress bind = address(spec.bindHost());
        List<Protocol> out = new ArrayList<>();

        TCP tcp = new TCP();
        tcp.setBindAddress(bind);
        tcp.setBindPort(spec.port());
        tcp.setPortRange(0);                                   // exactly cluster-port: firewalls name it
        DiagnosticsHandler diag = new DiagnosticsHandler();
        diag.setEnabled(false);                                // the probe handler listens on UDP multicast: never
        diag.enableUdp(false);
        tcp.setDiagnosticsHandler(diag);
        if (spec.tls() != null) {
            tcp.tls().enabled(true).setSSLContext(spec.tls()).setClientAuth(spec.clientAuthNeeded() ? TLSClientAuth.NEED : TLSClientAuth.WANT);
        }
        out.add(tcp);

        out.add(discovery(spec));

        MERGE3 merge = new MERGE3();
        merge.setMinInterval(Math.max(1000, t.mergeEvery().toMillis() / 2));
        merge.setMaxInterval(Math.max(2000, t.mergeEvery().toMillis()));
        out.add(merge);

        FD_SOCK2 sock = new FD_SOCK2();
        sock.setOffset(ClusterSettings.FAILURE_PORT_OFFSET);
        sock.setPortRange(0);
        out.add(sock);

        FD_ALL3 all = new FD_ALL3();
        all.setInterval(t.heartbeat().toMillis());
        all.setTimeout(t.suspectAfter().toMillis());
        out.add(all);

        VERIFY_SUSPECT2 verify = new VERIFY_SUSPECT2();
        verify.setTimeout(t.verify().toMillis());
        out.add(verify);

        if (spec.tls() == null) {
            SYM_ENCRYPT enc = new SYM_ENCRYPT();
            enc.setSecretKey(channelKey(spec.key(), spec.clusterName()));
            out.add(enc);
        }

        NAKACK2 nak = new NAKACK2();
        nak.useMcastXmit(false);
        nak.useMcastXmitReq(false);
        out.add(nak);
        out.add(new UNICAST3());
        out.add(new STABLE());

        AUTH auth = new AUTH();
        auth.setAuthToken(new ClusterKeyToken(spec.clusterName(), spec.key(), spec.previousKey()));
        out.add(auth);

        GMS gms = new GMS();
        gms.setJoinTimeout(t.joinTimeout().toMillis());
        gms.printLocalAddress(false);
        out.add(gms);

        out.add(new FRAG4());
        return out;
    }

    private static Protocol discovery(Spec spec) throws Exception {
        ClusterSettings.Cluster c = spec.settings().cluster();
        Map<String, String> o = c.discoveryOptions();
        switch (c.discovery()) {
            case "file" -> {
                FILE_PING f = new FILE_PING();
                f.setLocation(o.get("dir"));
                return f;
            }
            case "jdbc" -> {
                JDBC_PING2 j = new JDBC_PING2();
                j.setConnectionUrl(o.get("url"));
                j.setConnectionUsername(o.getOrDefault("user", ""));
                j.setConnectionPassword(o.getOrDefault("password", ""));
                return j;
            }
            case "s3" -> {
                Class<?> type;
                try {
                    type = Class.forName(S3_PING);
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("[net.cluster] discovery=s3 needs the jgroups-aws jar in lib/ (" + S3_PING + "); or use seeds", e);
                }
                Protocol s3 = (Protocol) type.getDeclaredConstructor().newInstance();
                s3.setValue("bucket_name", o.get("bucket"));
                if (!o.getOrDefault("region", "").isEmpty()) {
                    s3.setValue("region_name", o.get("region"));
                }
                if (!o.getOrDefault("prefix", "").isEmpty()) {
                    s3.setValue("bucket_prefix", o.get("prefix"));
                }
                return s3;
            }
            default -> {
                TCPPING ping = new TCPPING();
                ping.setInitialHosts(spec.seeds());
                ping.setPortRange(0);
                return ping;
            }
        }
    }

    /** The host as an address; a name is resolved once, here. */
    static InetAddress address(String host) throws UnknownHostException {
        return InetAddress.getByName(host);
    }

    /** The channel's AES key without certificates: an HMAC-SHA256 of the cluster's name under the cluster key (never the key itself). */
    static SecretKey channelKey(String key, String clusterName) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] k = mac.doFinal(("drishti-net-membership|" + clusterName).getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(k, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available in this JVM", e);
        }
    }
}
