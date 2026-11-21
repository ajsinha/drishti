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
package com.ash.drishti.lakehost;

import com.ash.lake.load.LakeLoad;

/**
 * The main class of the data inbox's loader child process (the catalog's {@code loaderMain}): {@code LakeLoad} with Drishti's runtime, so the
 * child sees the shipped lakes, the packs and the connector templates as the server does. The same arguments and exit codes as {@code LakeLoad}.
 */
public final class DrishtiLakeLoad {

    private DrishtiLakeLoad() {
    }

    public static void main(String[] args) {
        System.exit(LakeLoad.run(DrishtiLake.runtime(), args, System.out));
    }
}
