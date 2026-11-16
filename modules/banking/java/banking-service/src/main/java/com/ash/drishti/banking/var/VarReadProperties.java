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
package com.ash.drishti.banking.var;

import com.ash.drishti.api.VectorReads;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How a date's first open reads its vectors from the lake (VAR_GUIDE.md section 27a.6): the date's files decoded several at once, each
 * on its own thread, and handed to the load in file order (the same rows in the same order, so the same date).
 *
 * @param threads files decoded at once for one load (0: half the cores, at most 8, at least 1; 1: one after the other on the load's
 *     own thread)
 * @param bufferMb the most decoded rows waiting for one load, in MB (0: 64; never above a thirty-second of the heap)
 */
@ConfigurationProperties("drishti.var.read")
public record VarReadProperties(Integer threads, Integer bufferMb) {

    /** The most files decoded at once by default, however many cores. */
    static final int MAX_DEFAULT_THREADS = 8;

    public VarReadProperties {
        threads = threads == null || threads <= 0
                ? Math.max(1, Math.min(MAX_DEFAULT_THREADS, Runtime.getRuntime().availableProcessors() / 2)) : threads;
        bufferMb = bufferMb == null || bufferMb <= 0 ? 64 : bufferMb;
    }

    /** The defaults. */
    static VarReadProperties defaults() {
        return new VarReadProperties(null, null);
    }

    /** The read's options: the buffer held to a thirty-second of the heap, so the decoded rows never crowd the dates out of it. */
    VectorReads.Options options() {
        long bytes = Math.min((long) bufferMb << 20, Runtime.getRuntime().maxMemory() / 32);
        return new VectorReads.Options(threads, bytes);
    }
}
