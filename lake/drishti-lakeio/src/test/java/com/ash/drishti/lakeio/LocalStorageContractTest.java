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
package com.ash.drishti.lakeio;

import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

/** The {@link Storage} contract on local disk: the reference the memory store is held to. */
class LocalStorageContractTest extends StorageContract {

    @TempDir
    Path dir;

    private final LocalStorage storage = new LocalStorage();

    @Override
    protected Storage storage() {
        return storage;
    }

    @Override
    protected String root() {
        return dir.toString();
    }
}
