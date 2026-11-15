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

import com.ash.drishti.net.agreement.AgreementBase;
import com.ash.drishti.net.agreement.AgreementHealth;
import com.ash.drishti.net.agreement.AgreementState;
import com.ash.drishti.net.agreement.AgreementUnavailableException;
import com.ash.drishti.net.config.ClusterSettings;
import com.ash.drishti.net.security.ClusterTls;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.ratis.RaftConfigKeys;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.client.RaftClientConfigKeys;
import org.apache.ratis.conf.Parameters;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.grpc.GrpcTlsConfig;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.retry.RetryPolicies;
import org.apache.ratis.rpc.SupportedRpcType;
import org.apache.ratis.server.DivisionInfo;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.apache.ratis.util.TimeDuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code ratis} mode (CLUSTER_I5_DESIGN.md sections 2.3, 3 and 10): leases and the register replicated by an Apache Ratis group of the
 * named voters (three or five; a witness counts), on the agreement port, over gRPC with <b>mutual TLS, required</b> (Ratis has no
 * shared-key authentication, so a consensus port without certificates is never opened). A voter runs a Raft server and the
 * {@link AgreementStateMachine}; every node, voter or not, reaches the group with a Raft client. The leader proposes the time
 * ({@code tick}) every half renewal, so lease expiry is itself a log entry and every replica agrees on it.
 *
 * <p>Pure Java on Linux and Windows: Netty's native transports and OpenSSL inside {@code ratis-thirdparty-misc} are switched off before
 * the first Ratis class loads them ({@link #pureJava()}), so no native library is unpacked or loaded.
 */
public final class RatisAgreement extends AgreementBase {

    private static final Logger LOG = LoggerFactory.getLogger(RatisAgreement.class);
    /** How long a call to the group may take before this node says the cluster cannot decide. */
    private static final Duration CALL = Duration.ofSeconds(3);

    private final ClusterSettings.AgreementSettings settings;
    private final List<String> voters;
    private final RaftGroup group;
    private final RaftServer server;
    private final RaftClient client;
    private final Thread ticker;
    private volatile long lastDecided;
    private volatile String lastWhy = "starting";

    /**
     * Starts this node's part of the group: its Raft server when it is a voter, its client always, and the leader's ticker.
     *
     * @param self this node's id
     * @param voters each voter's id and agreement address ({@code host:agreement-port}), in the configured order
     * @param clusterName the cluster's name (the group's id is derived from it, so two clusters never mix)
     * @param settings {@code [net.agreement]}
     * @param tls this node's certificate and the cluster's authority ({@code [net.tls]}): required
     * @param dir the voter's log and snapshot folder, absolute ({@code [net.agreement] dir}, else {@code <data dir>/net/agreement})
     * @throws IllegalStateException when TLS is missing or the server cannot start
     */
    public RatisAgreement(String self, Map<String, InetSocketAddress> voters, String clusterName, ClusterSettings.AgreementSettings settings,
            ClusterTls tls, Path dir) {
        super(self, settings.leaseTtl(), settings.leaseRenew(), System::currentTimeMillis);
        if (tls == null) {
            throw new IllegalStateException("the ratis agreement needs mutual TLS: name [net.tls] ca-file, cert-file and key-file "
                    + "(drishti.py net certs makes them for a site without a PKI); Ratis has no shared-key authentication");
        }
        if (voters.size() < 3) {
            throw new IllegalStateException("the ratis agreement needs three or five voters ([net.agreement] voters); it has " + voters.size());
        }
        if (!dir.isAbsolute()) {
            throw new IllegalArgumentException("[net.agreement] dir must be absolute: " + dir);
        }
        pureJava();
        this.settings = settings;
        this.voters = List.copyOf(voters.keySet());
        List<RaftPeer> peers = new ArrayList<>();
        voters.forEach((id, at) -> peers.add(RaftPeer.newBuilder().setId(id).setAddress(at).build()));
        this.group = RaftGroup.valueOf(RaftGroupId.valueOf(UUID.nameUUIDFromBytes(("drishti-agreement|" + clusterName).getBytes(StandardCharsets.UTF_8))), peers);
        Parameters parameters = tlsParameters(tls);
        RaftProperties props = properties(settings, dir.resolve(self));
        try {
            if (voters.containsKey(self)) {
                RaftProperties serverProps = properties(settings, dir.resolve(self));
                GrpcConfigKeys.Server.setHost(serverProps, voters.get(self).getHostString());   // the real address only, never every interface
                GrpcConfigKeys.Server.setPort(serverProps, voters.get(self).getPort());
                this.server = RaftServer.newBuilder().setServerId(RaftPeerId.valueOf(self)).setGroup(group)
                        .setStateMachine(new AgreementStateMachine()).setProperties(serverProps).setParameters(parameters)
                        .setOption(RaftStorage.StartupOption.RECOVER).build();
                server.start();
            } else {
                this.server = null;
            }
        } catch (IOException e) {
            throw new IllegalStateException("the ratis agreement of " + self + " cannot start in " + dir + ": " + e.getMessage(), e);
        }
        this.client = RaftClient.newBuilder().setRaftGroup(group).setProperties(props).setParameters(parameters)
                .setRetryPolicy(RetryPolicies.retryUpToMaximumCountWithFixedSleep(3, TimeDuration.valueOf(200, TimeUnit.MILLISECONDS))).build();
        long every = Math.max(100, settings.leaseRenew().toMillis() / 2);
        this.ticker = Thread.ofPlatform().daemon().name("drishti-agreement-tick-" + self).unstarted(() -> tick(every));
        ticker.start();
        startKeeper();
        LOG.info("agreement: ratis on {} for {} (voters {}{})", self, clusterName, this.voters, server == null ? "; this node is a client" : "");
    }

    /** Netty's native transports and OpenSSL off (shaded property names), unless the operator set them. */
    static void pureJava() {
        for (String p : List.of("org.apache.ratis.thirdparty.io.netty.transport.noNative", "org.apache.ratis.thirdparty.io.netty.handler.ssl.noOpenSsl")) {
            if (System.getProperty(p) == null) {
                System.setProperty(p, "true");
            }
        }
    }

    private static RaftProperties properties(ClusterSettings.AgreementSettings s, Path storage) {
        RaftProperties p = new RaftProperties();
        RaftConfigKeys.Rpc.setType(p, SupportedRpcType.GRPC);
        RaftServerConfigKeys.setStorageDir(p, List.of(new File(storage.toString())));
        long e = s.electionTimeout().toMillis();
        RaftServerConfigKeys.Rpc.setTimeoutMin(p, TimeDuration.valueOf(e, TimeUnit.MILLISECONDS));
        RaftServerConfigKeys.Rpc.setTimeoutMax(p, TimeDuration.valueOf(e * 2, TimeUnit.MILLISECONDS));
        RaftServerConfigKeys.Snapshot.setAutoTriggerEnabled(p, true);
        RaftServerConfigKeys.Snapshot.setAutoTriggerThreshold(p, s.snapshotEvery());
        RaftServerConfigKeys.Snapshot.setRetentionFileNum(p, 2);
        RaftClientConfigKeys.Rpc.setRequestTimeout(p, TimeDuration.valueOf(CALL.toMillis(), TimeUnit.MILLISECONDS));
        return p;
    }

    /** Mutual TLS on every gRPC channel of Ratis: server, client and admin. */
    private static Parameters tlsParameters(ClusterTls tls) {
        GrpcTlsConfig conf = new GrpcTlsConfig(tls.key(), tls.certificate(), tls.authorities(), true);
        Parameters p = new Parameters();
        GrpcConfigKeys.TLS.setConf(p, conf);
        GrpcConfigKeys.Server.setTlsConf(p, conf);
        GrpcConfigKeys.Client.setTlsConf(p, conf);
        GrpcConfigKeys.Admin.setTlsConf(p, conf);
        return p;
    }

    /** The leader moves the agreed time on; a follower or a client does nothing here. */
    private void tick(long everyMillis) {
        while (!closed() && !Thread.currentThread().isInterrupted()) {
            try {
                if (leader()) {
                    referee().apply(AgreementState.tick(System.currentTimeMillis()));
                }
            } catch (RuntimeException e) {
                LOG.debug("agreement: a tick failed: {}", e.toString());
            }
            try {
                Thread.sleep(everyMillis);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private DivisionInfo info() throws IOException {
        return server == null ? null : server.getDivision(group.getGroupId()).getInfo();
    }

    private boolean leader() {
        try {
            DivisionInfo i = info();
            return i != null && i.isLeader() && i.isLeaderReady();
        } catch (IOException e) {
            return false;
        }
    }

    private final AgreementBase.Referee referee = new AgreementBase.Referee() {
        @Override
        public byte[] apply(byte[] command) {
            return call(command, false);
        }

        @Override
        public byte[] query(byte[] query) {
            return call(query, true);
        }
    };

    private byte[] call(byte[] bytes, boolean readOnly) {
        if (closed()) {
            throw new AgreementUnavailableException("the agreement of " + self() + " is closed");
        }
        try {
            Message m = Message.valueOf(ByteString.copyFrom(bytes));
            RaftClientReply r = readOnly ? client.io().sendReadOnly(m) : client.io().send(m);
            if (!r.isSuccess()) {
                String why = r.getException() == null ? "no answer" : r.getException().getMessage();
                lastWhy = why;
                throw new AgreementUnavailableException("the cluster cannot decide right now: " + why);
            }
            lastDecided = System.currentTimeMillis();
            lastWhy = "";
            return r.getMessage().getContent().toByteArray();
        } catch (IOException e) {
            lastWhy = e.getMessage();
            throw new AgreementUnavailableException("the cluster cannot decide right now (" + reachableText() + "): " + e.getMessage(), e);
        }
    }

    private String reachableText() {
        return "voters " + voters;
    }

    @Override
    protected AgreementBase.Referee referee() {
        return referee;
    }

    @Override
    public Mode mode() {
        return Mode.RATIS;
    }

    @Override
    public AgreementHealth health() {
        String leader = "";
        long term = 0;
        try {
            DivisionInfo i = info();
            if (i != null) {
                term = i.getCurrentTerm();
                leader = i.getLeaderId() == null ? "" : i.getLeaderId().toString();
            } else if (client.getLeaderId() != null) {
                leader = client.getLeaderId().toString();
            }
        } catch (IOException e) {
            LOG.debug("agreement: reading the division: {}", e.toString());
        }
        boolean decides = !closed() && System.currentTimeMillis() - lastDecided < settings.leaseTtl().toMillis();
        return new AgreementHealth(Mode.RATIS, decides, leader, term, voters, decides ? voters : List.of(),
                decides ? "ratis: a majority of " + voters.size() + " voters answers" : "ratis: no majority answered in "
                        + settings.leaseTtl().toSeconds() + " s" + (lastWhy.isEmpty() ? "" : " (" + lastWhy + ")"));
    }

    @Override
    protected void closeProvider() {
        ticker.interrupt();
        try {
            client.close();
        } catch (IOException e) {
            LOG.debug("agreement: closing the client: {}", e.toString());
        }
        if (server != null) {
            try {
                server.close();
            } catch (IOException e) {
                LOG.debug("agreement: closing the server: {}", e.toString());
            }
        }
    }

    /** The voters' ids, in order. */
    public List<String> voters() {
        return voters;
    }

    /** For tests: whether this node's Raft server leads now. */
    boolean leads() {
        return leader();
    }
}
