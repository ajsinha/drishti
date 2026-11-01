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
package com.ash.drishti.server.var;

import java.time.MonthDay;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Regulatory VaR (VAR_GUIDE.md sections 18.10 to 18.12; REGULATORY_VAR_REPORTING.md section 8.2): the desk P&amp;L the backtest reads, the
 * backtest's window, confidences, zones and capital multipliers, the fiscal year the period statistics and the reports use, the folder of
 * the report definitions, and whether a demo server may seed a synthetic official history.
 *
 * @param fiscalYearEnd the last day of the bank's fiscal year, {@code MM-DD} ({@code 10-31}: FY2026 is 2025-11-01 to 2026-10-31)
 * @param pnlKind the kind holding each desk's actual and hypothetical P&amp;L per business date ({@code desk-pnl})
 * @param backtesting the regulatory backtest
 * @param averageDays the business days of the VaR average the capital formula takes (60)
 * @param reportsDir the folder of the report definitions, one YAML per report
 * @param syntheticHistory a demo server may seed synthetic official records before its oldest one (never on a bank's server)
 */
@ConfigurationProperties("drishti.regulatory")
public record RegulatoryProperties(String fiscalYearEnd, String pnlKind, Backtesting backtesting, Integer averageDays, String reportsDir,
        Boolean syntheticHistory) {

    /** The US table (12 CFR 217.204, Table 1): the multiplier for 0, 1, ... exceptions; the last for that many or more. */
    public static final List<Double> US_MULTIPLIERS = List.of(3.0, 3.0, 3.0, 3.0, 3.0, 3.4, 3.5, 3.65, 3.75, 3.85, 4.0);

    /**
     * @param window the business days counted (250)
     * @param confidences the VaR confidences tested; the first is the one the zone and the multiplier read (0.99, 0.975)
     * @param green the most exceptions of the green zone (4)
     * @param amber the most exceptions of the amber zone (9); more is red
     * @param multipliers the capital multiplier by exceptions (index), the last for that many or more (the US table)
     * @param count which count sets the zone and the multiplier: {@code actual} (the US rule), {@code hypothetical} or {@code higher}
     */
    public record Backtesting(Integer window, List<Double> confidences, Integer green, Integer amber, List<Double> multipliers, String count) {

        public Backtesting {
            window = window == null || window < 20 ? 250 : window;
            confidences = confidences == null || confidences.isEmpty() ? List.of(0.99, 0.975)
                    : confidences.stream().map(c -> VarProperties.checkConfidence(c, "drishti.regulatory.backtesting.confidences")).toList();
            green = green == null || green < 0 ? 4 : green;
            amber = amber == null || amber < green ? Math.max(9, green) : amber;
            multipliers = multipliers == null || multipliers.isEmpty() ? US_MULTIPLIERS : List.copyOf(multipliers);
            count = count == null || count.isBlank() ? "actual" : count.strip().toLowerCase(java.util.Locale.ROOT);
            if (!List.of("actual", "hypothetical", "higher").contains(count)) {
                throw new IllegalArgumentException("drishti.regulatory.backtesting.count is actual, hypothetical or higher, not " + count);
            }
        }

        /** The zone of a count: green, amber or red. */
        public String zone(int exceptions) {
            return exceptions <= green ? "green" : exceptions <= amber ? "amber" : "red";
        }

        /** The capital multiplier of a count (the table's last value for that many or more). */
        public double multiplier(int exceptions) {
            return multipliers.get(Math.min(Math.max(exceptions, 0), multipliers.size() - 1));
        }
    }

    public RegulatoryProperties {
        fiscalYearEnd = fiscalYearEnd == null || fiscalYearEnd.isBlank() ? "10-31" : fiscalYearEnd.strip();
        MonthDay.parse("--" + fiscalYearEnd);                          // a wrong value refuses the start, in words
        pnlKind = pnlKind == null || pnlKind.isBlank() ? "desk-pnl" : pnlKind.strip();
        backtesting = backtesting == null ? new Backtesting(null, null, null, null, null, null) : backtesting;
        averageDays = averageDays == null || averageDays < 1 ? 60 : averageDays;
        reportsDir = reportsDir == null || reportsDir.isBlank() ? "./config/reports" : reportsDir.strip();
        syntheticHistory = syntheticHistory != null && syntheticHistory;
    }

    /** The fiscal year end as a month and day. */
    public MonthDay yearEnd() {
        return MonthDay.parse("--" + fiscalYearEnd);
    }
}
