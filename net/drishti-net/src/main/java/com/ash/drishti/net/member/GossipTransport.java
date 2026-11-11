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

import java.util.List;

/**
 * How gossip reaches another node: {@code POST /api/v1/net/gossip} and {@code GET /api/v1/net/ping} over signed HTTP in the
 * server, a map of in-process tables in tests. Implementations are thread-safe; each call has its own short timeout.
 */
public interface GossipTransport {

    /** Push-pull: sends this node's table to the node at {@code url} and returns that node's table. Throws when it cannot. */
    List<Member> exchange(String url, List<Member> mine) throws Exception;

    /** A direct probe: true when the node at {@code url} answers. */
    boolean ping(String url);

    /** An indirect probe: asks the node at {@code via} to probe {@code target}; true when {@code via} says it answered. */
    boolean pingVia(String via, String target);
}
