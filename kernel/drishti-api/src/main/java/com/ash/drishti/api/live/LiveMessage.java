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

import java.time.Instant;

/**
 * One message as a transport received it, before the kernel reads it.
 *
 * @param key       the transport's message key (a Kafka record key), or null
 * @param value     the payload as text (JSON, or JSONL for {@code shape: jsonl}); null for a tombstone (a delete by key)
 * @param position  where it is in the transport (topic or queue, partition, offset or delivery tag)
 * @param timestamp when the broker received it
 * @param ack       what acknowledges it at the broker once the kernel is done with it (a queue's delivery), or null (a log that needs none)
 */
public record LiveMessage(String key, String value, LivePosition position, Instant timestamp, Runnable ack) {

    /** A message that needs no acknowledgement (a Kafka record). */
    public LiveMessage(String key, String value, LivePosition position, Instant timestamp) {
        this(key, value, position, timestamp, null);
    }

    /** The stream (topic or queue) it came from. */
    public String stream() {
        return position.stream();
    }

    /** True when the value is absent: a delete of the row with the message's key. */
    public boolean tombstone() {
        return value == null;
    }
}
