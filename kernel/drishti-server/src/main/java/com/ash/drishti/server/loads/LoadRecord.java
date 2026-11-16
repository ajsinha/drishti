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
package com.ash.drishti.server.loads;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One announcement that a batch landed (or failed), and what Drishti did about it.
 *
 * @param id unique id of this record
 * @param attempt 1 for the first announcement of the pack, kind, date and batch; higher when a different outcome was announced for the same key
 * @param reload true when a {@code ready} load of the same kind and date was already recorded (this one replaces it)
 * @param verified {@code verified}, {@code not-found}, or {@code skipped} (a failed load, or the check could not run)
 * @param entities entities of the kind found on the date when verified (capped at {@code verify-limit})
 * @param done false while the steps are still running
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoadRecord(String id, String pack, String kind, LocalDate businessDate, String status, Long rows, Long rejected, String source,
        String batchId, String note, Instant receivedAt, String receivedBy, int attempt, boolean reload, String verified, Long entities,
        int alerts, int notices, List<Step> steps, long durationMs, boolean done) {

    public static final String READY = "ready";
    public static final String FAILED = "failed";

    /** One thing done for the load, with its own result: {@code ok}, {@code warn}, {@code failed} or {@code skipped}. */
    public record Step(String name, String status, String detail, long ms) {}

    public LoadRecord {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /** The same load with its steps and outcome filled in. */
    public LoadRecord completed(String verified, Long entities, int alerts, int notices, List<Step> steps, long durationMs) {
        return new LoadRecord(id, pack, kind, businessDate, status, rows, rejected, source, batchId, note, receivedAt, receivedBy, attempt, reload,
                verified, entities, alerts, notices, steps, durationMs, true);
    }

    /** The idempotency key: pack, kind, business date and batch id. */
    public String key() {
        return key(pack, kind, businessDate, batchId);
    }

    public static String key(String pack, String kind, LocalDate date, String batch) {
        return pack + "|" + kind + "|" + date + "|" + (batch == null ? "" : batch);
    }

    /** Whether announcing this again would say the same thing. */
    public boolean sameAs(String status2, Long rows2, Long rejected2) {
        return status.equals(status2) && java.util.Objects.equals(rows, rows2) && java.util.Objects.equals(rejected, rejected2);
    }
}
