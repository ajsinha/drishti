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
import java.nio.ByteBuffer;
import java.util.List;

/**
 * A holistic figure a provider finished itself (HOW_IT_FITS.md section A26.4: "a provider may also answer final figures for functions only it
 * can finish"): an exact median DuckDB computed for one grouping set from its own rows, or a cell's VaR from its own members' summed vector.
 * It is never merged with another cell's: a total is its own set's figure.
 *
 * @param value the figure, null when the cell has none
 */
public record Final(Double value) {

    static final byte TAG = 9;

    byte[] encode() {
        return ByteBuffer.allocate(10).put(TAG).put((byte) (value == null ? 0 : 1)).putDouble(value == null ? 0 : value).array();
    }

    static Final decode(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b);
        bb.get();
        boolean has = bb.get() == 1;
        double v = bb.getDouble();
        return new Final(has ? v : null);
    }

    /**
     * A provider's native measure as a function (VaR, ES): holistic, its state the figure the provider finished per cell.
     *
     * @param name the name a view writes
     * @param plainSum true for an additive native measure (a PV, a trade count): a total is the sum of its cells
     */
    public static AggFunction<Object> function(String name, boolean plainSum) {
        return new AggFunction<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Kind kind() {
                return Kind.HOLISTIC;
            }

            @Override
            public int inputs() {
                return 0;
            }

            @Override
            public Object start() {
                return new Final(null);
            }

            @Override
            public Object add(Object state, Batch batch) {
                throw new UnsupportedOperationException(name + " is computed by its provider from each cell's own members");
            }

            @Override
            public Object merge(Object a, Object b) {
                throw new IllegalStateException(name + " is holistic: each cell and total is computed from its own members, never merged");
            }

            @Override
            public Double finish(Object state) {
                return ((Final) state).value();
            }

            @Override
            public byte[] encode(Object state) {
                return ((Final) state).encode();
            }

            @Override
            public Object decode(byte[] bytes) {
                return Final.decode(bytes);
            }

            @Override
            public long bytes(Object state) {
                return 32;
            }

            @Override
            public boolean plainSum() {
                return plainSum;
            }

            @Override
            public List<String> sqlState(List<String> inputs) {
                return List.of();
            }

            @Override
            public Object fromSql(Object[] parts) {
                throw new UnsupportedOperationException(name);
            }
        };
    }
}
