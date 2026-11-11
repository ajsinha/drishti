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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Where legal holds are kept: the identity database ({@link JpaHoldStore}) or {@code holds.json} ({@link FileHoldStore}). */
public interface HoldStore {

    /** Stores the hold and returns it with its id. */
    Hold place(Hold hold);

    Optional<Hold> find(long id);

    /** Every hold, newest first, or only the active ones. */
    List<Hold> list(boolean activeOnly);

    /** Releases an active hold; false when it does not exist or was released already. */
    boolean release(long id, String by, Instant at);
}
