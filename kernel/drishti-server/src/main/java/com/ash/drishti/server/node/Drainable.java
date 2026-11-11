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
package com.ash.drishti.server.node;

/**
 * A part of this node that hands its work over before a stop (CD2, CLUSTER_GUIDE.md section 4.6): Drishti*net's forwarding, the VaR
 * shards (exposure's once it is sharded, CD10). {@link NodeDrain} drains every part at once and calls the node safe to stop when each is.
 */
public interface Drainable {

    /** A short name for the status: {@code net}, {@code var-shards}. */
    String name();

    /** Starts handing work over (true) or takes work again (false). Returns at once; the hand-over runs in the background. */
    void drain(boolean on);

    /** True while this part is drained, whoever drained it (this node's drain, or the part's own endpoint). */
    boolean draining();

    /** True when this part has nothing left to hand over (always, when the part is off on this server). */
    boolean safeToStop();

    /** One line for the status: what is left, or why it is safe. */
    String detail();
}
