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

import java.time.LocalDate;
import java.util.List;

/**
 * Telling the server that data has moved (DATALOAD_INBOX.md section 4.9): one data-load signal per pack, kind, business date and load,
 * {@code ready}. The server answers it with the steps of any data-load signal (its {@code LoadService}: refresh, verify, alerts, smoke,
 * notices, the Data loads page). Signals are idempotent per pack, kind, date and batch, so one sent again after a restart is recorded once.
 */
@FunctionalInterface
public interface Signal {

    /**
     * One table's business date committed by the inbox.
     *
     * @param pack the pack
     * @param kind the kind
     * @param date the business date committed
     * @param rows the rows committed on the date
     * @param rejected the rows rejected by the load (null when the load spans several dates: it is not known per date)
     * @param batch the batch ({@code inbox:<run>#<load>}): the same signal sent twice is recorded once
     * @param files the inbox files the load read
     */
    record Landed(String pack, String kind, LocalDate date, long rows, Long rejected, String batch, List<String> files) {}

    /**
     * Tells the server. An {@link IllegalArgumentException} is a refusal (a pack no longer loaded, a date in the future): recorded and not
     * sent again; any other exception leaves the signal pending, to be sent at the next cycle.
     */
    void landed(Landed l);

    /** No server (a test, or the module on its own): nothing is told. */
    Signal NONE = l -> { };
}
