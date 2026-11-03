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
package com.ash.drishti.bicompute.custom;

import com.ash.drishti.bicompute.spi.AggFunction;
import com.ash.drishti.bicompute.spi.Batch;
import com.ash.drishti.bicompute.spi.Kind;
import java.util.List;

/**
 * A custom function whose approval was revoked (PIVOT_ENGINE.md section 8.7), as a view that uses it sees it: every cell of its column says
 * "function withdrawn" with who withdrew it and why, and no figure; the rest of the view answers as before. Nothing of the function's code
 * runs.
 */
final class Withdrawn implements AggFunction<Object> {

    private final String ref;
    private final int inputs;
    private final String note;

    Withdrawn(String ref, int inputs, String note) {
        this.ref = ref;
        this.inputs = inputs;
        this.note = note;
    }

    @Override
    public String name() {
        return ref;
    }

    @Override
    public Kind kind() {
        return Kind.MERGEABLE;
    }

    @Override
    public int inputs() {
        return inputs;
    }

    @Override
    public boolean numeric() {
        return false;
    }

    @Override
    public Object start() {
        return Boolean.FALSE;
    }

    @Override
    public Object add(Object state, Batch batch) {
        return state;
    }

    @Override
    public Object merge(Object a, Object b) {
        return a;
    }

    @Override
    public Double finish(Object state) {
        return null;
    }

    @Override
    public String emptyNote() {
        return note;
    }

    @Override
    public byte[] encode(Object state) {
        return new byte[0];
    }

    @Override
    public Object decode(byte[] bytes) {
        return Boolean.FALSE;
    }

    @Override
    public long bytes(Object state) {
        return 8;
    }

    @Override
    public List<String> sqlState(List<String> in) {
        return List.of("NULL");
    }

    @Override
    public Object fromSql(Object[] parts) {
        return Boolean.FALSE;
    }
}
