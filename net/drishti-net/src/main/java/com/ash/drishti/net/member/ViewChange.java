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
package com.ash.drishti.net.member;

import java.util.ArrayList;
import java.util.List;

/**
 * What a new view changed (CLUSTER_I5_DESIGN.md section 3): who joined, who left (cleanly or found dead), whether two partitions merged.
 *
 * @param before the view before (or {@link View#NONE} on the first view)
 * @param after the new view
 * @param joined members in {@code after} that were not in {@code before}
 * @param left members in {@code before} that are not in {@code after}
 * @param merged true when the view joins partitions that had views of their own
 */
public record ViewChange(View before, View after, List<String> joined, List<String> left, boolean merged) {

    public ViewChange {
        joined = joined == null ? List.of() : List.copyOf(joined);
        left = left == null ? List.of() : List.copyOf(left);
    }

    /** The change from {@code before} to {@code after}, by member ids. */
    public static ViewChange between(View before, View after, boolean merged) {
        List<String> joined = new ArrayList<>(after.ids());
        joined.removeAll(before.ids());
        List<String> left = new ArrayList<>(before.ids());
        left.removeAll(after.ids());
        return new ViewChange(before, after, joined, left, merged);
    }
}
