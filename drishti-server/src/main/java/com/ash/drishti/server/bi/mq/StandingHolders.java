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
package com.ash.drishti.server.bi.mq;

import com.ash.drishti.server.security.Principal;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;

/**
 * The other nodes of a cluster as the standing queries see them: a subscription is held on the node that made it (its id names it, {@link StandingId}),
 * and a stream or a close asked of another node is relayed from the holder, for the caller (on-behalf-of: the same person and rights there, so a stream is
 * always as the subscriber). Provided by Drishti*net; absent off a cluster.
 */
public interface StandingHolders {

    /** This node's name while it is a member of a running cluster, else null. */
    String self();

    /** True for a request another node relayed here: it is answered here, never relayed again (one hop). */
    boolean forwarded(jakarta.servlet.http.HttpServletRequest request);

    /** Whether {@code node} is a member alive now. */
    boolean alive(String node);

    /** {@code GET pathAndQuery} on {@code node} as {@code p}, as a stream the caller closes; IOException when the node cannot be reached. */
    HttpResponse<InputStream> stream(String node, String pathAndQuery, Principal p) throws IOException;

    /** {@code DELETE path} on {@code node} as {@code p}: its HTTP status; IOException when the node cannot be reached. */
    int delete(String node, String path, Principal p) throws IOException;
}
