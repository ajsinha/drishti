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
package com.ash.drishti.compute.shard;

/**
 * The handover rule of one shard a member holds, for any sharded measure (VAR_SHARDING.md section 7), kept apart to be checked alone. A member
 * keeps serving a shard it no longer owns until every new owner has announced it ready for the grace period, then releases it; it keeps an
 * older version of a shard it owns while it builds the newer (an older one is released once the newer has been ready here for the grace
 * period), and a shard of a shard map since replaced (the cut moved) once the grace period has passed. So a shard in use always has a ready
 * holder: the release waits for the readiness of the next one, never the other way round.
 */
public final class ShardHandover {

    /** What to do with a book held. */
    public enum Verdict {
        /** Keep serving it. */
        KEEP,
        /** Release it: unmap it and delete its file (its successor has been ready long enough). */
        RELEASE
    }

    /**
     * The facts about one book held.
     *
     * @param owner this member is one of the shard's owners now (under the date's current shard map)
     * @param current it is the newest data of the shard held here, and of the current shard map
     * @param staleMap it belongs to a shard map since replaced
     * @param successorReady what replaces it is ready: every owner has announced the shard (handed over), or the newer data is built here
     */
    public record Facts(boolean owner, boolean current, boolean staleMap, boolean successorReady) {}

    private ShardHandover() {}

    /**
     * The verdict at {@code now} for a book whose successor has been ready since {@code readySince} (epoch milliseconds; 0: not ready).
     */
    public static Verdict decide(Facts f, long readySince, long now, long graceMs) {
        if (f.owner() && f.current() && !f.staleMap()) {
            return Verdict.KEEP;                                       // its owner's current book
        }
        if (!f.staleMap() && !f.successorReady()) {
            return Verdict.KEEP;                                       // nobody ready to take it: it goes on serving
        }
        return readySince > 0 && now - readySince >= graceMs ? Verdict.RELEASE : Verdict.KEEP;
    }
}
