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

import static com.ash.drishti.rachana.el.Fy.eval;
import static com.ash.drishti.rachana.el.Fy.evalError;
import static com.ash.drishti.rachana.el.Fy.masked;
import static com.ash.drishti.rachana.el.Fy.near;
import static com.ash.drishti.rachana.el.Fy.num;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Finance on given flows (FUNCTION_CATALOGUE.md section 8) on the examples of Excel's documentation (NPV, XNPV, IRR, XIRR, MIRR, PV, FV,
 * PMT, IPMT, PPMT, NPER, RATE, CUMIPMT, CUMPRINC, EFFECT, NOMINAL, FVSCHEDULE, RRI, PDURATION, ACCRINTM, DOLLARDE, DOLLARFR, DURATION),
 * to the digits those pages print; the root finders' refusals in words; and the rules on empty, masked and wrong-typed flows.
 */
class FinanceFunctionsTest {

    @Test
    void presentValuesAndRatesOfReturnMatchExcel() {
        near("npv(0.1, $.NPV)", 1188.443412, 1e-6);                             // NPV(10%, -10000, 3000, 4200, 6800)
        near("xnpv(0.09, $.XF, $.XD)", 2086.647602, 1e-6);                      // XNPV
        near("xirr($.XF, $.XD)", 0.373362535, 1e-8);                            // XIRR (Excel stops at 1e-8)
        near("irr($.IRR1)", -0.021244848, 1e-9);                                // IRR: -2.1 %
        near("irr($.IRR2)", 0.086630948, 1e-9);                                 // 8.7 %
        near("irr($.IRR3, -0.1)", -0.443506941, 1e-9);                          // -44.4 % from a guess of -10 %
        near("mirr($.MIRR, 0.1, 0.12)", 0.126094, 1e-6);                         // MIRR: 12.61 %
        near("npv(irr($.IRR2), $.IRR2) ", 0, 1e-6);                              // the root makes the value 0
        near("fvschedule(1, $.FVS)", 1.33089, 1e-12);                           // FVSCHEDULE
    }

    @Test
    void annuitiesMatchExcel() {
        near("pv(0.08 / 12, 12 * 20, 500, 0, 0)", -59777.14585, 1e-5);          // PV
        near("fv(0.06 / 12, 10, -200, -500, 1)", 2581.403374, 1e-6);             // FV
        near("pmt(0.08 / 12, 10, 10000)", -1037.032089, 1e-6);                   // PMT
        near("ipmt(0.1 / 12, 1, 3 * 12, 8000)", -66.66666667, 1e-8);             // IPMT
        near("ipmt(0.1, 3, 3, 8000)", -292.4471299, 1e-7);
        near("ppmt(0.1 / 12, 1, 2 * 12, 2000)", -75.62318601, 1e-8);             // PPMT
        near("ppmt(0.08, 10, 10, 200000)", -27598.05346, 1e-5);
        near("nper(0.12 / 12, -100, -1000, 10000, 1)", 59.6738657, 1e-7);        // NPER
        near("nper(0.12 / 12, -100, -1000, 10000)", 60.0821229, 1e-7);
        near("nper(0.12 / 12, -100, -1000)", -9.57859404, 1e-8);
        near("rate(4 * 12, -200, 8000)", 0.007701472, 1e-9);                     // RATE
        near("cumipmt(0.09 / 12, 30 * 12, 125000, 13, 24, 0)", -11135.23213, 1e-5);   // CUMIPMT
        near("cumipmt(0.09 / 12, 30 * 12, 125000, 1, 1, 0)", -937.5, 1e-9);
        near("cumprinc(0.09 / 12, 30 * 12, 125000, 13, 24, 0)", -934.1071234, 1e-6);  // CUMPRINC
        near("cumprinc(0.09 / 12, 30 * 12, 125000, 1, 1, 0)", -68.27827118, 1e-7);
        near("pmt(0, 10, 1000)", -100, 1e-12);                                   // a rate of 0
        near("pv(0, 10, -100)", 1000, 1e-12);
        near("ipmt(0.01, 1, 12, 1000, 0, 1)", 0, 0);                             // paid at the start: no interest in the first
    }

    @Test
    void ratesDollarsAccrualAndDuration() {
        near("effect(0.0525, 4)", 0.053542667, 1e-9);                           // EFFECT
        near("nominal(0.053543, 4)", 0.052500319, 1e-9);                        // NOMINAL
        near("rri(96, 10000, 11000)", 0.0009933, 1e-7);                         // RRI
        near("pduration(0.025, 2000, 2200)", 3.859866163, 1e-9);                 // PDURATION
        assertThat(eval("accrued(0.1, '2008-04-01', '2008-06-15', 'ACT/365F', 1000)")).isEqualTo(new BigDecimal("20.54794520547945"));   // ACCRINTM
        assertThat(eval("accrued(0.04, '2026-06-15', '2026-12-15')")).isEqualTo(new BigDecimal("0.02"));      // 30/360, par 1
        assertThat(eval("dollarde(1.02, 16)")).isEqualTo(new BigDecimal("1.125"));                         // DOLLARDE
        assertThat(eval("dollarde(1.1, 32)")).isEqualTo(new BigDecimal("1.3125"));
        assertThat(eval("dollarfr(1.125, 16)")).isEqualTo(new BigDecimal("1.02"));                          // DOLLARFR
        assertThat(eval("dollarfr(1.125, 32)")).isEqualTo(new BigDecimal("1.04"));
        near("macaulay_duration($.BOND, $.TIMES, 0.09, 2)", 10.9191453, 1e-7);   // DURATION, 2018-07-01 to 2048-01-01, 8 %, 9 %, 2
        near("modified_duration($.BOND, $.TIMES, 0.09, 2)", 10.9191453 / 1.045, 1e-7);
        // convexity against a central second difference of the price in the yield
        double h = 1e-4;
        double pu = price(0.09 + h);
        double pd = price(0.09 - h);
        double pm = price(0.09);
        near("convexity($.BOND, $.TIMES, 0.09, 2)", (pu - 2 * pm + pd) / (h * h) / pm, 1e-4);
        assertThat(num("npv(0.045, $.BOND)")).isCloseTo(pm, org.assertj.core.api.Assertions.within(1e-9));
        // dates counted from the settlement in days / 365: 2027-10-16 is 1 year, 2028-10-16 is 731 / 365
        double t2 = 731 / 365.0;
        near("macaulay_duration($.B2, $.BDATES, 0.05, 1, '2026-10-16')", (5 / 1.05 + 105 * t2 * Math.pow(1.05, -t2))
                / (5 / 1.05 + 105 * Math.pow(1.05, -t2)), 1e-12);
        near("macaulay_duration($.B2, $.T12, 0.05, 0)", (5 * Math.exp(-0.05) + 210 * Math.exp(-0.1)) / (5 * Math.exp(-0.05)
                + 105 * Math.exp(-0.1)), 1e-12);                                 // continuous compounding
    }

    /** The bond's price per 100 at a yield compounded twice a year. */
    private static double price(double y) {
        double p = 0;
        for (int k = 1; k <= 59; k++) {
            p += (k == 59 ? 104 : 4) / Math.pow(1 + y / 2, k);
        }
        return p;
    }

    @Test
    void refusalsInWordsNeverAFigureThatIsNotARoot() {
        evalError("irr($.POS)", "irr", "never change sign");
        evalError("xirr($.B2, $.BDATES)", "xirr", "never change sign");
        evalError("npv(-1, $.NPV)", "npv", "not above −1");
        evalError("xnpv(0.1, $.XF, $.BACK)", "xnpv", "differ in number");
        evalError("xnpv(0.1, $.IRR3, $.XS)", "xnpv", "a date");
        evalError("ipmt(0.1, 4, 3, 8000)", "ipmt", "not from 1 to the number of periods");
        evalError("pmt(0.1, 0, 1000)", "pmt", "periods is 0");
        evalError("pv(0.1, 10, 100, 0, 2)", "pv", "type 2");
        evalError("cumipmt(0.01, 12, 1000, 5, 3, 0)", "cumipmt", "start first");
        evalError("macaulay_duration($.B2, $.BDATES, 0.05)", "macaulay_duration", "settlement date");
        evalError("dollarde(1.02, 0)", "dollarde", "under 1");
        evalError("accrued(0.04, '2026-12-15', '2026-06-15')", "accrued", "before the start");
        evalError("accrued(0.04, '2026-06-15', '2026-12-15', 'ACT/999')", "accrued", "unknown day-count basis");
        evalError("npv(0.1, $.TXT)", "npv", "'abc' is not a number");
        assertThat(eval("iferror(irr($.POS), 'no rate')")).isEqualTo("no rate");
    }

    @Test
    void emptyAndMaskedFlows() {
        assertThat(eval("npv(0.1, $.GAP)")).isNull();                             // a missing flow is never skipped
        assertThat(eval("irr($.GAP)")).isNull();
        assertThat(eval("npv(0.1, $.EMPTY)")).isNull();
        assertThat(eval("npv($.NOPE, $.NPV)")).isNull();
        assertThat(eval("pmt(0.1, 10, $.NOPE)")).isNull();
        assertThat(masked("npv(0.1, $.HIDDEN)")).isTrue();                       // a masked flow masks the figure
        assertThat(masked("irr($.HIDDEN)")).isTrue();
        assertThat(masked("pv($.MTM, 10, 100)")).isTrue();
        evalError("npv(0.1, 'abc')", "npv", "is not a list");
    }
}
