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
package com.ash.drishti.net.agreement;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * The cluster's small versioned register (CLUSTER_I5_DESIGN.md sections 3 and 4.1): a fixed, documented list of keys ({@code placement},
 * {@code switches/...}, {@code modules/active/...}, {@code net/key-epoch}, {@code eod/<date>}), each value small. A write is a
 * compare-and-set on the version, so two writers cannot both win.
 */
public interface Register {

    /** The largest value a key may hold: the register holds decisions, not data. */
    int MAX_VALUE_BYTES = 64 * 1024;

    /** One key as it stands: its value, its version (1 for the first write), who wrote it and when (epoch milliseconds, the referee's clock). */
    record Entry(String key, byte[] value, long version, String by, long at) {
        public Entry {
            value = value == null ? new byte[0] : value.clone();
            by = by == null ? "" : by;
        }

        @Override
        public byte[] value() {
            return value.clone();
        }

        /** The value as UTF-8 text. */
        public String text() {
            return new String(value, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** The key as last known here, or empty when it was never written. */
    Optional<Entry> get(String key);

    /**
     * Writes {@code value} when the key's version is {@code expected} (0 for a key never written); true when it was written (its version
     * is then {@code expected + 1}), false when another write came first.
     *
     * @throws AgreementUnavailableException when the cluster cannot decide now (nothing was written)
     * @throws IllegalArgumentException when the value is larger than {@link #MAX_VALUE_BYTES}
     */
    boolean compareAndSet(String key, long expected, byte[] value, String by);

    /** Told every entry written under {@code prefix} (empty: every key), on every node, after the write is agreed. */
    void onChange(String prefix, Consumer<Entry> listener);
}
