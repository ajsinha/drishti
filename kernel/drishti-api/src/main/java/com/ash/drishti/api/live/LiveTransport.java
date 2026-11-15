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
package com.ash.drishti.api.live;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import java.util.Optional;
import java.util.function.Function;

/**
 * The transport half of a live feed (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 2.5): a messaging plugin ({@code kafka}, later
 * {@code activemq}, {@code rabbitmq}) that implements this beside {@link com.ash.drishti.api.SourcePlugin} does four things: connect (or
 * WAIT calmly for its broker), receive, hand each message to the kernel's {@link LiveIntake} with its transport position, and acknowledge
 * when the message says so. Everything else (decode, map, validate, order, apply, notify, the live state, search, rejects) is the
 * kernel's, written once: the kernel wraps such a plugin and answers its reads from the live state.
 *
 * <p><b>The connection</b> is the connector's settings as the kernel gives them to {@code start}: a companion names its broker
 * ({@code broker: <name>}) and the kernel merges that broker's settings section ({@code [broker.<name>]}: servers, security, TLS) under
 * the connector's own before the plugin sees them. A transport reads no other place for where its broker is or how to log in.
 */
public interface LiveTransport {

    /**
     * Starts handing messages to {@code intake}; called once, after {@code start}. The transport keeps receiving (and reconnecting) until it
     * is closed; a broker that is not there is WAITING (the plugin's health), never an error.
     */
    void receive(LiveIntake intake);

    /** True when the transport replays what the broker holds at every start (a Kafka topic read from the beginning): no checkpoint needed. */
    boolean replayable();

    /**
     * One message read back by its position, for a document the live state no longer holds in memory (a Kafka record by its offset); empty
     * when the transport cannot (a queue) or the message is gone.
     */
    default Optional<LiveMessage> readBack(LivePosition at) {
        return Optional.empty();
    }

    /**
     * How the plugin reads a row as it is now (its write-back's version check, APPROVALS_GUIDE.md section 5.4): the kernel hands it the live
     * state's read. A transport that writes nothing ignores it.
     */
    default void current(Function<EntityRef, Optional<EntityDocument>> read) {
    }
}
