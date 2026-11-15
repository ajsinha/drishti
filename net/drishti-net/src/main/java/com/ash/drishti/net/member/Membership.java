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

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Who is here (CLUSTER_I5_DESIGN.md section 3). Available without a majority: a node cut off from most of the cluster still knows which
 * peers it reaches. Views are agreed and ordered ({@link View}); a crashed process leaves the view when its socket closes, a hung or cut-off
 * one after the heartbeat time-out; two partitions that see each other again merge into one view.
 *
 * <p>Providers: {@code JGroupsMembership} (the product; TCP only, never multicast) and {@code SimMembership} (tests: scripted joins,
 * losses and partitions). Both pass the same contract suite ({@code MembershipContract}). JGroups is never imported outside its provider
 * (ArchitectureRulesTest).
 */
public interface Membership extends AutoCloseable {

    /** The largest message {@link #publish} takes: vitals, a digest hash, a drain flag; never data. */
    int MAX_MESSAGE_BYTES = 4 * 1024;

    /** This node: its id, its cluster address and its role. */
    NodeIdentity self();

    /** The current view; {@link View#NONE} before {@link #join()} and after {@link #leave()}. */
    View view();

    /** Joins the cluster (called once the server is ready: its web server answers and its connectors have started). */
    void join();

    /** A clean stop: the peers drop this node at once. {@link #join()} may be called again afterwards. */
    void leave();

    /** Told every new view, on a thread of the provider's (keep it short; hand work off). */
    void onChange(Consumer<ViewChange> listener);

    /**
     * Sends a small message (at most {@link #MAX_MESSAGE_BYTES}) to every member of the view, this node included, reliably and in order
     * per sender.
     *
     * @throws IllegalArgumentException when the message is larger
     * @throws IllegalStateException when the node has not joined
     */
    void publish(String topic, byte[] small);

    /** Told every message of {@code topic}: the sender's id and the bytes (this node's own included). */
    void onMessage(String topic, BiConsumer<String, byte[]> listener);

    /**
     * Adds a seed ({@code host:port} of a cluster port) to a running node's discovery, so partitions merge through it without a restart (an
     * administrator's Admin → Cluster → Add a seed). False when this provider or its discovery takes no seed at run time.
     */
    default boolean addSeed(String hostPort) {
        return false;
    }

    /** Leaves (when joined) and frees the port and threads. */
    @Override
    void close();
}
