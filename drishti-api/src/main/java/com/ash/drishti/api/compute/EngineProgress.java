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
package com.ash.drishti.api.compute;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where a running engine says how far it has got (DATA_ARCHITECTURE.md › Reactive connectors, section 6.2, step 9): a fraction and, if it has one, a partial
 * result ({@code {"done": 7, "of": 12, "estimate": {...}}}). The server sends it on the requester's live channel; the panel paints it,
 * marked <em>computing 58%</em>. Calls must be quick.
 */
@FunctionalInterface
public interface EngineProgress {

    EngineProgress NONE = data -> { };

    void partial(Map<String, Object> data);

    /** {@code done} of {@code of} steps are done. */
    default void fraction(long done, long of) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("done", done);
        m.put("of", of);
        partial(m);
    }
}
