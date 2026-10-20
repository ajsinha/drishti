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
package com.ash.drishti.iceberg.catalog;

/**
 * Another writer committed first: the version a commit was made on is no longer the newest (a path-based table's next
 * metadata file already exists, an S3 conditional PUT was refused, a REST catalog answered 409). The writer re-reads
 * the table and, when its change still applies, commits again.
 */
public final class CommitConflict extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CommitConflict(String message) {
        super(message);
    }
}
