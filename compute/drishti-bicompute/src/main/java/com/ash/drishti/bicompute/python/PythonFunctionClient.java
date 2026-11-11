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
package com.ash.drishti.bicompute.python;

import com.ash.drishti.kernel.api.pivot.Batch;
import java.util.List;
import java.util.Map;

/**
 * The client side of the Python function workers (ARCHITECTURE.md section 26.5): the module never runs Python itself; the host (the server's
 * {@code pivot-fn} pool) implements this with sandboxed worker processes on the node that scanned the rows, batches sent as Arrow IPC in
 * memory (a pipe), never a file. A state is the worker's bytes, opaque to the engine, bounded by the function's declared size and validated
 * before it is merged or cached.
 */
public interface PythonFunctionClient {

    /**
     * A function as the worker loads it.
     *
     * @param ref {@code fn:owner/name@version}
     * @param code the Python source
     * @param entry the class decorated with {@code @function}
     * @param inputs the declared input names, in order (the batch's column names)
     * @param params the function's parameters
     * @param sha256 the code's SHA-256 (the worker's cache key)
     * @param stateBytes the most bytes a state may hold
     * @param memoryOnly true when the rows are a memory-only store's: never written, never spilled
     */
    record Spec(String ref, String code, String entry, List<String> inputs, Map<String, Object> params, String sha256, long stateBytes,
            boolean memoryOnly) {}

    /** An empty state. */
    byte[] start(Spec f);

    /** The state with a batch of the cell's rows added (the declared input columns only, already ruled and masked). */
    byte[] add(Spec f, byte[] state, Batch batch);

    /** Two states as one; refused for a holistic function. */
    byte[] merge(Spec f, byte[] a, byte[] b);

    /** The figure (null: nothing to say), with the function's own note when it says why. */
    Double finish(Spec f, byte[] state);
}
