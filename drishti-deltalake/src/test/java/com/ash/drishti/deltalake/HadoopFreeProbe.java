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
package com.ash.drishti.deltalake;

import io.delta.kernel.Table;
import io.delta.kernel.utils.FileStatus;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs inside {@link NoHadoopFileSystemTest}'s isolated class loader: every kind of read the connector does, through
 * the native engine, returning plain strings (so nothing crosses the loaders but JDK types).
 */
public final class HadoopFreeProbe {

    private HadoopFreeProbe() {}

    /** Reads {@code table} (latest, version 0, a checkpointed copy if given, one row group) and describes what it read. */
    public static List<String> run(String table, String checkpointed) throws Exception {
        List<String> out = new ArrayList<>();
        try (NativeEngine engine = NativeEngine.create()) {
            out.add("latest " + Table.forPath(engine, table).getLatestSnapshot(engine).getVersion());
            out.add("rows " + TableReader.rows(engine, table, null).size());
            out.add("v0 " + TableReader.rows(engine, table, 0L).size());
            if (checkpointed != null) {
                out.add("checkpointed " + TableReader.rows(engine, checkpointed, null).size());
            }
            FileStatus file = TableReader.files(engine, table).get(0);
            out.add("one " + TableReader.idsWhere(engine, file, "T-001").size());
            out.add("tables " + engine.storage(table).directories(table + "/..").size());
        }
        return out;
    }

    /** The same first read through Kernel's Hadoop engine: the guard of the test must catch it. */
    public static String runWithHadoop(String table) {
        var engine = io.delta.kernel.defaults.engine.DefaultEngine.create(new org.apache.hadoop.conf.Configuration());
        return "latest " + Table.forPath(engine, table).getLatestSnapshot(engine).getVersion();
    }
}
