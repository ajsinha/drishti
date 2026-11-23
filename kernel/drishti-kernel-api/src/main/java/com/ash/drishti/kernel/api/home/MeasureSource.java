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
package com.ash.drishti.kernel.api.home;

import com.ash.drishti.kernel.api.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * What a Home asks an engine, one implementation per engine, found in the context (a module contributes its own: the banking module's VaR
 * and exposure). Every answer is the engine's own, read as the person given: a person never gets a figure of a subject they may not see. An
 * engine that is switched off answers nothing ({@link #on()} false) and the Home drops its rows.
 *
 * <p>TODO(R2): the {@code var}, {@code exposure} and {@code mr-cube} sources answer today from the in-tree services; when the engines are
 * hosted behind {@code engine-api} (milestone R2) these become calls to the hosted engines with the same signatures.
 */
public interface MeasureSource {

    /** The engine's id, as in {@code config/engines/<engine>.yaml} and the measures file's {@code engine:}. */
    String engine();

    /** Whether the engine runs on this node. */
    boolean on();

    /** One KPI figure, empty when the engine has none (no data for the date, the person may see nothing). */
    OptionalDouble kpi(Principal p, String measure, Map<String, Object> args, LocalDate date);

    /** A followed measure for every subject of the kind the person may see. */
    List<HomeData.MeasureValue> values(Principal p, String measure, String subjectKind, LocalDate date);

    /** A measure against its limits by subject (the use-bar tables); the limit is null where there is none. */
    List<HomeData.LimitRow> limits(Principal p, String measure, String subjectKind, LocalDate date);

    /**
     * The parts of a subject's move between two days by the measure's own engine: the reference inputs, then each input group of
     * {@code groups} swapped to its current state in turn, cumulatively, each step one row (ROLE_HOME_SUTRAS.md 4.4). An engine that cannot
     * take a group simply has fewer rows; none at all means "unexplained".
     */
    default List<HomeData.Driver> drivers(Principal p, String measure, String subjectKind, String subject, LocalDate then, LocalDate now,
            List<String> groups) {
        return List.of();
    }
}
