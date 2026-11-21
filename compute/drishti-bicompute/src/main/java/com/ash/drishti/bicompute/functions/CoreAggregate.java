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
package com.ash.drishti.bicompute.functions;

import com.ash.drishti.kernel.api.pivot.AggFunction;
import com.ash.drishti.kernel.api.pivot.Batch;
import com.ash.drishti.kernel.api.pivot.Kind;
import com.ash.rachana.agg.Aggregate;
import java.util.List;

/**
 * One of Core's aggregates ({@code com.ash.rachana.agg}: {@code sum}, {@code avg}, the statistics, the holistic functions) as the pivot
 * engine's {@link AggFunction} (SPLIT_PLAN 8.3h, A9): the one aggregate kernel is Core's, Drishti keeps none of its own. A thin shell:
 * every call is Core's; the batch and the kind are mapped at the edge, and the state, its bytes ({@code encode}/{@code decode}) and its
 * SQL text are Core's, so a cell state written before the move reads the same.
 */
public final class CoreAggregate implements AggFunction<Object> {

    private final Aggregate<Object> core;

    private CoreAggregate(Aggregate<Object> core) {
        this.core = core;
    }

    /** Core's aggregate as an engine function. */
    public static AggFunction<Object> of(Aggregate<Object> core) {
        return new CoreAggregate(core);
    }

    /** The Core aggregate this shell runs. */
    public Aggregate<Object> core() {
        return core;
    }

    @Override
    public String name() {
        return core.name();
    }

    @Override
    public Kind kind() {
        return Kind.valueOf(core.kind().name());
    }

    @Override
    public int inputs() {
        return core.inputs();
    }

    @Override
    public boolean numeric() {
        return core.numeric();
    }

    @Override
    public Object start() {
        return core.start();
    }

    @Override
    public Object add(Object state, Batch batch) {
        return core.add(state, new com.ash.rachana.agg.Batch(batch.rows(), batch.columns()));
    }

    @Override
    public Object merge(Object a, Object b) {
        return core.merge(a, b);
    }

    @Override
    public Double finish(Object state) {
        return core.finish(state);
    }

    @Override
    public Object result(Object state) {
        return core.result(state);
    }

    @Override
    public String resultUnit() {
        return core.resultUnit();
    }

    @Override
    public String emptyNote() {
        return core.emptyNote();
    }

    @Override
    public byte[] encode(Object state) {
        return core.encode(state);
    }

    @Override
    public Object decode(byte[] bytes) {
        return core.decode(bytes);
    }

    @Override
    public long bytes(Object state) {
        return core.bytes(state);
    }

    @Override
    public boolean plainSum() {
        return core.plainSum();
    }

    @Override
    public List<String> sqlState(List<String> inputs) {
        return core.sqlState(inputs);
    }

    @Override
    public Object fromSql(Object[] parts) {
        return core.fromSql(parts);
    }

    @Override
    public boolean sqlStateMergeable() {
        return core.sqlStateMergeable();
    }

    @Override
    public String toString() {
        return core.toString();
    }
}
