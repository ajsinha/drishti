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
package com.ash.drishti.iceberg.read;

import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.manifest.DataFile;
import java.io.IOException;

/**
 * Reads the deleted row positions of one data file from a deletion vector of another table format: a position-delete
 * {@link DataFile} whose {@link DataFile#format()} is this reader's names the vector in its path and the data file in
 * {@link DataFile#referencedDataFile()}. drishti-lakehouse serves Delta's deletion vectors this way, so a Delta table seen as Iceberg
 * keeps its rows deleted in place. Found with {@link java.util.ServiceLoader}
 * ({@code META-INF/services/com.ash.drishti.iceberg.read.DeletionVectors}).
 */
public interface DeletionVectors {

    /** The {@link DataFile#format()} this reader serves ({@code DELTA-DV}). */
    String format();

    /** The deleted positions of {@code vector.referencedDataFile()}, sorted ascending. */
    long[] positions(DataFile vector, FileAccess io) throws IOException;
}
