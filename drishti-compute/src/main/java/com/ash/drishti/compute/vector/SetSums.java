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
package com.ash.drishti.compute.vector;

/**
 * Rows that sum a whole set at once rather than run by run (VAR_SHARDING.md): a date sharded across Drishti*net members, whose rows
 * are partly on other servers, gives each shard's part of the set to one owner and adds the partial sums. {@link ParallelSums} hands such
 * a store the whole set; every other store is summed run by run as before.
 */
public interface SetSums {

    /** True when this store sums a set itself (its rows are not all here); false: summed run by run like any store. */
    boolean sumsSets();

    /** The sum of the rows of {@code set} ({@code width} doubles, float64), {@code sums} for the parts summed here. */
    double[] sumSet(OrdinalSet set, ParallelSums sums);
}
