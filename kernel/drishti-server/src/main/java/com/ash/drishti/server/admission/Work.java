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
package com.ash.drishti.server.admission;

import com.ash.drishti.common.ErrorCode;

/** The kinds of new work busy protection may ask to wait (docs/guides/OPERATIONS_GUIDE.md, "Busy protection"), each with its code. */
public enum Work {
    /** A new sign-in: waits only at the highest memory level; people already signed in keep working. */
    SIGN_IN(ErrorCode.BUSY_SIGN_IN, "Sign in", false),
    /** A business date opened or loaded (VaR, exposure). */
    LOAD(ErrorCode.BUSY_LOAD, "Open a date", true),
    /** A Data view download. */
    DOWNLOAD(ErrorCode.BUSY_DOWNLOAD, "Download", true),
    /** A BI run. */
    BI_RUN(ErrorCode.BUSY_BI_RUN, "BI run", true),
    /** A bulk load of a pack's data. */
    BULK_LOAD(ErrorCode.BUSY_BULK_LOAD, "Bulk load", true),
    /** A large pivot view: more cells than {@code drishti.pivot.busy-cells} (BI_GUIDE.md). */
    PIVOT_VIEW(ErrorCode.BUSY_PIVOT_VIEW, "Pivot view", true),
    /** An Arrow Flight read (Admin → Data out): heavy, as a download is; it waits as long as a download. */
    FLIGHT(ErrorCode.BUSY_FLIGHT, "Flight read", true);

    private final ErrorCode code;
    private final String label;
    private final boolean heavy;

    Work(ErrorCode code, String label, boolean heavy) {
        this.code = code;
        this.label = label;
        this.heavy = heavy;
    }

    public ErrorCode code() {
        return code;
    }

    /** What Health's table says in its What column when nothing more is known. */
    public String label() {
        return label;
    }

    /** Heavy work: waits at the heavy levels and when an operator holds new heavy work. */
    public boolean heavy() {
        return heavy;
    }

    /** True when the work writes to the local disk (a load, a download, a bulk load): it waits when the disk is nearly full. */
    boolean writes() {
        return this == LOAD || this == DOWNLOAD || this == BULK_LOAD;
    }
}
