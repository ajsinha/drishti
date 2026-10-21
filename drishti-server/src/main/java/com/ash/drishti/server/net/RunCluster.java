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

import com.ash.drishti.net.member.Member;
import com.ash.drishti.net.member.MemberState;
import com.ash.drishti.net.security.NetHeaders;
import com.ash.drishti.net.security.OnBehalfOf;
import com.ash.drishti.net.transport.PeerHttp;
import com.ash.drishti.server.bi.mq.ShardDirectory;
import com.ash.drishti.server.bi.runs.RunService;
import com.ash.drishti.server.bi.runs.RunsProperties;
import com.ash.drishti.server.security.Principal;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BI runs on a Drishti*net cluster (docs/architecture/BI_RUNS.md section 13): {@link ShardDirectory} over the members that are alive, so a heavy run executes
 * on its model's owning node; calls to another node for one person (signed, the hop header, an on-behalf-of token: the owner computes it as that person,
 * with that person's row rules and masks); and the lease loop, which renews this node's runs and adopts the runs of a dead node whose lease lapsed (a
 * shared identity database: one conditional update decides the winner). Members are called at the address they advertise, which is never a loopback one.
 */
public final class RunCluster implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RunCluster.class);

    private final NetService net;
    private final RunService runs;
    private final RunsProperties props;
    private volatile Thread loop;

    public RunCluster(NetService net, RunService runs, RunsProperties props) {
        this.net = net;
        this.runs = runs;
        this.props = props;
    }

    /** True while this node is a member of a running cluster. */
    public boolean on() {
        return net.running() && net.node().isPresent() && !net.nodeId().isEmpty();
    }

    public String self() {
        return net.nodeId();
    }

    /** Whether a node is alive now (this node always is; suspect, dead and left ones are not). */
    public boolean alive(String node) {
        if (node == null) {
            return false;
        }
        if (node.equals(self())) {
            return true;
        }
        return net.node().flatMap(n -> n.table().known(node)).map(k -> k.member().state() == MemberState.ALIVE).orElse(false);
    }

    /** The other members that are alive, in a stable order. */
    public List<String> peers() {
        return net.node().map(n -> n.table().alive().stream().map(Member::nodeId).toList()).orElse(List.of());
    }

    /** The directory of model owners over the members known now (rendezvous hashing; a member not alive owns nothing). */
    public ShardDirectory directory() {
        List<String> all = new ArrayList<>();
        all.add(self());
        net.node().ifPresent(n -> n.table().others().forEach(k -> all.add(k.member().nodeId())));
        return new ShardDirectory(self(), all, this::alive);
    }

    /** The node that owns a model's heavy runs now. */
    public String owner(String modelKey) {
        return on() ? directory().owner(modelKey) : self();
    }

    private Optional<String> url(String node) {
        return net.node().flatMap(n -> n.table().known(node)).map(k -> k.member().url());
    }

    private Map<String, String> headers(String node, Principal p, String accept) {
        String request = NetCaller.current().map(NetCaller.Caller::requestId).orElseGet(() -> UUID.randomUUID().toString().substring(0, 13));
        Map<String, String> h = new LinkedHashMap<>();
        h.put(NetHeaders.HOP, "1");
        h.put(NetHeaders.REQUEST, request);
        h.put(NetHeaders.ON_BEHALF_OF, OnBehalfOf.mint(net.key(), p.user(), p.roles(), p.embedApp(), self(), request, node));
        h.put("Content-Type", "application/json");
        h.put("Accept", accept);
        return h;
    }

    /** One call to {@code node} for {@code p}, read whole; IOException when the node cannot be reached. */
    public PeerHttp.Answer call(String node, String method, String pathAndQuery, byte[] body, Principal p, Duration timeout) throws IOException {
        String base = url(node).orElseThrow(() -> new IOException("node " + node + " is not a known member"));
        try {
            return net.http().send(base, method, pathAndQuery, headers(node, p, "application/json"), body, timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while calling " + node, e);
        }
    }

    /** A stream from {@code node} for {@code p} (the runs' events); the caller closes it. */
    public HttpResponse<InputStream> stream(String node, String pathAndQuery, Principal p) throws IOException {
        String base = url(node).orElseThrow(() -> new IOException("node " + node + " is not a known member"));
        try {
            return net.http().open(base, "GET", pathAndQuery, headers(node, p, "text/event-stream"), null, Duration.ofDays(1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while calling " + node, e);
        }
    }

    /** The forwarding timeout of the cluster (routing.forward-timeout), at least 10 s for a run's calls. */
    public Duration timeout() {
        Duration t = net.config().routing().forwardTimeout();
        return t.compareTo(Duration.ofSeconds(10)) < 0 ? Duration.ofSeconds(10) : t;
    }

    /** Starts the lease loop: every {@code lease.renew} this node renews its runs' leases and, in a cluster, adopts the runs of dead nodes. */
    public void start() {
        Duration every = props.lease().renew();
        loop = Thread.ofVirtual().name("bi-runs-leases").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(every.toMillis());
                    runs.renewLeases();
                    if (on()) {
                        runs.adoptOrphans(this::alive, this::owner);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    LOG.warn("BI runs: the lease loop failed once: {}", e.getMessage());
                }
            }
        });
    }

    @Override
    public void close() {
        Thread t = loop;
        if (t != null) {
            t.interrupt();
        }
    }
}
