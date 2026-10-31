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
package com.ash.drishti.server.bi.runs;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A run refusal whose problem body carries more than a message (docs/architecture/BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), section 16): {@code DRS-9082} with the estimate
 * and the {@code confirmToken}, {@code DRS-9089} with the estimate, {@code DRS-9081} with {@code Retry-After}, {@code DRS-9029} with the
 * {@code run} hint. The API's exception handler copies {@link #properties()} into the problem.
 */
public final class RunProblem extends DrishtiException {

    private final Map<String, Object> properties;
    private final long retryAfterSeconds;

    public RunProblem(ErrorCode code, String message, Map<String, Object> properties) {
        this(code, message, properties, 0);
    }

    public RunProblem(ErrorCode code, String message, Map<String, Object> properties, long retryAfterSeconds) {
        super(code, message);
        this.properties = properties == null ? Map.of() : new LinkedHashMap<>(properties);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    /** Seconds for {@code Retry-After}, 0 for none. */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
