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
package com.ash.drishti.lakeload;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.atomic.LongAdder;

/**
 * One stage's work for the throughput report: bytes in and out, rows, and the busy time summed over the stage's threads. The stage's
 * rate is its amount over its wall time (first start to last finish); {@code perThread} is the amount over the busy time, what one thread
 * of the stage does a second, which says where more threads would help.
 */
final class Meter {

    final String name;
    final int threads;
    final LongAdder bytesIn = new LongAdder();
    final LongAdder bytesOut = new LongAdder();
    final LongAdder rows = new LongAdder();
    final LongAdder busyNanos = new LongAdder();
    private volatile long first = Long.MAX_VALUE;
    private volatile long last;

    Meter(String name, int threads) {
        this.name = name;
        this.threads = threads;
    }

    /** Marks work from {@code t0} (System.nanoTime) to now. */
    void busy(long t0) {
        long now = System.nanoTime();
        busyNanos.add(now - t0);
        synchronized (this) {
            if (t0 < first) {
                first = t0;
            }
            if (now > last) {
                last = now;
            }
        }
    }

    double wallSeconds() {
        return last <= first ? 0 : (last - first) / 1e9;
    }

    ObjectNode json(ObjectNode out) {
        double wall = wallSeconds();
        double busy = busyNanos.sum() / 1e9;
        long in = bytesIn.sum();
        long outBytes = bytesOut.sum();
        long r = rows.sum();
        out.put("threads", threads).put("wallSeconds", round(wall)).put("busySeconds", round(busy));
        if (in > 0) {
            out.put("mbIn", round(in / 1048576.0)).put("mbInPerSecond", round(wall > 0 ? in / 1048576.0 / wall : 0));
        }
        if (outBytes > 0) {
            out.put("mbOut", round(outBytes / 1048576.0)).put("mbOutPerSecond", round(wall > 0 ? outBytes / 1048576.0 / wall : 0));
        }
        if (r > 0) {
            out.put("rows", r).put("rowsPerSecond", Math.round(wall > 0 ? r / wall : 0));
            out.put("rowsPerSecondPerThread", Math.round(busy > 0 ? r / busy : 0));
        } else if (in > 0) {
            out.put("mbPerSecondPerThread", round(busy > 0 ? in / 1048576.0 / busy : 0));
        }
        return out;
    }

    static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
