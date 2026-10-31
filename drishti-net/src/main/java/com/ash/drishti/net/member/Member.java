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
package com.ash.drishti.net.member;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * One row of the member table, as gossip carries it (CLUSTER_DEPLOYMENT.md › Drishti*net: many servers, one Drishti, section 5).
 *
 * @param nodeId stable id, generated once and kept in {@code <data dir>/net/node-id}
 * @param url the advertised address peers call
 * @param incarnation raised only by the member itself, to refute a suspicion or to come back after it was declared gone
 * @param heartbeat raised by the member every gossip round; a heartbeat that stops rising is what makes a member suspect
 * @param state alive, suspect, dead or left
 * @param digestHash the hash of the member's capability digest; a change makes peers pull the digest again
 * @param version the member's build version
 * @param protocol the Drishti*net protocol it speaks; a peer refuses one it does not
 * @param tags the member's tags ({@code region:emea}), for nodes per role
 * @param since when the state began (epoch milliseconds, the clock of the node that set it)
 * @param drained true while the member takes no forwarded work (maintenance): it still gossips and serves its own users
 * @param gossipPort the port of the member's own gossip connector (2.6.7), on the host of {@code url}; 0 when it has none (an older
 *     node, or {@code gossip-port: off}): gossip then goes to {@code url}. A member that does not know the field drops it, so a peer may
 *     hear of a member without it for a while; gossip goes to {@code url} until it hears the member itself again
 * @param switchesHash the hash of the member's switches ledger (Admin → Commands, 2.8.0); a peer whose own hash differs pulls the
 *     member's switches and merges them. Blank from a member that has none (an older node), which is never pulled from
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Member(String nodeId, String url, long incarnation, long heartbeat, MemberState state, String digestHash, String version,
        int protocol, List<String> tags, long since, boolean drained, int gossipPort, String switchesHash) {

    /** The protocol this build speaks. A member with a different one is listed but never routed to. */
    public static final int PROTOCOL = 1;

    public Member {
        url = url == null ? "" : url.replaceAll("/+$", "");
        state = state == null ? MemberState.SUSPECT : state;
        digestHash = digestHash == null ? "" : digestHash;
        version = version == null ? "" : version;
        tags = tags == null ? List.of() : List.copyOf(tags);
        gossipPort = gossipPort < 0 || gossipPort > 65535 ? 0 : gossipPort;
        switchesHash = switchesHash == null ? "" : switchesHash;
    }

    /** A member without a switches hash (an older node, a test). */
    public Member(String nodeId, String url, long incarnation, long heartbeat, MemberState state, String digestHash, String version,
            int protocol, List<String> tags, long since, boolean drained, int gossipPort) {
        this(nodeId, url, incarnation, heartbeat, state, digestHash, version, protocol, tags, since, drained, gossipPort, "");
    }

    /** A member without a gossip port (a seed, an older node, a test). */
    public Member(String nodeId, String url, long incarnation, long heartbeat, MemberState state, String digestHash, String version,
            int protocol, List<String> tags, long since, boolean drained) {
        this(nodeId, url, incarnation, heartbeat, state, digestHash, version, protocol, tags, since, drained, 0, "");
    }

    /** Where gossip and probes go: {@code url}'s scheme and host with {@link #gossipPort()}, or {@code url} itself when there is none. */
    public String gossipUrl() {
        return gossipUrl(url, gossipPort);
    }

    /** {@code url}'s scheme and host with {@code port}; {@code url} itself when the port is 0 or the url cannot be read. */
    public static String gossipUrl(String url, int port) {
        if (port <= 0 || url == null || url.isEmpty()) {
            return url;
        }
        try {
            java.net.URI u = java.net.URI.create(url);
            if (u.getHost() == null) {
                return url;
            }
            return new java.net.URI(u.getScheme(), null, u.getHost(), port, null, null, null).toString();
        } catch (IllegalArgumentException | java.net.URISyntaxException e) {
            return url;
        }
    }

    public Member withState(MemberState s, long at) {
        return new Member(nodeId, url, incarnation, heartbeat, s, digestHash, version, protocol, tags, at, drained, gossipPort, switchesHash);
    }

    public Member withHeartbeat(long beat) {
        return new Member(nodeId, url, incarnation, beat, state, digestHash, version, protocol, tags, since, drained, gossipPort, switchesHash);
    }

    public Member withIncarnation(long inc) {
        return new Member(nodeId, url, inc, heartbeat, state, digestHash, version, protocol, tags, since, drained, gossipPort, switchesHash);
    }

    public Member withDigestHash(String hash) {
        return new Member(nodeId, url, incarnation, heartbeat, state, hash, version, protocol, tags, since, drained, gossipPort, switchesHash);
    }

    public Member withDrained(boolean d) {
        return new Member(nodeId, url, incarnation, heartbeat, state, digestHash, version, protocol, tags, since, d, gossipPort, switchesHash);
    }

    public Member withAdvert(String u, List<String> t) {
        return new Member(nodeId, u, incarnation, heartbeat, state, digestHash, version, protocol, t, since, drained, gossipPort, switchesHash);
    }

    public Member withSwitchesHash(String hash) {
        return new Member(nodeId, url, incarnation, heartbeat, state, digestHash, version, protocol, tags, since, drained, gossipPort, hash);
    }

    public Member withGossipPort(int port) {
        return new Member(nodeId, url, incarnation, heartbeat, state, digestHash, version, protocol, tags, since, drained, port, switchesHash);
    }

    /** Alive, speaking our protocol and not drained: a member a request may be sent to. */
    public boolean routable() {
        return state == MemberState.ALIVE && protocol == PROTOCOL && !drained;
    }

    /**
     * The merge rule: the higher incarnation wins; at equal incarnation the worse state wins; at equal incarnation and state the
     * higher heartbeat wins (it carries the member's newest url, tags, digest hash and drain flag).
     *
     * @return true when {@code other} is newer news than this entry
     */
    public boolean supersededBy(Member other) {
        if (other.incarnation != incarnation) {
            return other.incarnation > incarnation;
        }
        if (other.state != state) {
            return other.state.worseThan(state);
        }
        return other.heartbeat > heartbeat;
    }
}
