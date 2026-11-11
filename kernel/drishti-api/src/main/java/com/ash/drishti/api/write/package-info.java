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
 * Write-back through a connector (docs/guides/APPROVALS_GUIDE.md): one command of a connector's {@code writes:} filled for one change
 * ({@link com.ash.drishti.api.write.WriteCommand}) and what the source said to it ({@link com.ash.drishti.api.write.WriteResult}).
 */
package com.ash.drishti.api.write;
