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
package com.ash.drishti.server.bi.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.common.BusinessCalendar;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * A report's {@code as-of} setting (2.6.7 patch): per-table (the default) leaves the reader's date alone; aligned takes the newest date every
 * table holds; a fixed date is always that date; a relative name is the end of its window at the asked date. A window is read as it is, and an
 * unknown rule is DRS-9030.
 */
class AsOfRuleTest {

    static final BusinessCalendar WEEKENDS = BusinessCalendar.of("WEEKENDS");
    static final LocalDate TUESDAY = LocalDate.of(2026, 10, 21);
    static final LocalDate SUNDAY = LocalDate.of(2026, 10, 18);

    private static ReportParser.Result parse(String asOf) {
        return ReportParser.parse("report: r\nmodel: trading-risk\n" + (asOf == null ? "" : "as-of: " + asOf + "\n") + """
                pages:
                  - id: p
                    visuals:
                      - { id: c, type: card, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [MTM] } }
                """);
    }

    private static ReportState state(String json) throws Exception {
        return ReportState.of(new ObjectMapper().readTree(json));
    }

    private static String applied(String asOf, String stateJson, LocalDate aligned) throws Exception {
        Report r = parse(asOf).report();
        AtomicReference<LocalDate> askedAligned = new AtomicReference<>();
        ReportState s = AsOfRule.apply(r, state(stateJson), () -> TUESDAY, WEEKENDS, d -> {
            askedAligned.set(d);
            return aligned;
        });
        return s.from() + ".." + s.to();
    }

    @Test
    void theRuleIsValidatedAndPerTableIsTheDefault() {
        assertThat(parse(null).report().asOf()).isEqualTo("per-table");
        for (String ok : new String[] {"per-table", "aligned", "2026-09-30", "last-business-day", "previous-month", "last-5-business-days", "this-quarter"}) {
            ReportParser.Result r = parse(ok);
            assertThat(r.problems()).as(ok).noneMatch(ReportParser.Problem::error);
            assertThat(r.report().asOf()).isEqualTo(ok);
        }
        for (String bad : new String[] {"latest", "all", "2026-13-40", "previous-decade"}) {
            assertThat(parse(bad).problems()).as(bad).anyMatch(p -> p.error() && "as-of".equals(p.path()) && "DRS-9030".equals(p.code()));
        }
    }

    @Test
    void perTableLeavesTheReadersDateAlone() throws Exception {
        assertThat(applied(null, "{\"dates\": {\"from\": \"2026-10-21\", \"to\": \"2026-10-21\"}}", null)).isEqualTo("2026-10-21..2026-10-21");
        assertThat(applied("per-table", "{}", null)).isEqualTo("null..null");                                // Now: each table's latest
    }

    @Test
    void alignedTakesTheNewestDateEveryTableHolds() throws Exception {
        assertThat(applied("aligned", "{\"dates\": {\"from\": \"2026-10-21\", \"to\": \"2026-10-21\"}}", LocalDate.of(2026, 10, 16))).isEqualTo("2026-10-16..2026-10-16");
        assertThat(applied("aligned", "{}", LocalDate.of(2026, 10, 16))).as("Now: from the current business date").isEqualTo("2026-10-16..2026-10-16");
        assertThat(applied("aligned", "{}", null)).as("the sources cannot say: as asked").isEqualTo("null..null");
    }

    @Test
    void aFixedDateIsAlwaysThatDate() throws Exception {
        assertThat(applied("2026-09-30", "{\"dates\": {\"from\": \"2026-10-21\", \"to\": \"2026-10-21\"}}", null)).isEqualTo("2026-09-30..2026-09-30");
        assertThat(applied("2026-09-30", "{}", null)).isEqualTo("2026-09-30..2026-09-30");
    }

    @Test
    void aRelativeNameIsTheEndOfItsWindowAtTheAskedDate() throws Exception {
        assertThat(applied("previous-month", "{}", null)).isEqualTo("2026-09-30..2026-09-30");
        assertThat(applied("last-business-day", "{\"dates\": {\"from\": \"" + SUNDAY + "\", \"to\": \"" + SUNDAY + "\"}}", null)).as("a Sunday: the Friday before")
                .isEqualTo("2026-10-16..2026-10-16");
        assertThat(applied("last-business-day", "{}", null)).isEqualTo("2026-10-21..2026-10-21");
    }

    @Test
    void aWindowIsReadAsItIs() throws Exception {
        assertThat(applied("aligned", "{\"dates\": {\"from\": \"2026-10-01\", \"to\": \"2026-10-21\"}}", LocalDate.of(2026, 10, 16))).isEqualTo("2026-10-01..2026-10-21");
        assertThat(applied("2026-09-30", "{\"dates\": {\"from\": \"2026-10-01\", \"to\": \"2026-10-21\"}}", null)).isEqualTo("2026-10-01..2026-10-21");
    }
}
