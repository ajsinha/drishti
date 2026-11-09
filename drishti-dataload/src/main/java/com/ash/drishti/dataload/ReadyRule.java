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
package com.ash.drishti.dataload;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * What a domain module adds to the checks of a file before it is pushed: the rules only it knows (the VaR engine's {@code DRS-5210}), called
 * rather than copied. The server gathers every such bean; the data inbox itself knows no domain.
 */
public interface ReadyRule {

    /** A record's columns that say who sent what, counted per date in a table. */
    List<String> SENDER_COLUMNS = List.of("SOURCE_SYSTEM", "VALUE_TYPE", "GRAIN");

    /** What a table already holds on a date. */
    interface Facts {
        /** The rows of {@code kind} in the file's lake and pack on the date, by {@link #SENDER_COLUMNS}: {@code [system, type, grain]} to rows. */
        Map<List<String>, Long> of(String kind) throws IOException;
    }

    /**
     * The file, one table and one date at a time.
     *
     * @param kind the kind the rows load as
     * @param date the business date
     * @param mode {@code replace} or {@code append}: the mode the load runs in
     * @param sample the first rows of the file
     * @param facts what the lake already holds
     */
    record Input(String kind, LocalDate date, String mode, List<JsonNode> sample, Facts facts) {}

    /** This rule's findings ({@code empty}: it says nothing about this kind). */
    List<ReadyCheck> check(Input in) throws IOException;
}
