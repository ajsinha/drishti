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
package com.ash.drishti.server.memlake;

import com.ash.lake.memory.Retention;
import com.ash.lake.spi.RowSource;

/**
 * The server's one {@link Retention} (memory-only data never reaches a disk, a report, an e-mail, an AI provider or a download): installed
 * by {@link MemoryLakeConfiguration}; before that, and in a process without the memory lake, nothing is memory-only.
 */
public final class RetentionHolder {

    private static volatile Retention current = new Retention(RowSource.NONE, null, s -> "");

    private RetentionHolder() {
    }

    public static Retention current() {
        return current;
    }

    static void install(Retention r) {
        current = r;
    }
}
