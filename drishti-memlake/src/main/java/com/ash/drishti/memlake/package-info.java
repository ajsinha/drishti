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
/**
 * The in-memory lake (docs/architecture/IN_MEMORY_LAKE.md), plain Java with no Spring: the tables of the memory stores and their replenish
 * jobs ({@link com.ash.drishti.memlake.MemoryLake}, {@link com.ash.drishti.memlake.ReplenishSource}), the eviction ladder, automated
 * compaction ({@link com.ash.drishti.memlake.MemoryCompactor}), the expiry of old versions ({@link com.ash.drishti.memlake.MemoryUpkeep}),
 * the owner node's binding of the pack ({@link com.ash.drishti.memlake.MemoryBinding}), the never-on-disk guard
 * ({@link com.ash.drishti.memlake.Retention}) and the host checks. The storage itself, {@code mem://}, is
 * {@link com.ash.drishti.memlake.store}.
 *
 * <h2>The dependency direction</h2>
 *
 * <p>This module depends on {@code drishti-lakeio} and {@code drishti-deltalake} (it writes its tables with the lake writer and reads them
 * with Delta Kernel); nothing below it depends on it. The lake readers reach {@code mem://} the other way round: {@code drishti-lakeio}
 * defines {@link com.ash.drishti.lakeio.StorageProvider}, and this module registers its memory stores as the provider of the scheme
 * {@code mem} ({@code META-INF/services/com.ash.drishti.lakeio.StorageProvider}). The native Delta engine, the lake writer, the Iceberg
 * engine and the Delta and Iceberg connectors ask {@link com.ash.drishti.lakeio.StorageProviders} for a path's storage, and whether it is
 * memory-only, and never name a class of this module. So:
 *
 * <ul>
 *   <li>there is no cycle: the lake engines cannot depend on this module, which depends on them;</li>
 *   <li>the readers stay the same code for every storage: local disk, S3, memory, and any later scheme a module provides the same way;</li>
 *   <li>{@code mem://} works exactly where this module is on the class path (the server) and fails clearly elsewhere (a standalone tool):
 *       nothing can quietly read or write a memory table through a disk.</li>
 * </ul>
 *
 * <p>The server keeps only the Spring wiring ({@code com.ash.drishti.server.memlake}): binding {@code drishti.lake.memory.*} onto
 * {@link com.ash.drishti.memlake.MemoryLakeSettings}, the beans, the cron parser, the joins to the memory budget and busy protection, and the
 * REST controller of Admin, Lakes.
 */
package com.ash.drishti.memlake;
