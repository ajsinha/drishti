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
 * Thrown by a source that holds the data asked for but cannot read it, for a reason the reader can act on: a Delta
 * table whose Parquet pages use a codec the engine does not decompress, a file in a format the connector does not
 * read. Unlike any other failure, its message is shown with the error the caller receives ({@code DRS-1003 … failed
 * reading …: <message>}) and with an incomplete search, not only logged, so it must name what cannot be read and what
 * to do, and must not carry secrets (credentials, connection strings). The read still stops: it is a failure, not
 * "not held".
 */
public final class UnreadableData extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnreadableData(String message, Throwable cause) {
        super(message, cause);
    }

    /** The first {@link UnreadableData} in {@code t}'s cause chain, if any. */
    public static java.util.Optional<UnreadableData> in(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof UnreadableData u) {
                return java.util.Optional.of(u);
            }
        }
        return java.util.Optional.empty();
    }
}
