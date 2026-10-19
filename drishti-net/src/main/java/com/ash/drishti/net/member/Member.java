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
 * One row of the member table, as gossip carries it (DRISHTI_NET.md section 5).
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
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Member(String nodeId, String url, long incarnation, long heartbeat, MemberState state, String digestHash, String version,
        int protocol, List<String> tags, long since, boolean drained) {

    /** The protocol this build speaks. A member with a different one is listed but never routed to. */
    public static final int PROTOCOL = 1;

    public Member {
        url = url == null ? "" : url.replaceAll("/+$", "");
        state = state == null ? MemberState.SUSPECT : state;
        digestHash = digestHash == null ? "" : digestHash;
        version = version == null ? "" : version;
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public Member withState(MemberState s, long at) {
        return new Member(nodeId, url, incarnation, heartbeat, s, digestHash, version, protocol, tags, at, drained);
    }

    public Member withHeartbeat(long beat) {
        return new Member(nodeId, url, incarnation, beat, state, digestHash, version, protocol, tags, since, drained);
    }

    public Member withIncarnation(long inc) {
        return new Member(nodeId, url, inc, heartbeat, state, digestHash, version, protocol, tags, since, drained);
    }

    public Member withDigestHash(String hash) {
        return new Member(nodeId, url, incarnation, heartbeat, state, hash, version, protocol, tags, since, drained);
    }

    public Member withDrained(boolean d) {
        return new Member(nodeId, url, incarnation, heartbeat, state, digestHash, version, protocol, tags, since, d);
    }

    public Member withAdvert(String u, List<String> t) {
        return new Member(nodeId, u, incarnation, heartbeat, state, digestHash, version, protocol, t, since, drained);
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
