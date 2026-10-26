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
package com.ash.drishti.server.exposure;

import com.ash.drishti.compute.ccr.ExposureBook;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One loaded business date of the exposure engine: the run's {@link ExposureBook}, the reference data the scopes read beside it (every
 * netting set the date knows, with or without paths; the counterparties' names; the connected groups), and what the date was read from.
 *
 * @param version the exposure paths' data version (the pin an answer is stamped with)
 * @param known every netting set of the date's {@code netting-set} records: its counterparty and legal entity, with paths or not
 * @param names the counterparties' names (for labels; masked per viewer)
 * @param groups each connected group's member counterparties
 * @param limits each counterparty's stored credit limit ({@code credit-limit}, read as stored: LIM stays parked), by counterparty
 * @param versions every input kind's data version on the date
 * @param warnings what every answer of this date says about it (a date answered in place of another, a run refused)
 */
record ExposureDate(LocalDate date, ExposureBook book, Map<String, Known> known, Map<String, String> names, Map<String, List<String>> groups,
        Map<String, Limit> limits, String version, Instant loadedAt, long loadMs, Map<String, Object> intake, Map<String, String> versions, List<String> warnings) {

    /** A netting set the reference data knows. */
    record Known(String id, String counterparty, String legalEntity, String csa, int members) {}

    /** A counterparty's credit limit as the limits system stores it: its id, the amount (USD) and the measure it is set on. */
    record Limit(String id, double amount, String measure) {}

    ExposureDate {
        limits = limits == null ? Map.of() : Map.copyOf(limits);
        versions = versions == null ? Map.of() : Map.copyOf(versions);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    ExposureDate warned(String warning) {
        List<String> w = new ArrayList<>(warnings);
        w.add(warning);
        return new ExposureDate(date, book, known, names, groups, limits, version, loadedAt, loadMs, intake, versions, w);
    }

    /** The input tables' versions and the run, as an official capture and the provenance record them. */
    Map<String, Object> inputs() {
        Map<String, Object> m = new java.util.LinkedHashMap<>(versions);
        m.put("runId", book.runId());
        m.put("batchId", book.batchId());
        return m;
    }
}
