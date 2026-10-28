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

import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.io.Relocation;
import com.ash.drishti.lakeio.S3Settings;
import com.ash.drishti.lakeload.Inputs.InputFile;
import com.ash.drishti.lakeload.Inputs.Split;
import com.ash.drishti.lakeload.SourceReader.Chunk;
import com.ash.drishti.lakeload.TableTarget.WrittenFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * One load, every stage parallel and memory bounded (docs/architecture/PARALLEL_LOADER.md):
 *
 * <ol>
 *   <li><b>read</b>: {@code readers} threads take the input's splits ({@link Inputs}); each reads and, for a compressed file,
 *       decompresses its split into chunks of whole lines on a queue bounded by {@code queue-mb} ({@link SourceReader});</li>
 *   <li><b>parse</b>: {@code parsers} threads turn lines into row records, buffer them by business date and sort and spill them as
 *       runs when their share of {@code sort-buffer-mb} is full ({@link ParseWorker}, {@link Runs});</li>
 *   <li><b>write</b>: per business date, {@code writers} threads each merge one key range of the runs and write Parquet files
 *       ({@link DateWriter});</li>
 *   <li><b>commit</b>: ONE commit per business date, by this thread alone ({@link TableTarget#commit}): the date's new files in, its
 *       earlier files out. A load that fails or is stopped before a date's commit leaves that date as it was.</li>
 * </ol>
 *
 * <p>Rejects are counted by reason (lake build's reasons) and kept with where they were; more than {@code rejects.max-pct} of the
 * documents rejected stops the load before anything is written.
 */
final class Loader {

    private final LoadConfig cfg;
    private final RowPlan plan;
    private Path spillDir;
    final Map<String, Meter> meters = new LinkedHashMap<>();
    final LoadReport report;

    Loader(LoadConfig cfg) {
        this.cfg = cfg;
        this.plan = new RowPlan(cfg.source(), cfg.target().layout().sortBy());
        this.report = new LoadReport(cfg, plan);
    }

    /** Runs the load; the report says what happened (a failure is thrown, with the report's counts so far kept). */
    LoadReport run() throws Exception {
        LoadConfig.Parallel par = cfg.parallel();
        Path spillDir = spillDir();
        this.spillDir = spillDir;
        Files.createDirectories(spillDir);
        // the scratch folder goes however the load ends: success, failure, or the JVM stopped (Ctrl-C, SIGTERM, a container stopped)
        Thread cleanup = new Thread(() -> removeSpillDir(spillDir), "lakeload-spill-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanup);
        FileAccess io = new FileAccess(S3Settings.from(cfg.source().storage()), Relocation.none(), null);
        List<ParseWorker> workers = new ArrayList<>();
        try (Progress progress = new Progress(cfg.report().progress(), report); HeapWatch heap = new HeapWatch(report)) {
            List<InputFile> files = Inputs.resolve(cfg.source().files(), cfg.source().recursive(), io);
            List<Split> splits = Inputs.splits(files, par.splitBytes());
            report.inputs(files);
            checkSpillSpace(spillDir, files);
            int readers = Math.max(1, Math.min(par.readers(), splits.size()));
            Meter read = meter("read", readers);
            Meter decompress = meter("decompress", readers);
            Meter parse = meter("parse", par.parsers());
            Meter spill = meter("sortSpill", par.parsers());
            long t0 = System.nanoTime();
            progress.phase("reading");
            ParseWorker.LineCounts lines = new ParseWorker.LineCounts();
            read(io, splits, readers, spillDir, workers, read, decompress, parse, spill, lines);
            report.readSeconds = (System.nanoTime() - t0) / 1e9;
            report.collect(workers, splits, lines);
            progress.phase("read");
            if (report.documents > 0 && report.rejected * 100.0 / report.documents > cfg.rejects().maxPct()) {
                throw new LoadException(String.format(java.util.Locale.ROOT, "%,d of %,d documents rejected (%.2f%%), more than rejects.max-pct %s: "
                        + "nothing was written", report.rejected, report.documents, report.rejected * 100.0 / report.documents, cfg.rejects().maxPct()));
            }
            if (cfg.report().dryRun()) {
                report.status = "dry-run";
                return report;
            }
            if (report.accepted == 0) {
                throw new LoadException("no document could be loaded (every one was rejected: see rejects)");
            }
            write(workers, progress);
            report.status = report.rejected > 0 ? "partial" : "ok";
            return report;
        } finally {
            for (ParseWorker w : workers) {
                for (ParseWorker.DateRows d : w.dates.values()) {
                    for (Runs.Run r : d.runs) {
                        r.close();
                    }
                }
            }
            io.close();
            report.meters(meters);
            removeSpillDir(spillDir);
            try {
                Runtime.getRuntime().removeShutdownHook(cleanup);
            } catch (IllegalStateException shuttingDown) {
                // the hook runs anyway
            }
        }
    }

    /** Typical expansion of compressed JSON Lines (gzip about 6 to 1 for documents with long number lists; zstd a little more). */
    static final double GZIP_RATIO = 6;
    static final double ZSTD_RATIO = 8;

    /**
     * The spill estimate: the input's uncompressed size (compressed files by their typical ratio), less what the sort buffers hold,
     * times 1.1 for the run files' framing; a load that may need more than the spill folder's free space stops before reading, saying
     * how much it needs and has. Only when {@code memory.check-spill-space} (default true).
     */
    private void checkSpillSpace(Path spillDir, List<InputFile> files) throws IOException {
        double plain = 0;
        for (InputFile f : files) {
            plain += f.size() * switch (f.compression()) {
                case GZIP -> GZIP_RATIO;
                case ZSTD -> ZSTD_RATIO;
                case NONE -> 1;
            };
        }
        long need = (long) (Math.max(0, plain - cfg.memory().sortBytes()) * 1.1);
        report.spillEstimate = need;
        if (!cfg.memory().checkSpace() || need == 0) {
            return;
        }
        long free = Files.getFileStore(spillDir).getUsableSpace();
        if (free < need) {
            throw new LoadException(String.format(java.util.Locale.ROOT, "the spill folder %s has %,d MB free; this load may spill up to about %,d MB "
                    + "(the input uncompressed, less memory.sort-buffer-mb): give memory.spill-dir a larger disk, raise memory.budget-mb, or set "
                    + "memory.check-spill-space: false to try anyway", spillDir.getParent(), free / 1048576, need / 1048576));
        }
    }

    private Meter meter(String name, int threads) {
        Meter m = new Meter(name, threads);
        meters.put(name, m);
        return m;
    }

    /** The read and parse stages: readers and parsers at once, joined by the bounded queue. */
    private void read(FileAccess io, List<Split> splits, int readers, Path spillDir, List<ParseWorker> workers, Meter read, Meter decompress,
            Meter parse, Meter spill, ParseWorker.LineCounts lines) throws Exception {
        LoadConfig.Parallel par = cfg.parallel();
        LoadConfig.Memory mem = cfg.memory();
        // the queue's share: half for chunks waiting in the queue, a quarter for the chunks readers fill, a quarter for their inflate buffers
        long queueBytes = mem.queueBytes();
        int maxKb = (int) Math.max(64, Math.min(Integer.MAX_VALUE, queueBytes / 2 / 1024));
        Semaphore budget = new Semaphore(maxKb);
        LinkedBlockingQueue<Chunk> queue = new LinkedBlockingQueue<>();
        AtomicLong docs = new AtomicLong();
        AtomicLong rejected = new AtomicLong();
        report.live(docs, rejected, read);
        int chunk = (int) Math.min(SourceReader.CHUNK, Math.max(64 * 1024, queueBytes / 4 / (readers + par.parsers())));
        int block = (int) Math.min(8 << 20, Math.max(64 * 1024, queueBytes / 4 / readers));
        SourceReader reader = new SourceReader(io, queue, budget, maxKb, read, decompress, chunk, block);
        long perParser = Math.max(256 << 10, mem.sortBytes() / par.parsers());
        ExecutorService parsePool = Executors.newFixedThreadPool(par.parsers(), named("lakeload-parse"));
        ExecutorService readPool = Executors.newFixedThreadPool(readers, named("lakeload-read"));
        List<Future<Void>> parsing = new ArrayList<>();
        for (int i = 0; i < par.parsers(); i++) {
            ParseWorker w = new ParseWorker(i, plan, queue, budget, maxKb, perParser, spillDir, parse, spill, lines, docs, rejected, cfg.rejects().keep());
            workers.add(w);
            parsing.add(parsePool.submit(w));
        }
        List<Future<Integer>> reading = new ArrayList<>();
        // the largest splits first: a big .gz read by one thread starts at once, the small ones fill in around it
        List<Split> order = new ArrayList<>(splits);
        order.sort(Comparator.comparingLong((Split s) -> s.end() - s.start()).reversed());
        for (Split s : order) {
            reading.add(readPool.submit(() -> reader.read(s)));
        }
        try {
            for (Future<Integer> f : reading) {
                waitFor(f, parsing);
            }
            for (int i = 0; i < par.parsers(); i++) {
                queue.put(Chunk.END);
            }
            for (Future<Void> f : parsing) {
                f.get();
            }
        } catch (Exception e) {
            readPool.shutdownNow();
            parsePool.shutdownNow();
            throw e;
        } finally {
            readPool.shutdown();
            parsePool.shutdown();
        }
        for (ParseWorker w : workers) {
            w.finish();
        }
    }

    /** Waits for a reader, failing at once when a parser failed (so a bad parser never leaves the readers blocked on a full queue). */
    private static void waitFor(Future<?> f, List<Future<Void>> parsing) throws Exception {
        while (true) {
            try {
                f.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                return;
            } catch (java.util.concurrent.TimeoutException e) {
                for (Future<Void> p : parsing) {
                    if (p.isDone()) {
                        p.get();                                       // a parser that ended early ended by failing: its failure
                    }
                }
            } catch (ExecutionException e) {
                throw e.getCause() instanceof Exception x ? x : e;
            }
        }
    }

    /** The write and commit stages, one business date after another. */
    private void write(List<ParseWorker> workers, Progress progress) throws Exception {
        TreeMap<Integer, List<Runs.Run>> byDate = new TreeMap<>();
        for (ParseWorker w : workers) {
            w.dates.forEach((day, d) -> byDate.computeIfAbsent(day, k -> new ArrayList<>()).addAll(d.runs));
        }
        boolean[] numeric = new boolean[plan.promoted.size()];
        boolean[] seen = new boolean[plan.promoted.size()];
        Arrays.fill(numeric, true);
        for (ParseWorker w : workers) {
            for (int i = 0; i < numeric.length; i++) {
                numeric[i] &= !w.notNumeric()[i];
                seen[i] |= w.seen()[i];
            }
        }
        List<String> promoted = plan.promoted.stream().map(RowPlan.Promoted::column).toList();
        Meter write = meter("write", cfg.parallel().writers());
        Meter commit = meter("commit", 1);
        try (TableTarget target = cfg.target().isIceberg() ? new IcebergTarget(cfg, plan) : new DeltaTarget(cfg, plan)) {
            target.open(new TableTarget.Columns(promoted, numeric, seen));
            report.columns(target.columnTypes(), target.leftOut());
            DateWriter dw = new DateWriter(target, plan, cfg.target().layout(), cfg.parallel().writers(), write);
            ExecutorService pool = Executors.newFixedThreadPool(cfg.parallel().writers(), named("lakeload-write"));
            long t0 = System.nanoTime();
            try {
                int done = 0;
                for (Map.Entry<Integer, List<Runs.Run>> e : byDate.entrySet()) {
                    LocalDate date = LocalDate.ofEpochDay(e.getKey());
                    progress.phase("writing " + date);
                    target.begin(date);
                    List<Runs.Run> runs = fanIn(date, e.getValue());
                    e.setValue(runs);
                    List<WrittenFile> files = dw.write(date, runs, pool, mergeBuffer(runs));
                    long c0 = System.nanoTime();
                    long version = target.commit(date, files);
                    commit.rows.add(files.stream().mapToLong(WrittenFile::rows).sum());
                    commit.busy(c0);
                    report.committed(date, version, files);
                    for (Runs.Run r : e.getValue()) {
                        r.close();
                    }
                    e.getValue().clear();
                    progress.done(++done, byDate.size());
                }
            } finally {
                pool.shutdownNow();
            }
            report.writeSeconds = (System.nanoTime() - t0) / 1e9;
        }
    }

    /** The smallest read buffer a writer gives each spilled run it merges. */
    static final int MIN_MERGE_BUFFER = 64 * 1024;

    /** Half the writers' share of the budget is for reading runs (the other half for the Parquet row groups they fill). */
    private long mergeShare() {
        return cfg.memory().writeBytes() / 2;
    }

    private static long fileRuns(List<Runs.Run> runs) {
        return runs.stream().filter(r -> r instanceof Runs.FileRun).count();
    }

    /**
     * A date that spilled more runs than the writers can read at once within their share of memory (each writer reads every run of its
     * range, through at least {@link #MIN_MERGE_BUFFER}) is first merged into fewer runs, pass after pass (an external merge sort).
     */
    private List<Runs.Run> fanIn(LocalDate date, List<Runs.Run> runs) throws IOException {
        int writers = cfg.parallel().writers();
        long n = fileRuns(runs);
        long most = Math.max(2, mergeShare() / ((long) writers * MIN_MERGE_BUFFER));
        if (n <= most) {
            return runs;
        }
        long t0 = System.nanoTime();
        int maxRuns = (int) Math.max(2, Math.min(256, mergeShare() / MIN_MERGE_BUFFER));
        List<Runs.Run> out = Runs.reduce(runs, (int) Math.min(maxRuns, most), spillDir, "merge-" + date, (int) (mergeShare() / maxRuns));
        Meter m = meters.computeIfAbsent("mergePasses", k -> new Meter(k, 1));
        m.rows.add(out.stream().mapToLong(r -> r.rows).sum());
        m.busy(t0);
        return out;
    }

    private int mergeBuffer(List<Runs.Run> runs) {
        long n = Math.max(1, fileRuns(runs));
        return (int) Math.max(8192, Math.min(1 << 20, mergeShare() / (cfg.parallel().writers() * n)));
    }

    private Path spillDir() {
        String d = cfg.memory().spillDir();
        Path base = d != null ? Path.of(d) : Path.of(System.getProperty("java.io.tmpdir"));
        return base.resolve("drishti-lakeload-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
    }

    private static void removeSpillDir(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // a scratch file another process holds (Windows): left for the temp folder's clean-up
                }
            });
        } catch (IOException e) {
            // as above
        }
    }

    private static java.util.concurrent.ThreadFactory named(String prefix) {
        AtomicLong n = new AtomicLong();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** A load that cannot go on for a reason of its data or settings (exit 1, nothing more committed). */
    static final class LoadException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        LoadException(String message) {
            super(message);
        }
    }

    /** The progress file ({@code report.progress}): phase, bytes read, documents, rejects, dates written; rewritten twice a second. */
    static final class Progress implements AutoCloseable {
        private final Path file;
        private final LoadReport report;
        private final java.util.concurrent.ScheduledExecutorService timer;
        private volatile String phase = "starting";
        private volatile int datesDone;
        private volatile int datesTotal;

        Progress(String file, LoadReport report) {
            this.file = file == null ? null : Path.of(file);
            this.report = report;
            if (this.file != null) {
                timer = Executors.newSingleThreadScheduledExecutor(named("lakeload-progress"));
                timer.scheduleAtFixedRate(this::write, 0, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } else {
                timer = null;
            }
        }

        void phase(String p) {
            phase = p;
            write();
        }

        void done(int done, int total) {
            datesDone = done;
            datesTotal = total;
            write();
        }

        private final ReentrantLock writing = new ReentrantLock();   // file I/O: a lock, not synchronized (virtual threads)

        private void write() {
            if (file == null) {
                return;
            }
            writing.lock();
            try {
                var n = LoadReport.JSON.createObjectNode().put("phase", phase).put("bytesRead", report.bytesRead()).put("bytesTotal", report.bytesTotal)
                        .put("documents", report.documentsSoFar()).put("rejected", report.rejectedSoFar()).put("datesDone", datesDone)
                        .put("datesTotal", datesTotal);
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.writeString(tmp, n.toString());
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // progress is a courtesy: a failed write is tried again in half a second
            } finally {
                writing.unlock();
            }
        }

        @Override
        public void close() {
            if (timer != null) {
                timer.shutdownNow();
                write();
            }
        }
    }

    /**
     * The heap a load really uses, for the summary: the used heap sampled every 20 ms (its largest value, garbage not yet collected
     * included), the largest heap left after a collection (what the load really held: the live rows, buffers and runs), and the sum of
     * the heap pools' own peaks (an upper bound: the pools peak at different moments).
     */
    static final class HeapWatch implements AutoCloseable {
        private final LoadReport report;
        private final java.util.concurrent.ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(named("lakeload-heap"));
        private final java.lang.management.MemoryMXBean bean = java.lang.management.ManagementFactory.getMemoryMXBean();
        private volatile long peak;

        HeapWatch(LoadReport report) {
            this.report = report;
            for (java.lang.management.MemoryPoolMXBean p : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
                if (p.getType() == java.lang.management.MemoryType.HEAP) {
                    p.resetPeakUsage();
                }
            }
            timer.scheduleAtFixedRate(this::sample, 0, 20, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        private volatile long live;

        private void sample() {
            long used = bean.getHeapMemoryUsage().getUsed();
            if (used > peak) {
                peak = used;
            }
            long afterGc = 0;
            for (java.lang.management.MemoryPoolMXBean p : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
                if (p.getType() == java.lang.management.MemoryType.HEAP && p.getCollectionUsage() != null) {
                    afterGc += p.getCollectionUsage().getUsed();
                }
            }
            if (afterGc > live) {
                live = afterGc;
            }
        }

        @Override
        public void close() {
            timer.shutdownNow();
            sample();
            long pools = 0;
            for (java.lang.management.MemoryPoolMXBean p : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
                if (p.getType() == java.lang.management.MemoryType.HEAP && p.getPeakUsage() != null) {
                    pools += p.getPeakUsage().getUsed();
                }
            }
            report.peakHeapBytes = peak;
            report.peakLiveBytes = live;
            report.heapPoolPeaksBytes = pools;
        }
    }
}
