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
 * One member as this node knows it (CLUSTER_I5_DESIGN.md sections 3.1 and 8): its <b>vitals</b>, which the member publishes on the
 * membership channel on every change and every {@code vitals-every}, with its <b>state</b>, which comes from the agreed view (in the view:
 * alive; out of it: dead, or left when its last vitals said it was leaving). The vitals are small (well under the channel's 4 KB).
 *
 * @param nodeId the node's id
 * @param url the advertised address peers and consoles call (the server port)
 * @param state alive, suspect, dead or left
 * @param digestHash the hash of the member's capability digest; a change makes peers pull the digest again over HTTP
 * @param version the member's build version
 * @param protocol the Drishti*net protocol it speaks ({@link #PROTOCOL}); a member with another is listed but never routed to
 * @param tags the member's tags ({@code region:emea}), for nodes per role
 * @param since when the state began (epoch milliseconds, this node's clock)
 * @param drained true while the member takes no forwarded work (maintenance): it stays in the view and serves its own users
 * @param clusterAddress {@code host:port} of the member's cluster port, remembered in {@code members.json} to rejoin without seeds
 * @param switchesHash the hash of the member's switches ledger (Admin → Functions); a peer whose own hash differs pulls them; blank when none
 * @param role {@code server} or {@code witness}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Member(String nodeId, String url, MemberState state, String digestHash, String version, int protocol, List<String> tags, long since,
        boolean drained, String clusterAddress, String switchesHash, String role) {

    /**
     * The protocol this build speaks: 2, the membership on JGroups (i5). A node of protocol 1 (the gossip membership) cannot join: a cluster
     * moves to i5 by a short full stop (CLUSTER_I5_DESIGN.md Q9).
     */
    public static final int PROTOCOL = 2;

    public Member {
        url = url == null ? "" : url.replaceAll("/+$", "");
        state = state == null ? MemberState.SUSPECT : state;
        digestHash = digestHash == null ? "" : digestHash;
        version = version == null ? "" : version;
        tags = tags == null ? List.of() : List.copyOf(tags);
        clusterAddress = clusterAddress == null ? "" : clusterAddress;
        switchesHash = switchesHash == null ? "" : switchesHash;
        role = role == null || role.isBlank() ? NodeIdentity.SERVER : role;
    }

    /** A member without a cluster address, switches hash or role (a test, a member known only from the view so far). */
    public Member(String nodeId, String url, MemberState state, String digestHash, String version, int protocol, List<String> tags, long since,
            boolean drained) {
        this(nodeId, url, state, digestHash, version, protocol, tags, since, drained, "", "", NodeIdentity.SERVER);
    }

    /** A member the view names whose vitals have not arrived yet: alive, no address to call, not routed to. */
    public static Member fromView(NodeIdentity n, long at) {
        return new Member(n.id(), "", MemberState.ALIVE, "", "", PROTOCOL, List.of(), at, false, n.address(), "", n.role());
    }

    public Member withState(MemberState s, long at) {
        return new Member(nodeId, url, s, digestHash, version, protocol, tags, at, drained, clusterAddress, switchesHash, role);
    }

    public Member withDigestHash(String hash) {
        return new Member(nodeId, url, state, hash, version, protocol, tags, since, drained, clusterAddress, switchesHash, role);
    }

    public Member withDrained(boolean d) {
        return new Member(nodeId, url, state, digestHash, version, protocol, tags, since, d, clusterAddress, switchesHash, role);
    }

    public Member withAdvert(String u, List<String> t) {
        return new Member(nodeId, u, state, digestHash, version, protocol, t, since, drained, clusterAddress, switchesHash, role);
    }

    public Member withSwitchesHash(String hash) {
        return new Member(nodeId, url, state, digestHash, version, protocol, tags, since, drained, clusterAddress, hash, role);
    }

    public Member withClusterAddress(String address) {
        return new Member(nodeId, url, state, digestHash, version, protocol, tags, since, drained, address, switchesHash, role);
    }

    /** Alive, speaking our protocol, with an address, not drained and not a witness: a member a request may be sent to. */
    public boolean routable() {
        return state == MemberState.ALIVE && protocol == PROTOCOL && !drained && !url.isEmpty() && !witness();
    }

    /** A witness: membership and agreement only; never routed to for data. */
    public boolean witness() {
        return NodeIdentity.WITNESS.equals(role);
    }
}
