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
package com.ash.drishti.rachana.el;

import static com.ash.drishti.rachana.el.Fx.compileError;
import static com.ash.drishti.rachana.el.Fx.dec;
import static com.ash.drishti.rachana.el.Fx.eval;
import static com.ash.drishti.rachana.el.Fx.evalError;
import static com.ash.drishti.rachana.el.Fx.masked;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** The date functions (RACHANA_REFERENCE.md section F3): month ends, leap years, Excel's YEARFRAC, and the platform's calendars. */
class DateFunctionsTest {

    @Test
    void daysBetweenAndTheOldDays() {
        assertThat(eval("days_between($.TRADE_DATE, $.MATURITY_DATE)")).isEqualTo(2408L);
        assertThat(eval("days($.TRADE_DATE, $.MATURITY_DATE)")).isEqualTo(2408L);
        assertThat(eval("days_between($.MATURITY_DATE, $.TRADE_DATE)")).isEqualTo(-2408L);
        assertThat(eval("days_between('2026-10-16T09:30:00Z', '2026-10-17')")).isEqualTo(1L);
        assertThat(eval("days_between('2024-02-28', '2024-03-01')")).isEqualTo(2L);      // a leap year
        assertThat(eval("days_between('2023-02-28', '2023-03-01')")).isEqualTo(1L);
        assertThat(eval("days_between(today(), $.MATURITY_DATE)")).isEqualTo(2079L);
        assertThat(eval("days_between($.NOPE, '2026-01-01')")).isNull();
        assertThat(masked("days_between($.MTM, '2026-01-01')")).isTrue();
        evalError("days_between('x', '2026-01-01')", "days_between", "'x' is not a date (yyyy-mm-dd)");
        assertThat(eval("days('x', '2026-01-01')")).isNull();                          // the old spelling stays quiet, as it was
    }

    @Test
    void eomonthAndEdateKeepMonthEnds() {
        assertThat(eval("eomonth('2024-01-15')")).isEqualTo("2024-01-31");
        assertThat(eval("eomonth('2024-01-31', 1)")).isEqualTo("2024-02-29");
        assertThat(eval("eomonth('2023-01-31', 1)")).isEqualTo("2023-02-28");
        assertThat(eval("eomonth('2024-03-31', -1)")).isEqualTo("2024-02-29");
        assertThat(eval("eomonth('2026-12-15', 1)")).isEqualTo("2027-01-31");
        assertThat(eval("eomonth($.TRADE_DATE)")).isEqualTo("2025-11-30");
        assertThat(eval("edate('2024-01-31', 1)")).isEqualTo("2024-02-29");
        assertThat(eval("edate('2023-01-31', 1)")).isEqualTo("2023-02-28");
        assertThat(eval("edate('2024-02-29', 12)")).isEqualTo("2025-02-28");
        assertThat(eval("edate('2024-02-29', 48)")).isEqualTo("2028-02-29");
        assertThat(eval("edate('2026-10-16', -10)")).isEqualTo("2025-12-16");
        assertThat(eval("edate('2026-10-16', 1.9)")).isEqualTo("2026-11-16");          // months are cut to a whole number
        assertThat(eval("eomonth($.NOPE)")).isNull();
        assertThat(eval("edate('2026-10-16', $.NOPE)")).isNull();
        assertThat(masked("eomonth($.MTM)")).isTrue();
        assertThat(masked("edate($.TRADE_DATE, $.MTM)")).isTrue();
        evalError("eomonth('2026-02-30')", "eomonth", "'2026-02-30' is not a date");
        evalError("edate($.TRADE_DATE, 'x')", "edate", "'x' is not a number");
    }

    /** Excel's YEARFRAC on Microsoft's and ISDA's documented examples, to 8 places; ACT/ACT is ISDA's. */
    @ParameterizedTest(name = "{0} to {1} {2}")
    @CsvSource({
        "2012-01-01, 2012-07-30, 30/360,   0.58055556",       // Microsoft's YEARFRAC example, basis 0
        "2012-01-01, 2012-07-30, ACT/ACT,  0.57650273",       // basis 1 (inside one year ISDA and Excel agree)
        "2012-01-01, 2012-07-30, ACT/365F, 0.57808219",       // basis 3
        "2012-01-01, 2012-07-30, ACT/360,  0.58611111",       // basis 2
        "2012-01-01, 2012-07-30, 30E/360,  0.58055556",       // basis 4
        "2003-11-01, 2004-05-01, ACT/ACT,  0.49772438",       // ISDA's example: 61/365 + 121/366
        "2003-11-01, 2004-05-01, ACT/365F, 0.49863014",
        "2003-11-01, 2004-05-01, ACT/360,  0.50555556",
        "2003-11-01, 2004-05-01, 30/360,   0.50000000",
        "2003-11-01, 2004-05-01, 30E/360,  0.50000000",
        "2007-02-28, 2008-02-29, 30/360,   1.00000000",       // both February month ends count as the 30th
        "2007-02-28, 2008-02-29, 30E/360,  1.00277778",
        "2011-01-15, 2011-03-31, 30/360,   0.21111111",       // US: the 31st stays when the start is before the 30th
        "2011-01-15, 2011-03-31, 30E/360,  0.20833333",       // European: the 31st is always the 30th
        "2006-01-31, 2006-02-28, 30/360,   0.07777778",
        "2011-01-30, 2011-12-31, 30/360,   0.91666667",       // Microsoft's DAYS360 example: 330 days
        "2023-12-31, 2024-01-01, ACT/ACT,  0.00273973",       // a day of a common year
        "2024-12-31, 2025-01-01, ACT/ACT,  0.00273224",       // a day of a leap year
        "2012-07-30, 2012-01-01, ACT/365F, 0.57808219",       // either order, never negative
        "2026-10-16, 2026-10-16, ACT/ACT,  0.00000000",
    })
    void yearfracMatchesExcelAndIsda(String a, String b, String basis, String expected) {
        Object v = eval("yearfrac('" + a + "', '" + b + "', '" + basis + "')");
        BigDecimal got = v instanceof BigDecimal d ? d : BigDecimal.valueOf(((Number) v).longValue());
        assertThat(got.setScale(8, RoundingMode.HALF_UP)).isEqualByComparingTo(dec(expected));
    }

    @Test
    void yearfracIsDecimalAndChecksItsBasis() {
        assertThat(eval("yearfrac('2026-01-01', '2026-07-01', 'ACT/360')")).isEqualTo(dec("0.5027777777777778"));
        assertThat(eval("yearfrac($.START_DATE, $.MATURITY_DATE, 'ACT/365F')")).isInstanceOf(BigDecimal.class);
        assertThat(eval("yearfrac($.NOPE, $.MATURITY_DATE, 'ACT/360')")).isNull();
        assertThat(masked("yearfrac($.MTM, $.MATURITY_DATE, 'ACT/360')")).isTrue();
        compileError("yearfrac($.START_DATE, $.MATURITY_DATE, 'ACT/999')", "yearfrac: unknown day-count basis 'ACT/999'",
                "ACT/360, ACT/365F, 30/360, 30E/360, ACT/ACT");
        evalError("yearfrac($.START_DATE, $.MATURITY_DATE, $.BASIS)", "yearfrac", "unknown day-count basis 'ACT/999'");
        evalError("yearfrac('soon', $.MATURITY_DATE, 'ACT/360')", "yearfrac", "'soon' is not a date");
    }

    @Test
    void intckCountsBoundariesCrossed() {
        assertThat(eval("intck('month', '2026-01-31', '2026-02-01')")).isEqualTo(1L);
        assertThat(eval("intck('month', '2026-02-01', '2026-01-31')")).isEqualTo(-1L);
        assertThat(eval("intck('month', $.TRADE_DATE, today())")).isEqualTo(11L);
        assertThat(eval("intck('year', '2025-12-31', '2026-01-01')")).isEqualTo(1L);
        assertThat(eval("intck('quarter', '2026-03-31', '2026-04-01')")).isEqualTo(1L);
        assertThat(eval("intck('quarter', '2026-01-01', '2026-03-31')")).isEqualTo(0L);
        assertThat(eval("intck('week', '2026-11-07', '2026-11-08')")).isEqualTo(1L);     // Saturday to Sunday
        assertThat(eval("intck('week', '2026-11-08', '2026-11-14')")).isEqualTo(0L);
        assertThat(eval("intck('day', '2024-01-01', '2024-03-01')")).isEqualTo(60L);     // a leap February
        assertThat(eval("intck('business_day', '2026-11-25', '2026-11-27', 'USNY')")).isEqualTo(1L);   // Thanksgiving between
        assertThat(eval("intck('business_day', '2026-11-25', '2026-11-27')")).isEqualTo(1L);           // USNY by default
        assertThat(eval("intck('business_day', '2026-11-27', '2026-11-25', 'USNY')")).isEqualTo(-1L);
        assertThat(eval("intck('business_day', '2026-11-25', '2026-11-27', 'WEEKENDS')")).isEqualTo(2L);
        assertThat(eval("intck('business_day', '2026-12-24', '2026-12-29', 'USNY')")).isEqualTo(2L);
        assertThat(eval("intck('business_day', '2026-12-24', '2026-12-29', 'USNY+GBLO')")).isEqualTo(1L);   // Boxing Day on the 28th
        assertThat(eval("intck('month', $.NOPE, today())")).isNull();
        assertThat(masked("intck('day', $.MTM, today())")).isTrue();
        compileError("intck('fortnight', $.TRADE_DATE, today())", "intck: unknown interval 'fortnight'");
        compileError("intck('month', $.TRADE_DATE, today(), 'USNY')", "a calendar counts only 'business_day'");
        compileError("intck('business_day', $.TRADE_DATE, today(), 'XXNY')", "unknown calendar XXNY");
        evalError("intck('month', 'x', today())", "intck", "'x' is not a date");
    }

    @Test
    void isBusinessDayReadsTheBanksCalendars() {
        assertThat(eval("is_business_day('2026-11-26')")).isEqualTo(false);              // Thanksgiving, USNY by default
        assertThat(eval("is_business_day('2026-11-27')")).isEqualTo(true);
        assertThat(eval("is_business_day('2026-11-28')")).isEqualTo(false);              // a Saturday
        assertThat(eval("is_business_day('2026-07-03', 'USNY')")).isEqualTo(false);      // July 4th on a Saturday, kept the Friday
        assertThat(eval("is_business_day('2026-12-28', 'GBLO')")).isEqualTo(false);      // Boxing Day on a Saturday, kept the Monday
        assertThat(eval("is_business_day('2026-12-28', 'USNY')")).isEqualTo(true);
        assertThat(eval("is_business_day('2026-12-28', 'usny+gblo')")).isEqualTo(false);
        assertThat(eval("is_business_day('2024-02-29')")).isEqualTo(true);               // a leap day, a Thursday
        assertThat(eval("is_business_day('2026-10-16T10:00:00Z')")).isEqualTo(true);
        assertThat(eval("is_business_day($.MATURITY_DATE, 'USNY')")).isEqualTo(true);
        assertThat(eval("is_business_day($.NOPE)")).isNull();
        assertThat(masked("is_business_day($.MTM)")).isTrue();
        compileError("is_business_day('2026-11-26', 'NOPE')", "unknown calendar NOPE");
        evalError("is_business_day('2026-11-26', $.TRADE_ID)", "is_business_day", "unknown calendar MX-20000001");
        evalError("is_business_day('Thursday')", "is_business_day", "'Thursday' is not a date");
    }
}
