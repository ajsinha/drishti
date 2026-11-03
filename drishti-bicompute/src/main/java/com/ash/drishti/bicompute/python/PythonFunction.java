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

import com.ash.drishti.bicompute.spi.AggFunction;
import com.ash.drishti.bicompute.spi.Batch;
import com.ash.drishti.bicompute.spi.Kind;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.util.List;

/**
 * A Python custom function (PIVOT_ENGINE.md sections 8.2 and 8.5) as the engine sees it: the four steps run by the host's sandboxed worker
 * pool through {@link PythonFunctionClient}; the state is the worker's bytes. It has no SQL form: the tabular provider feeds it the cell's
 * rows in columnar batches ({@code functions.python.batch-rows}, 64K by default). A holistic Python function gets all of a cell's rows in one
 * call (its size bound applies); a mergeable or sketch one is merged in the engine's fixed order like any built-in.
 */
public final class PythonFunction implements AggFunction<Object> {

    private final PythonFunctionClient client;
    private final PythonFunctionClient.Spec spec;
    private final Kind kind;
    private final boolean numeric;

    public PythonFunction(PythonFunctionClient client, PythonFunctionClient.Spec spec, Kind kind, boolean numeric) {
        this.client = client;
        this.spec = spec;
        this.kind = kind;
        this.numeric = numeric;
    }

    public PythonFunctionClient.Spec spec() {
        return spec;
    }

    @Override
    public String name() {
        return spec.ref();
    }

    @Override
    public Kind kind() {
        return kind;
    }

    @Override
    public int inputs() {
        return spec.inputs().size();
    }

    @Override
    public boolean numeric() {
        return numeric;
    }

    @Override
    public Object start() {
        return checked(client.start(spec));
    }

    @Override
    public Object add(Object state, Batch batch) {
        if (batch.rows() == 0) {
            return state;
        }
        return checked(client.add(spec, (byte[]) state, batch));
    }

    @Override
    public Object merge(Object a, Object b) {
        if (kind == Kind.HOLISTIC) {
            throw new IllegalStateException(spec.ref() + " is holistic: it receives all the rows of a cell in one call, never merged");
        }
        return checked(client.merge(spec, (byte[]) a, (byte[]) b));
    }

    @Override
    public Double finish(Object state) {
        return client.finish(spec, (byte[]) state);
    }

    private byte[] checked(byte[] state) {
        long bound = spec.stateBytes();
        if (bound > 0 && state.length > bound) {
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_LIMIT, spec.ref() + ": a state of " + state.length + " bytes passed its declared bound of "
                    + bound + " bytes");
        }
        return state;
    }

    @Override
    public byte[] encode(Object state) {
        return (byte[]) state;
    }

    @Override
    public Object decode(byte[] bytes) {
        return bytes;
    }

    @Override
    public long bytes(Object state) {
        return 16 + ((byte[]) state).length;
    }

    @Override
    public List<String> sqlState(List<String> inputs) {
        return List.of();
    }

    @Override
    public Object fromSql(Object[] parts) {
        throw new UnsupportedOperationException(spec.ref() + " has no SQL form: its rows are fed in batches");
    }
}
