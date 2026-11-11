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
package com.ash.drishti.kernel.api.pivot;

/**
 * How a function's total is made (HOW_IT_FITS.md section A26.3). The core never mixes them up:
 *
 * <ul>
 *   <li>{@link #MERGEABLE}: a small, fixed state with an exact merge (a sum; a sum and a count; Welford's count, mean and M2): a total is the
 *       merge of its cells' states, no second scan.</li>
 *   <li>{@link #SKETCH}: a bounded state whose merge carries a stated error (HyperLogLog, KLL, Space-Saving), shown ≈ with the error.</li>
 *   <li>{@link #HOLISTIC}: no small state (VaR from summed vectors, an exact median, an exact distinct count): every cell and every total is
 *       recomputed from its own members, never from other cells' figures.</li>
 * </ul>
 */
public enum Kind {
    MERGEABLE, SKETCH, HOLISTIC;

    /** The word the screen and the answer use. */
    public String word() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
