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
package com.ash.drishti.plugin.delta;

/** {@link DeltaOnS3Test} (a lake in S3) with Hadoop's engine (S3A). Skipped where Docker is not reachable. */
class DeltaOnS3HadoopTest extends DeltaOnS3Test {

    @Override
    protected String engine() {
        return "hadoop";
    }
}
