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

/**
 * A storage for one more URI scheme, beyond the local disk and S3 this module serves itself. The engines (the native Delta engine, the
 * Iceberg engine, the lake writer) reach such a scheme only through this interface and {@link StorageProviders}, never through the module
 * that implements it. The in-memory lake ({@code drishti-lakeload}, HOW_IT_FITS.md) provides {@code mem://} this way.
 *
 * <p>An implementation is listed in {@code META-INF/services/com.ash.drishti.lakeio.StorageProvider} of its module and found with
 * {@link java.util.ServiceLoader}: its scheme works wherever its module is on the class path, and nowhere else.
 */
public interface StorageProvider {

    /** The URI scheme served, in lower case ({@code mem}). */
    String scheme();

    /** The storage that answers {@code path} (a path of this scheme). */
    Storage storage(String path);

    /**
     * Whether data under this scheme is held in memory only, by contract: nothing read from it may reach a disk (the connectors keep no
     * disk cache of it, the lake writer keeps no temporary file). False by default.
     */
    default boolean memoryOnly() {
        return false;
    }
}
