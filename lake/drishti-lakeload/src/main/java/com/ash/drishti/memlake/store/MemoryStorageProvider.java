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
package com.ash.drishti.memlake.store;

import com.ash.drishti.lakeio.Storage;
import com.ash.drishti.lakeio.StorageProvider;

/**
 * {@code mem://} for every lake engine: listed in {@code META-INF/services/com.ash.drishti.lakeio.StorageProvider}, so the native Delta
 * engine, the lake writer and the Iceberg engine find the memory stores through {@link com.ash.drishti.lakeio.StorageProviders} without
 * depending on this module. Memory-only: nothing read from it may reach a disk.
 */
public final class MemoryStorageProvider implements StorageProvider {

    @Override
    public String scheme() {
        return MemoryStorage.SCHEME;
    }

    @Override
    public Storage storage(String path) {
        return MemoryStores.forPath(path);
    }

    @Override
    public boolean memoryOnly() {
        return true;
    }
}
