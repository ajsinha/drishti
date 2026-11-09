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
package com.ash.drishti.iceberg.meta;

import java.util.ArrayList;
import java.util.List;

/** A sort order: what a table's files are sorted by (Drishti's layout: {@code id} ascending). Order 0 is unsorted. */
public record SortOrder(int orderId, List<Field> fields) {

    /** One sort key: a transform of a column, its direction and where nulls go. */
    public record Field(int sourceId, Transform transform, boolean ascending, boolean nullsFirst) {}

    public SortOrder {
        fields = List.copyOf(fields);
    }

    public static final SortOrder UNSORTED = new SortOrder(0, List.of());

    /** Whether the files are sorted by this column alone, ascending (so per-file id bounds do not overlap). */
    public boolean sortedBy(int fieldId) {
        return !fields.isEmpty() && fields.get(0).sourceId() == fieldId && fields.get(0).ascending()
                && fields.get(0).transform() instanceof Transform.Identity;
    }
}
