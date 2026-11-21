/*
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Proprietary: see LICENSE.
 */
package com.ash.drishti.common;

import com.ash.lake.LakeRuntime;

/**
 * G4 REHEARSAL STUB: the one {@link LakeRuntime} of the process. The real adapter (DRISHTI_LAKE_DESIGN D4) builds it once in the
 * server's wiring with the host's {@code LakeCatalog} (shipped lakes, pack files, connector templates, named lake writers,
 * extensions, child-process settings), its {@code LakeOptions}, its {@code mem://} storage provider and its settings, and hands it
 * to every call; until then this holder only lets the callers compile.
 */
public final class DrishtiLake {
    private static volatile LakeRuntime runtime = LakeRuntime.fromProcess();

    private DrishtiLake() {
    }

    public static LakeRuntime runtime() {
        return runtime;
    }

    public static void use(LakeRuntime r) {
        runtime = r;
    }
}
