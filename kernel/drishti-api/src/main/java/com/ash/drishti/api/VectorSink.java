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
package com.ash.drishti.api;

/**
 * Receives the rows of a vector column, one entity at a time, as a source decodes them (VAR_GUIDE.md section 29.3): a P&amp;L
 * vector of 509 scenario values per trade, read once per business date into a vector store. The source reuses {@code values}
 * and {@code texts} for the next row, so a sink copies what it keeps; nothing is boxed and no object is made per value.
 */
@FunctionalInterface
public interface VectorSink {

    /**
     * One entity's row.
     *
     * @param id the entity's id
     * @param values the vector, valid in {@code [0, length)}; reused by the next call
     * @param length the vector's length; {@code -1} when the entity has no vector (a failed batch row: counted, never zero)
     * @param texts the requested text columns in the order asked, {@code null} where the entity has none; reused by the next call
     */
    void row(String id, double[] values, int length, String[] texts);
}
