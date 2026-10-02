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
package com.ash.drishti.engine.source;

import com.ash.drishti.api.UnreadableData;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/**
 * A read that a source failed ({@code DRS-1003}) or did not finish in time ({@code DRS-1004}), naming the source and a
 * reason fit to show to whoever asked: the message of an {@link UnreadableData} (the source says what cannot be read
 * and what to do), "did not answer within …", or only the kind of failure (driver and I/O messages can carry hosts and
 * paths; they go to the log and Admin → Health).
 */
public final class SourceFailure extends DrishtiException {

    private static final long serialVersionUID = 1L;

    private final String source;
    private final String reason;

    SourceFailure(ErrorCode code, String source, String reason, String message, Throwable cause) {
        super(code, message, cause);
        this.source = source;
        this.reason = reason;
    }

    /** The connector that failed (null when a read timed out before any connector was asked). */
    public String source() {
        return source;
    }

    /** Why, as shown with the error and with an incomplete search. */
    public String reason() {
        return reason;
    }

    /** The reason to show for {@code e}, a source's exception or a timeout of {@code budget}. */
    static String reason(Throwable e, Duration budget) {
        Throwable t = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
        if (t instanceof SourceFailure f) {
            return f.reason();
        }
        if (t instanceof TimeoutException) {
            return "did not answer within " + budget.toMillis() + " ms";
        }
        var unreadable = UnreadableData.in(t);
        if (unreadable.isPresent()) {
            return unreadable.get().getMessage();
        }
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return "failed (" + root.getClass().getSimpleName() + "; the server log and Admin → Health say more)";
    }
}
