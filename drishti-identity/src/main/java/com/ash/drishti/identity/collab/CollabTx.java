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
package com.ash.drishti.identity.collab;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One unit of work over several stores: a share, its recipients, the inbox rows and the access-log row commit together or not at
 * all. On the database it is one transaction (the stores join it); with files it is serialised by one lock.
 */
public interface CollabTx {

    <T> T run(Supplier<T> work);

    /** One database transaction. */
    static CollabTx of(TransactionTemplate tx) {
        return new CollabTx() {
            @Override
            public <T> T run(Supplier<T> work) {
                return tx.execute(s -> work.get());
            }
        };
    }

    /** Files: no transaction exists, so work is serialised and a failure leaves what was written (each file write is atomic). */
    static CollabTx serial() {
        ReentrantLock lock = new ReentrantLock();
        return new CollabTx() {
            @Override
            public <T> T run(Supplier<T> work) {
                lock.lock();
                try {
                    return work.get();
                } finally {
                    lock.unlock();
                }
            }
        };
    }
}
