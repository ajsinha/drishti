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
package com.ash.drishti.server.bi.transform;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Runs a Python Transform: some frames in (Arrow IPC streams, masked and row-ruled for the reader), one frame out (a Parquet file). The shipped implementation
 * is {@link ProcessTransformRunner}, which starts the console's sandboxed runner; a test supplies its own.
 */
@FunctionalInterface
public interface TransformRunner {

    /**
     * What to run.
     *
     * @param manifest the Python item's manifest as {@code PythonManifest.Manifest#toMap} has it (name, interface, entry, inputs, packages, timeout, memory, params, source)
     * @param frames the inputs in order, as Arrow IPC streams
     * @param params the dataset's {@code derived.params}, over the manifest's own defaults
     * @param output where the result file is to be written
     * @param maxRows most rows the result may have
     * @param maxBytes largest result file
     */
    record Request(Map<String, Object> manifest, List<byte[]> frames, Map<String, Object> params, Path output, int maxRows, long maxBytes) {}

    /** A column of the result with the dataset type it maps to ({@code string integer decimal boolean date timestamp}). */
    record Column(String name, String type) {}

    /** What came back: the file, the columns typed from the frame, the rows, and the time the run took. */
    record Result(Path file, List<Column> columns, long rows, long millis) {}

    /**
     * Runs a Python <em>measure</em> over rows the server built for one person (an alert on a Python measure has no browser to run it in): the answer has
     * {@code by} (the group columns) and {@code rows} (the group values, then the number). The default refuses: only {@link ProcessTransformRunner} runs Python.
     *
     * @param groupBy the group columns; empty: the whole input is one group
     */
    default com.fasterxml.jackson.databind.JsonNode measure(Map<String, Object> manifest, Map<String, Object> params, List<String> groupBy, List<String> columns,
            List<List<Object>> rows) {
        throw new com.ash.drishti.common.DrishtiException(com.ash.drishti.common.ErrorCode.PYTHON_BUSY, "this runner runs Transforms only");
    }

    /** Runs it; a failure is a {@code DrishtiException} with the Python code (DRS-9071 to 9074) or {@code DRS-9075}. */
    Result run(Request request);
}
