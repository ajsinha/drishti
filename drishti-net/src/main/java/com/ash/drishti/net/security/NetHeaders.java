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
package com.ash.drishti.net.security;

/** The headers of a call between nodes (CLUSTER_DEPLOYMENT.md › Drishti*net: many servers, one Drishti, sections 7 and 9). None of them is ever sent to a browser. */
public final class NetHeaders {

    /** The calling node's id. */
    public static final String NODE = "X-Drishti-Net-Node";
    /** When the call was signed, epoch milliseconds; refused outside a 60 s window (replay). */
    public static final String TIME = "X-Drishti-Net-Time";
    /** The HMAC of method, path and query, time, node and body with the cluster key. */
    public static final String SIGNATURE = "X-Drishti-Net-Signature";
    /** {@code 1} on a forwarded request: the node receiving it answers only from itself (one hop at most). */
    public static final String HOP = "X-Drishti-Net-Hop";
    /** The short-lived on-behalf-of token of the user a forwarded call is made for. */
    public static final String ON_BEHALF_OF = "X-Drishti-Net-On-Behalf-Of";
    /** The id of the request on the node that forwarded it, for the audit trail of both nodes. */
    public static final String REQUEST = "X-Drishti-Net-Request";

    private NetHeaders() {
    }
}
