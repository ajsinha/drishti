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

import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.meta.TableMetadata;
import com.ash.drishti.iceberg.meta.TableUpdate;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Optional;

/**
 * A table read from one metadata file named directly ({@code metadata-location: …/v12.metadata.json}): someone else's
 * table, read-only. A metadata file never changes, so a refresh finds nothing new; point the setting at a newer file
 * to move on.
 */
public final class StaticTableOps implements TableOps {

    private final String file;
    private final FileAccess io;

    public StaticTableOps(String file, FileAccess io) {
        this.file = file;
        this.io = io;
    }

    @Override
    public Loaded load() throws IOException {
        try {
            return new Loaded(TableMetadata.read(io.readAll(file)), file, null);
        } catch (FileNotFoundException e) {
            throw new NoSuchTable("no metadata file " + file);
        }
    }

    @Override
    public Optional<Loaded> refresh(Loaded current) {
        return Optional.empty();
    }

    @Override
    public Loaded commit(Loaded base, TableUpdate update) {
        throw new UnsupportedOperationException("a table named by its metadata file (" + file + ") is read-only");
    }

    @Override
    public FileAccess io(Loaded current) {
        return io;
    }

    @Override
    public String describe() {
        return file;
    }
}
