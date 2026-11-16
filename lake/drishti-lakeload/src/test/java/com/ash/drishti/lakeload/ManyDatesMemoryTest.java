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
package com.ash.drishti.lakeload;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The memory budget holds for a table of many dates (the demo lake's Murex trades: 60 dates of large nested trades ran the loader out of a
 * 12 GB heap): every parser thread kept a whole 8 MB arena for every date it saw (24 threads × 60 dates), and the budget counted only the
 * bytes used. Arenas now grow from 64 KB, and the budget counts the heap held, so such a load spills instead.
 */
class ManyDatesMemoryTest {

    @TempDir
    Path tmp;

    @Test
    void aBufferHoldingAFewRowsHoldsLittleAndSaysWhatItHolds() {
        Runs.Buffer b = new Runs.Buffer(8 << 20);
        b.add(new byte[1000], 1000);
        assertThat(b.allocated).as("one row: one small arena, not 8 MB").isLessThanOrEqualTo(64 * 1024);
        for (int i = 0; i < 5000; i++) {
            b.add(new byte[1000], 1000);
        }
        assertThat(b.allocated).as("the arenas held cover the rows").isGreaterThanOrEqualTo(b.bytes - 8L * b.n);
        assertThat(b.held()).isGreaterThan(b.allocated);
    }

    /** A Murex-shaped trade: a deep document with many legs and cash flows (about 20 KB as text). */
    static String trade(String id, String date) {
        StringBuilder legs = new StringBuilder();
        for (int l = 0; l < 4; l++) {
            StringBuilder flows = new StringBuilder();
            for (int f = 0; f < 40; f++) {
                flows.append(f == 0 ? "" : ",").append("{\"PAY_DATE\":\"2027-0").append(1 + f % 9).append("-15\",\"AMOUNT\":").append(1000.5 + f)
                        .append(",\"RATE\":{\"INDEX\":{\"NAME\":\"SOFR\",\"TENOR\":\"3M\"},\"SPREAD\":0.0025,\"FIXING\":[").append(f).append(",")
                        .append(f + 1).append("]},\"NOTE\":\"cash flow ").append(f).append(" of leg ").append(l).append("\"}");
            }
            legs.append(l == 0 ? "" : ",").append("{\"LEG\":").append(l).append(",\"NOTIONAL\":1000000,\"INDEX\":{\"NAME\":\"SOFR\"},\"FLOWS\":[")
                    .append(flows).append("]}");
        }
        return "{\"TRADE_ID\":\"" + id + "\",\"BUSINESS_DATE\":\"" + date + "\",\"SOURCE_SYSTEM\":\"MUREX\",\"PRODUCT_GROUP\":\"IRD\",\"LEGS\":["
                + legs + "]}";
    }

    @Test
    void sixtyDatesOfLargeTradesLoadWithinASmallBudgetBySpilling() throws Exception {
        Path in = Files.createDirectories(tmp.resolve("in"));
        java.time.LocalDate d = java.time.LocalDate.parse("2026-07-01");
        int dates = 60;
        int perDate = 12;
        for (int i = 0; i < dates; i++) {
            String day = d.plusDays(i).toString();
            List<String> lines = new ArrayList<>();
            for (int t = 0; t < perDate; t++) {
                lines.add(trade("M-" + t, day));
            }
            Files.write(in.resolve("trade_murex." + day + ".jsonl"), lines);
        }
        Path lake = tmp.resolve("lake");
        String yaml = "source:\n  files: '" + in + "/*.jsonl'\n  id: TRADE_ID\n  promote: [TRADE_ID, SOURCE_SYSTEM]\n  partition-by: [PRODUCT_GROUP]\n"
                + "target:\n  format: delta\n  lake: '" + lake + "'\n  domain: trading\n  table: trade_murex\n  kind: trade\n"
                + "parallel:\n  parsers: 24\n  readers: 8\nmemory:\n  budget-mb: 32\n";
        JsonNode s = LakeLoadTest.load(tmp, yaml);
        assertThat(s.path("exit").asInt()).as(s.path("said").asText() + s.path("error").asText()).isZero();
        assertThat(s.path("accepted").asLong()).isEqualTo((long) dates * perDate);
        assertThat(s.path("commit").path("dates").size()).isEqualTo(dates);
        assertThat(s.path("memory").path("spilledMb").asLong()).as("the arenas of 24 threads × 60 dates count against the budget: it spills")
                .isPositive();
    }
}
