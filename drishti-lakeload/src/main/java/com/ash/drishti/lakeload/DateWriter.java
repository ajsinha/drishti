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

import com.ash.drishti.lakeload.TableTarget.FileOut;
import com.ash.drishti.lakeload.TableTarget.WrittenFile;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The write stage of one business date: its sorted runs are cut into key ranges of about equal size, each range merged and written by
 * one writer thread into Parquet files of about {@code file-size-mb} (a new file also when the partition values change, and with
 * {@code shard-aligned} at a group boundary, the sort fields before the id, once a file holds a quarter of its size), uploaded as they
 * finish. Nothing is committed here: {@link Loader} commits the date's files once all of them are written.
 */
final class DateWriter {

    /** A test's way to stop a load after this many finished files (a crash before the commit); 0: never. */
    static volatile int failAfterFiles;
    private static final AtomicInteger FINISHED = new AtomicInteger();

    private final TableTarget target;
    private final RowPlan plan;
    private final LoadConfig.Layout layout;
    private final int writers;
    private final Meter meter;

    DateWriter(TableTarget target, RowPlan plan, LoadConfig.Layout layout, int writers, Meter meter) {
        this.target = target;
        this.plan = plan;
        this.layout = layout;
        this.writers = writers;
        this.meter = meter;
    }

    /**
     * Writes the date's rows (every run) on {@code pool}, leaving out the duplicate copies {@code dropped} names (their places in the
     * input, {@link Latest#order}, the last eight bytes of each record); returns its files in key order.
     */
    List<WrittenFile> write(LocalDate date, List<Runs.Run> runs, ExecutorService pool, int bufferBytes, Set<Long> dropped)
            throws IOException, InterruptedException {
        long bytes = runs.stream().mapToLong(r -> r.bytes).sum();
        int ranges = (int) Math.max(1, Math.min(writers * 2L, (bytes + layout.fileBytes() - 1) / layout.fileBytes()));
        List<byte[]> bounds = Runs.bounds(runs, ranges);
        List<Future<List<WrittenFile>>> tasks = new ArrayList<>();
        for (int r = 0; r + 1 < bounds.size(); r++) {
            int range = r;
            byte[] lo = bounds.get(r);
            byte[] hi = bounds.get(r + 1);
            tasks.add(pool.submit(() -> range(date, runs, lo, hi, range, bufferBytes, dropped)));
        }
        List<WrittenFile> out = new ArrayList<>();
        try {
            for (Future<List<WrittenFile>> f : tasks) {
                out.addAll(f.get());
            }
        } catch (ExecutionException e) {
            tasks.forEach(t -> t.cancel(true));
            Throwable c = e.getCause();
            if (c instanceof IOException io) {
                throw io;
            }
            if (c instanceof RuntimeException re) {
                throw re;
            }
            throw new IOException(c);
        }
        return out;
    }

    /** One range: merged in key order and written into files. */
    private List<WrittenFile> range(LocalDate date, List<Runs.Run> runs, byte[] lo, byte[] hi, int range, int bufferBytes, Set<Long> dropped)
            throws IOException {
        long t0 = System.nanoTime();
        RowView view = new RowView(plan);
        List<WrittenFile> files = new ArrayList<>();
        long[] rows = {0};
        Object[] state = new Object[3];                                 // the open file, its partition values, the last group key
        int[] seq = {0};
        Runs.merge(runs, lo, hi, (buf, off, len) -> {
            if (!dropped.isEmpty() && dropped.contains((long) Bytes.LONG.get(buf, off + len - 8))) {
                return;                                                 // a duplicate's copy that is not kept (TOOLS.md section 4.13)
            }
            view.at(buf, off);
            String[] values = new String[view.partitions()];
            for (int j = 0; j < values.length; j++) {
                values[j] = view.partition(j);
            }
            int g = RowPlan.prefixLength(buf, view.keyOff(), view.keyLen(), plan.groupComponents);
            byte[] group = Arrays.copyOfRange(buf, view.keyOff(), view.keyOff() + g);
            FileOut out = (FileOut) state[0];
            if (out != null) {
                boolean newPartition = !Arrays.equals((String[]) state[1], values);
                boolean newGroup = !Arrays.equals((byte[]) state[2], group);
                long size = (rows[0] & 63) == 0 || newGroup ? out.length() : 0;
                if (newPartition || size >= layout.fileBytes() || layout.shardAligned() && newGroup && out.length() >= layout.fileBytes() / 4) {
                    files.add(finish(out));
                    out = null;
                }
            }
            if (out == null) {
                String name = date + "-r" + String.format("%03d-%05d", range, seq[0]++) + "-" + UUID.randomUUID() + ".parquet";
                out = target.newFile(date, values, name);
                state[0] = out;
                state[1] = values;
            }
            state[2] = group;
            out.write(view);
            rows[0]++;
        }, bufferBytes);
        if (state[0] != null) {
            files.add(finish((FileOut) state[0]));
        }
        meter.rows.add(rows[0]);
        for (WrittenFile f : files) {
            meter.bytesOut.add(Math.max(0, f.bytes()));
        }
        meter.busy(t0);
        return files;
    }

    private static WrittenFile finish(FileOut out) throws IOException {
        WrittenFile f = out.finish();
        int n = FINISHED.incrementAndGet();
        if (failAfterFiles > 0 && n >= failAfterFiles) {
            throw new IOException("stopped after " + n + " files (a test's crash before the commit)");
        }
        return f;
    }

    /** Resets the finished-file count (tests). */
    static void resetCount() {
        FINISHED.set(0);
    }
}
