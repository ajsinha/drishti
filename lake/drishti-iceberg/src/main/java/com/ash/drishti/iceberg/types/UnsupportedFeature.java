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
package com.ash.drishti.iceberg.types;

/**
 * A part of the Iceberg spec drishti-iceberg does not read (format version 3, an ORC or Avro data file, an encrypted
 * table, a type it does not know): refused by name, never read wrongly. The message names the feature and, through
 * {@link #in(String)}, the table or file; readers that recognise this type show it to whoever asked.
 */
public final class UnsupportedFeature extends UnsupportedOperationException {

    private static final long serialVersionUID = 1L;

    private final String feature;

    public UnsupportedFeature(String feature) {
        super("drishti-iceberg does not read " + feature + "; use the connector's engine: library for this table");
        this.feature = feature;
    }

    private UnsupportedFeature(UnsupportedFeature e, String where) {
        super(e.getMessage() + " [" + where + "]");
        this.feature = e.feature;
    }

    /** The same refusal naming the table or file it was met in. */
    public UnsupportedFeature in(String where) {
        return new UnsupportedFeature(this, where);
    }

    /** What is not read ({@code format version 3}, {@code ORC data files}, …). */
    public String feature() {
        return feature;
    }
}
