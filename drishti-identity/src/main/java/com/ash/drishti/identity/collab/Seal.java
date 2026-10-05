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
package com.ash.drishti.identity.collab;

import java.util.Optional;

/**
 * A tamper-evidence seal kept apart from the rows it covers: {@code count} and {@code hash} under a key ({@code thread:<id>} the
 * number and last hash of a thread's revisions, {@code comment:<id>} the hash of the live comment row, {@code hold:<id>} and
 * {@code holds:head}, {@code audit:<id>} and {@code audit:head}). Evidence of change, not prevention.
 */
public record Seal(long count, String hash) {

    /** Where seals are kept: the identity database ({@link JpaSealStore}) or {@code seals.log} ({@link FileSealStore}). */
    public interface Store {
        Optional<Seal> get(String key);

        void put(String key, Seal seal);
    }
}
