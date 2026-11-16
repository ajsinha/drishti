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
package com.ash.drishti.diskcache;

import org.rocksdb.RocksDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads RocksDB's native library once per JVM, one caller at a time. RocksDB's own loader lets a second thread wait only a short while
 * for a first thread that is unpacking the library (about 60 MB) into the temporary folder, then throws "Exceeded timeout whilst trying
 * to load the RocksDB shared library"; on a busy machine the unpacking takes longer than that wait, and a server failed to start. Here
 * the callers queue on one lock, and a timed-out wait is asked again until the library is in, for at most five minutes.
 */
final class RocksLibrary {

    private static final Logger LOG = LoggerFactory.getLogger(RocksLibrary.class);
    private static final long LIMIT_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
    private static volatile boolean loaded;

    private RocksLibrary() {
    }

    static synchronized void load() {
        if (loaded) {
            return;
        }
        long start = System.nanoTime();
        for (int attempt = 1;; attempt++) {
            try {
                RocksDB.loadLibrary();
                loaded = true;
                return;
            } catch (RuntimeException e) {
                boolean waitedTooShort = String.valueOf(e.getMessage()).contains("Exceeded timeout");
                if (!waitedTooShort || System.nanoTime() - start > LIMIT_NANOS) {
                    throw e;
                }
                if (attempt == 1) {
                    LOG.info("RocksDB's native library is still being unpacked by another thread; waiting for it");
                }
            }
        }
    }
}
