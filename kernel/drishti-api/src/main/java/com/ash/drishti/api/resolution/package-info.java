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
 * Configuration resolution (docs/architecture/KERNEL_AND_MODULES.md section 18): where every pack, connector, binding and inbox rule
 * comes from, in three layers (the site, the modules that are on, the kernel). The one resolver every reader asks; the server, the lake
 * loader and the command line share it, and the tools read its answer ({@code resolve --json}, {@code tools/resolved.py}); no
 * Python twin exists. It only reads files, never writes them.
 */
package com.ash.drishti.api.resolution;
