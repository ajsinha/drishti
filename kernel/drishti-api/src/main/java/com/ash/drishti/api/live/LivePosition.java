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

/**
 * Where a message is in its transport: the last tie-break of the order of two messages of one key
 * (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 4), and what reads a message back.
 *
 * @param stream    the topic or queue
 * @param partition the partition (0 for a transport without partitions)
 * @param offset    the offset in the partition, or a delivery sequence; -1 when the transport gives none
 */
public record LivePosition(String stream, int partition, long offset) {

    /** True when both positions are in the same partition of the same stream: only then do their offsets order them. */
    public boolean samePartition(LivePosition other) {
        return other != null && partition == other.partition && stream.equals(other.stream);
    }

    @Override
    public String toString() {
        return stream + "/" + partition + "@" + offset;
    }
}
