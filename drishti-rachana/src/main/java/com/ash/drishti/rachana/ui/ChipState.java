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
package com.ash.drishti.rachana.ui;

/** A chip version's lifecycle state (UI_BOARD.md 9.1). The Chip Store moves it; the board check reads it (BC-02, BC-11, BC-W2). */
public enum ChipState {
    DRAFT, IN_REVIEW, APPROVED, PUBLISHED, CERTIFIED, DEPRECATED, WITHDRAWN;

    /** True when a shared board may use it: published, certified, or deprecated (which still renders, with BC-W2). */
    public boolean released() {
        return this == PUBLISHED || this == CERTIFIED || this == DEPRECATED;
    }
}
