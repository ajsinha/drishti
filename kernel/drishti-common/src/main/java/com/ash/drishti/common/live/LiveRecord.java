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
package com.ash.drishti.common.live;

import com.ash.drishti.api.live.LivePosition;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * One live record: every message, whatever its shape, becomes this inside the server (docs/architecture/LIVE_CONNECTORS_DESIGN.md
 * section 3.1). The document is the kind's row with the connector's {@code where}, {@code set} and {@code map} applied.
 *
 * @param kind      the kind
 * @param key       the contract key's values joined with {@code |}
 * @param op        upsert, delete or patch
 * @param order     the values of the contract's {@code live.order} fields, most significant first; empty: arrival order
 * @param changedAt the event time (the {@code changed-at} field), else the broker's time
 * @param changedBy who changed it (the {@code changed-by} field), or null
 * @param asOf      its business date (the {@code as-of} field or the envelope's {@code asOf}), or null: the live date
 * @param doc       the row (null for a delete)
 * @param position  where it was in the transport
 * @param source    the live connector it came from
 */
public record LiveRecord(String kind, String key, Op op, List<Object> order, Instant changedAt, String changedBy, String asOf, ObjectNode doc,
        LivePosition position, String source) {

    /** What a record does to its row (section 3.2; {@code replace} is a set of upserts bracketed by markers, section 3.5). */
    public enum Op { UPSERT, DELETE, PATCH }

    public LiveRecord {
        order = order == null ? List.of() : List.copyOf(order);
    }

    /**
     * The order of two records of one key (section 4): their order values, left to right (numbers by value, text as written: ISO times
     * compare as text), then, in the same partition of the same stream, the transport position. Two records ordered by nothing else in
     * different partitions compare equal (a redelivery is a duplicate).
     */
    public static final Comparator<LiveRecord> ORDER = (a, b) -> compare(a.order, a.position, b.order, b.position);

    /** As {@link #ORDER}, on the parts. */
    public static int compare(List<Object> orderA, LivePosition a, List<Object> orderB, LivePosition b) {
        if (!orderA.isEmpty() && !orderB.isEmpty()) {
            for (int i = 0; i < Math.min(orderA.size(), orderB.size()); i++) {
                int c = value(orderA.get(i), orderB.get(i));
                if (c != 0) {
                    return c;
                }
            }
        }
        if (a != null && a.samePartition(b)) {
            return Long.compare(a.offset(), b.offset());
        }
        return 0;
    }

    private static int value(Object a, Object b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;                // a missing order value is older than any
        }
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }
}
