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
 * {@code mem://}: the memory stores (docs/architecture/IN_MEMORY_LAKE.md section 3). Each store holds files as fixed-size chunks of
 * off-heap memory under a hard cap ({@link com.ash.drishti.memlake.store.ChunkPool}) and meets the {@link com.ash.drishti.lakeio.Storage}
 * contract exactly ({@link com.ash.drishti.memlake.store.MemoryStorage}). The lake engines reach it only through drishti-lakeio's
 * {@link com.ash.drishti.lakeio.StorageProviders}, which finds {@link com.ash.drishti.memlake.store.MemoryStorageProvider} on the class
 * path (why this way round: the package documentation of {@link com.ash.drishti.memlake}).
 */
package com.ash.drishti.memlake.store;
