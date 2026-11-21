/*
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Proprietary: see LICENSE.
 */
package com.ash.drishti.rachana;

import com.ash.rachana.parse.BlockLibrary;

/**
 * G4 REHEARSAL STUB: the one block library (the UI kit, pack and site blocks) that Core's {@code SutraBlocks} held in statics before the
 * split. Core now has {@link BlockLibrary} per instance; the real adapter builds it in the profile holder (SPLIT_PLAN 8.3c/8.3d) and
 * hands it to the parser. Here it is a holder only so that the registry compiles.
 */
public final class DrishtiBlocks {
    public static final BlockLibrary LIBRARY = new BlockLibrary();

    private DrishtiBlocks() {
    }
}
