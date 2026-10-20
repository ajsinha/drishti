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
package com.ash.drishti.iceberg.manifest;

/**
 * One entry of a manifest: a file and its status in the snapshot that wrote the manifest. The snapshot id and the
 * sequence numbers are as read with inheritance applied: an entry added by the manifest's own commit leaves them out
 * and takes the manifest's.
 *
 * @param status 0 existing, 1 added, 2 deleted
 * @param sequenceNumber the data sequence number (the commit that first added the file's rows)
 * @param fileSequenceNumber the sequence number of the commit that added the file itself
 */
public record ManifestEntry(int status, long snapshotId, long sequenceNumber, Long fileSequenceNumber, DataFile file) {

    public static final int EXISTING = 0;
    public static final int ADDED = 1;
    public static final int DELETED = 2;

    public boolean live() {
        return status != DELETED;
    }
}
