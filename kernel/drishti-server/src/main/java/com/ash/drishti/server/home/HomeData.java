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
package com.ash.drishti.server.home;

import java.time.Instant;
import java.time.LocalDate;

/** The rows the Home services keep and pass between them; the columns are those of the {@code home/*} contracts (docs/contracts/HOME.md). */
public final class HomeData {

    private HomeData() {}

    /** One followed measure of one subject at one moment ({@code drishti/measure-value@1}). */
    public record MeasureValue(String subjectKind, String subject, String measure, double value) {
        public String key() {
            return subjectKind + "|" + subject + "|" + measure;
        }
    }

    /** What happened that is not a value move ({@code drishti/change-event@1}). */
    public record Event(String subjectKind, String subject, String kind, Instant at, String text) {}

    /** One driver's part of a move ({@code drishti/change-driver@1}). */
    public record Driver(String subjectKind, String subject, String measure, String driver, double delta) {}

    /** A figure against its limit, as an engine states it now. */
    public record LimitRow(String name, double value, Double limit) {}

    /** One KPI of the strip, a day's value. */
    public record KpiPoint(String label, LocalDate date, double value) {}

    /** One row of {@code home_changes} as the changes engine answers it. */
    public record Change(String subjectKind, String subject, String measure, String kind, Double then, Double now, double change, String why,
            String command) {}
}
