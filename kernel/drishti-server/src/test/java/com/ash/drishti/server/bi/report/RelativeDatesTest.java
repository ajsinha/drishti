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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The relative-date slicer: every name worked out against a plain weekend calendar and against USNY (whose Independence Day, Saturday 4 July 2026, is observed on
 * Friday 3 July), the slicer's default, an explicit choice, and the filter the visual's query gets.
 */
class RelativeDatesTest {

    static final BusinessCalendar WEEKENDS = BusinessCalendar.of("WEEKENDS");
    static final BusinessCalendar USNY = BusinessCalendar.of("USNY");
    static final LocalDate TUE = LocalDate.of(2026, 7, 7);

    static RelativeDates.Window w(String name, LocalDate asOf, BusinessCalendar cal) {
        return RelativeDates.resolve(name, asOf, cal);
    }

    static void is(RelativeDates.Window got, String from, String to) {
        assertThat(got.from()).isEqualTo(LocalDate.parse(from));
        assertThat(got.to()).isEqualTo(LocalDate.parse(to));
    }

    @Test
    void lastNDaysWeeksAndMonthsEndOnTheAsOfDate() {
        is(w("last-7-days", TUE, WEEKENDS), "2026-07-01", "2026-07-07");
        is(w("last-1-days", TUE, WEEKENDS), "2026-07-07", "2026-07-07");
        is(w("last-4-weeks", TUE, WEEKENDS), "2026-06-10", "2026-07-07");
        is(w("last-3-months", TUE, WEEKENDS), "2026-04-08", "2026-07-07");
        is(w("last-1-months", LocalDate.of(2026, 3, 31), WEEKENDS), "2026-03-01", "2026-03-31");
        is(w("last-12-months", LocalDate.of(2026, 2, 28), WEEKENDS), "2025-03-01", "2026-02-28");
    }

    @Test
    void lastNBusinessDaysSkipWeekendsAndHolidays() {
        is(w("last-5-business-days", TUE, WEEKENDS), "2026-07-01", "2026-07-07");               // Tue, Mon, Fri, Thu, Wed
        is(w("last-5-business-days", TUE, USNY), "2026-06-30", "2026-07-07");                   // Friday 3 July is a holiday: one day further back
        is(w("last-2-business-days", LocalDate.of(2026, 7, 4), USNY), "2026-07-01", "2026-07-02");        // from a Saturday holiday: back to Thursday, and Wednesday before it
        is(w("last-1-business-days", LocalDate.of(2026, 7, 5), WEEKENDS), "2026-07-03", "2026-07-03");
    }

    @Test
    void thisAndPreviousPeriodsStartOnTheCalendarBoundary() {
        is(w("this-week", TUE, WEEKENDS), "2026-07-06", "2026-07-07");
        is(w("this-month", TUE, WEEKENDS), "2026-07-01", "2026-07-07");
        is(w("this-quarter", LocalDate.of(2026, 8, 15), WEEKENDS), "2026-07-01", "2026-08-15");
        is(w("this-year", TUE, WEEKENDS), "2026-01-01", "2026-07-07");
        is(w("previous-week", LocalDate.of(2026, 7, 8), WEEKENDS), "2026-06-29", "2026-07-05");
        is(w("previous-month", LocalDate.of(2026, 3, 15), WEEKENDS), "2026-02-01", "2026-02-28");
        is(w("previous-month", LocalDate.of(2026, 1, 15), WEEKENDS), "2025-12-01", "2025-12-31");
        is(w("previous-quarter", LocalDate.of(2026, 1, 10), WEEKENDS), "2025-10-01", "2025-12-31");
        is(w("previous-year", TUE, WEEKENDS), "2025-01-01", "2025-12-31");
        assertThat(w("all", TUE, WEEKENDS)).isNull();
    }

    @Test
    void aNameThatIsNotARelativeDateIsRefused() {
        for (String bad : List.of("", "last-0-days", "last-7-fortnights", "last--1-days", "next-7-days", "this-decade", "last-99999-days", "LAST-7-DAYS", "last-7-days; drop")) {
            assertThat(RelativeDates.valid(bad)).as(bad).isFalse();
            assertThatThrownBy(() -> RelativeDates.resolve(bad, TUE, WEEKENDS)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(RelativeDates.DEFAULT_OPTIONS).allMatch(RelativeDates::valid);
    }

    static final String REPORT = """
            report: r
            dataset: trades-basic
            pages:
              - id: p
                title: P
                visuals:
                  - { id: when, type: slicer, at: { x: 0, y: 0, w: 3, h: 3 }, wells: { field: [trade.tradeDate] }, format: { mode: relative-date, default: last-30-days } }
                  - { id: plain, type: slicer, at: { x: 3, y: 0, w: 3, h: 3 }, wells: { field: [trade.desk] } }
                  - { id: card, type: card, at: { x: 0, y: 3, w: 3, h: 3 }, wells: { values: [MTM] } }
            """;

    ReportState apply(Report r, String stateJson, BusinessCalendar cal) throws Exception {
        return RelativeDates.apply(r, ReportState.of(new ObjectMapper().readTree(stateJson)), () -> TUE, cal);
    }

    @Test
    void theDefaultAppliesUntilThePersonChoosesAndTheStateIsWorkedOutOnTheServer() throws Exception {
        Report r = ReportParser.parse(REPORT).report();
        assertThat(r).isNotNull();
        ReportState none = apply(r, "{}", USNY);
        assertThat(none.slicers().get("when").path("from").asText()).isEqualTo("2026-06-08");           // last 30 days ending on the current business date
        assertThat(none.slicers().get("when").path("to").asText()).isEqualTo("2026-07-07");
        assertThat(none.slicers().get("when").path("day").asBoolean()).isTrue();
        ReportState chosen = apply(r, "{\"slicers\":{\"when\":{\"relative\":\"last-5-business-days\"}}}", USNY);
        assertThat(chosen.slicers().get("when").path("from").asText()).isEqualTo("2026-06-30");
        assertThat(apply(r, "{\"slicers\":{\"when\":\"this-month\"}}", USNY).slicers().get("when").path("from").asText()).isEqualTo("2026-07-01");
        assertThat(apply(r, "{\"slicers\":{\"when\":{\"relative\":\"all\"}}}", USNY).slicers()).doesNotContainKey("when");              // "All dates" switches the default off
        ReportState dated = apply(r, "{\"dates\":{\"to\":\"2026-03-31\"},\"slicers\":{\"when\":{\"relative\":\"this-quarter\"}}}", USNY);
        assertThat(dated.slicers().get("when").path("from").asText()).isEqualTo("2026-01-01");           // the as-of date is the end of the report's dates
        assertThat(dated.slicers().get("when").path("to").asText()).isEqualTo("2026-03-31");
        assertThatThrownBy(() -> apply(r, "{\"slicers\":{\"when\":{\"relative\":\"someday\"}}}", USNY)).isInstanceOf(DrishtiException.class);
        assertThat(apply(ReportParser.parse(REPORT.replace(", default: last-30-days", "")).report(), "{}", USNY).slicers()).doesNotContainKey("when");
    }

    @Test
    void theVisualsQueryIsFilteredToTheWholeDaysOfTheWindow() throws Exception {
        Report r = ReportParser.parse(REPORT).report();
        ReportState st = apply(r, "{\"slicers\":{\"when\":{\"relative\":\"last-7-days\"}}}", WEEKENDS);
        Report.Page p = r.page("p");
        QueryRequest q = VisualQueries.build(r, p, p.visual("card"), st, ReportParserTest.dataset("trades-basic"), BiReportProperties.defaults(), (f, n, by, d, c, a, b) -> List.of()).query();
        assertThat(q.filters()).containsExactly(new QueryRequest.Filter("trade.tradeDate", "ge", List.of("2026-07-01")), new QueryRequest.Filter("trade.tradeDate", "lt", List.of("2026-07-08")));
    }

    @Test
    void theParserAndValidatorHoldTheSlicerToItsShape() {
        assertThat(ReportParser.parse(REPORT).problems().stream().filter(ReportParser.Problem::error).toList()).isEmpty();
        assertThat(ReportParser.parse(REPORT.replace("default: last-30-days", "default: whenever")).problems()).anyMatch(x -> x.error() && x.path().endsWith("format.default"));
        assertThat(ReportParser.parse(REPORT.replace("default: last-30-days", "options: [last-7-days, someday]")).problems()).anyMatch(x -> x.error() && x.path().endsWith("format.options"));
        assertThat(ReportParser.parse(REPORT.replace("mode: relative-date", "mode: fortnightly")).problems()).anyMatch(ReportParser.Problem::error);
    }
}
