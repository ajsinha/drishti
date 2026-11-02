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
package com.ash.drishti.lakeio.mem;

import com.ash.drishti.lakeio.Storage;
import com.ash.drishti.lakeio.StorageContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/** The same {@link Storage} contract local disk passes, on {@code mem://} with small chunks (so files span many of them). */
class MemoryStorageContractTest extends StorageContract {

    private MemoryStorage store;

    @BeforeEach
    void define() {
        store = MemoryStores.define("contract", 64L << 20, 8192, "TEST-1");
    }

    @AfterEach
    void drop() {
        MemoryStores.remove("contract");
    }

    @Override
    protected Storage storage() {
        return store;
    }

    @Override
    protected String root() {
        return "mem://contract/lake";
    }
}
