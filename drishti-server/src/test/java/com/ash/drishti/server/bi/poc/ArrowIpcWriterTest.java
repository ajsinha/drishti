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
package com.ash.drishti.server.bi.poc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** RUPAKA PHASE 0 PROOF OF CONCEPT: the Arrow IPC stream round-trips text, integers, doubles and nulls, and is framed as the format says. */
class ArrowIpcWriterTest {

    @Test
    void aStreamRoundTripsWithNullsAndUnicode() {
        var cols = List.of(
                new ArrowIpcWriter.Column("desk", ArrowIpcWriter.Kind.TEXT, new Object[] {"DESK-FI", null, "•••", ""}),
                new ArrowIpcWriter.Column("notional", ArrowIpcWriter.Kind.FLOAT64, new Object[] {1.5, 2.25, null, -3.0}),
                new ArrowIpcWriter.Column("trades", ArrowIpcWriter.Kind.INT64, new Object[] {1L, 2L, 3L, 9_000_000_000L}));
        byte[] bytes = ArrowIpcWriter.write(cols, 4);
        assertThat(bytes.length % 8).isZero();
        var t = ArrowTestReader.read(bytes);
        assertThat(t.rows()).isEqualTo(4);
        assertThat(t.columns().get("desk")).containsExactly("DESK-FI", null, "•••", "");
        assertThat(t.columns().get("notional")).containsExactly(1.5, 2.25, null, -3.0);
        assertThat(t.columns().get("trades")).containsExactly(1L, 2L, 3L, 9_000_000_000L);
    }

    @Test
    void anEmptyTableIsAValidStream() {
        var t = ArrowTestReader.read(ArrowIpcWriter.write(List.of(new ArrowIpcWriter.Column("a", ArrowIpcWriter.Kind.TEXT, new Object[0])), 0));
        assertThat(t.rows()).isZero();
        assertThat(t.columns().get("a")).isEmpty();
    }
}
