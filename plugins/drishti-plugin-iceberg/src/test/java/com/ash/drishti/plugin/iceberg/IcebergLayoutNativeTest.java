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
package com.ash.drishti.plugin.iceberg;

/**
 * {@link IcebergLayoutTest} (the layout, the fast paths, delete files, time travel, retention, maintenance) on the
 * native engine throughout: the loader and maintenance write with drishti-iceberg and the connector reads with it. The
 * test's own checks of the layout read the tables with the Iceberg library, so they also prove the library reads what
 * drishti-iceberg writes.
 */
class IcebergLayoutNativeTest extends IcebergLayoutTest {

    @Override
    protected String engine() {
        return "native";
    }

    @Override
    protected String writeEngine() {
        return "native";
    }
}
