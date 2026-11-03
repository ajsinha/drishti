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
package com.ash.drishti.server.pivot.functions;

import com.ash.drishti.bicompute.python.PythonFunctionClient;
import com.ash.drishti.bicompute.spi.Batch;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.arrow.ArrowIpc;
import com.ash.drishti.compute.arrow.ArrowTable;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code pivot-fn} pool (PIVOT_ENGINE.md section 8.5): sandboxed Python worker processes on this node ({@code python -I}, an empty
 * environment, the compute SDK's {@code drishti_compute.pivot_fn}), each serving one call at a time. Batches go to a worker as Arrow IPC
 * stream bytes over its pipe, never through a file, so memory-only data is never written; a state comes back as bytes. A call over its time
 * limit stops its worker (a new one starts on demand) and is refused in words; a worker that dies is replaced.
 */
public final class ProcessPythonClient implements PythonFunctionClient, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessPythonClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> command;
    private final Path sdk;
    private final int max;
    private final long timeoutMs;
    private final BlockingQueue<Worker> idle;
    private final AtomicInteger started = new AtomicInteger();
    private final ExecutorService readers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "pivot-fn-reader");
        t.setDaemon(true);
        return t;
    });

    /** One worker process: its pipes and the functions it has loaded. */
    private static final class Worker {
        final Process process;
        final DataOutputStream in;
        final DataInputStream out;
        final Set<String> loaded = new HashSet<>();

        Worker(Process p) {
            this.process = p;
            this.in = new DataOutputStream(new java.io.BufferedOutputStream(p.getOutputStream(), 1 << 16));
            this.out = new DataInputStream(new java.io.BufferedInputStream(p.getInputStream(), 1 << 16));
        }
    }

    /** A response: its header and body. */
    private record Frame(Map<String, Object> header, byte[] body) {}

    public ProcessPythonClient(String interpreter, Path sdk, int workers, int timeoutSeconds, int memoryMb) {
        String py = interpreter == null || interpreter.isBlank()
                ? (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows") ? "python" : "python3") : interpreter;
        this.sdk = sdk;
        this.command = List.of(py, "-I", "-c", "import sys; sys.path.insert(0, " + pyString(sdk.toString()) + "); "
                + "from drishti_compute import pivot_fn; sys.exit(pivot_fn.main(['--memory', '" + ((long) memoryMb << 20) + "']))");
        this.max = Math.max(1, workers);
        this.timeoutMs = Math.max(1, timeoutSeconds) * 1000L;
        this.idle = new ArrayBlockingQueue<>(this.max);
    }

    private static String pyString(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private Worker borrow() {
        Worker w = idle.poll();
        if (w != null && w.process.isAlive()) {
            return w;
        }
        if (w != null) {
            started.decrementAndGet();
        }
        if (started.incrementAndGet() <= max) {
            try {
                return start();
            } catch (RuntimeException e) {
                started.decrementAndGet();
                throw e;
            }
        }
        started.decrementAndGet();
        try {
            Worker x = idle.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (x == null) {
                throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_LIMIT, "every Python function worker on this node is busy (pivot-fn: " + max
                        + " workers); try again shortly");
            }
            return x.process.isAlive() ? x : borrow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_LIMIT, "interrupted while waiting for a Python function worker");
        }
    }

    private Worker start() {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().clear();                                      // an empty environment, as compute's workers
        pb.directory(sdk.toFile());
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        try {
            Process p = pb.start();
            readers.submit(() -> {
                try (var err = new java.io.BufferedReader(new java.io.InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = err.readLine()) != null) {
                        LOG.info("pivot-fn {}: {}", p.pid(), line);
                    }
                } catch (IOException ignored) {
                    // the worker ended
                }
            });
            Worker w = new Worker(p);
            Frame ready = read(w);
            if (!Boolean.TRUE.equals(ready.header().get("ready"))) {
                p.destroyForcibly();
                throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_OFF, "the Python function worker did not start: " + ready.header());
            }
            return w;
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_OFF, "the Python function worker cannot start (" + command.get(0) + "): "
                    + e.getMessage() + "; set drishti.pivot.functions.python.interpreter to a Python 3.10+");
        }
    }

    private void give(Worker w) {
        if (!w.process.isAlive() || !idle.offer(w)) {
            w.process.destroyForcibly();
            started.decrementAndGet();
        }
    }

    private void send(Worker w, Map<String, Object> header, byte[] body) throws IOException {
        byte[] h = JSON.writeValueAsBytes(header);
        w.in.writeInt(h.length);
        w.in.write(h);
        w.in.writeInt(body.length);
        w.in.write(body);
        w.in.flush();
    }

    @SuppressWarnings("unchecked")
    private Frame read(Worker w) throws IOException {
        Future<Frame> f = readers.submit(() -> {
            int n = w.out.readInt();
            byte[] h = w.out.readNBytes(n);
            int m = w.out.readInt();
            byte[] b = w.out.readNBytes(m);
            return new Frame(JSON.readValue(h, Map.class), b);
        });
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            w.process.destroyForcibly();
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_LIMIT, "a Python function call passed its time limit of " + timeoutMs / 1000
                    + " s (drishti.pivot.functions.python.timeout-seconds); its worker was stopped");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            w.process.destroyForcibly();
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_LIMIT, "interrupted during a Python function call");
        } catch (java.util.concurrent.ExecutionException e) {
            w.process.destroyForcibly();
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_LIMIT, "the Python function worker stopped (its memory limit, or a crash): "
                    + e.getCause());
        }
    }

    private Frame call(Spec f, String op, byte[] body) {
        Worker w = borrow();
        try {
            if (!w.loaded.contains(f.sha256())) {
                Map<String, Object> load = new LinkedHashMap<>();
                load.put("op", "load");
                load.put("fn", f.sha256());
                load.put("code", f.code());
                load.put("entry", f.entry());
                load.put("params", f.params());
                send(w, load, new byte[0]);
                Frame r = read(w);
                check(f, r);
                w.loaded.add(f.sha256());
            }
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("op", op);
            h.put("fn", f.sha256());
            h.put("inputs", f.inputs());
            send(w, h, body);
            Frame r = read(w);
            check(f, r);
            return r;
        } catch (IOException e) {
            w.process.destroyForcibly();
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_LIMIT, f.ref() + ": the Python function worker stopped: " + e.getMessage());
        } finally {
            give(w);
        }
    }

    private static void check(Spec f, Frame r) {
        if (!Boolean.TRUE.equals(r.header().get("ok"))) {
            String code = String.valueOf(r.header().get("code"));
            String msg = f.ref() + ": " + r.header().get("message");
            throw new DrishtiException("limit".equals(code) || "sandbox".equals(code) ? ErrorCode.PIVOT_FUNCTION_LIMIT
                    : ErrorCode.PIVOT_FUNCTION_INVALID, msg);
        }
    }

    private static byte[] pair(byte[] a, byte[] b) {
        return java.nio.ByteBuffer.allocate(4 + a.length + b.length).putInt(a.length).put(a).put(b).array();
    }

    /** A batch as Arrow IPC stream bytes, its columns named by the function's inputs (NaN and null as Arrow nulls). */
    static byte[] arrow(List<String> names, Batch b) {
        List<ArrowTable.Column> cols = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            Object c = b.columns()[i];
            if (c instanceof double[] d) {
                double[] x = java.util.Arrays.copyOf(d, b.rows());
                BitSet nulls = new BitSet();
                for (int r = 0; r < x.length; r++) {
                    if (Double.isNaN(x[r])) {
                        nulls.set(r);
                        x[r] = 0;
                    }
                }
                cols.add(new ArrowTable.Column(names.get(i), ArrowTable.Type.FLOAT64, 1, x, nulls));
            } else {
                String[] s = java.util.Arrays.copyOf((String[]) c, b.rows());
                BitSet nulls = new BitSet();
                for (int r = 0; r < s.length; r++) {
                    if (s[r] == null) {
                        nulls.set(r);
                    }
                }
                cols.add(new ArrowTable.Column(names.get(i), ArrowTable.Type.UTF8, 1, s, nulls));
            }
        }
        return ArrowIpc.toBytes(new ArrowTable(cols));
    }

    @Override
    public byte[] start(Spec f) {
        return call(f, "start", new byte[0]).body();
    }

    @Override
    public byte[] add(Spec f, byte[] state, Batch batch) {
        return call(f, "add", pair(state, arrow(f.inputs(), batch))).body();
    }

    @Override
    public byte[] merge(Spec f, byte[] a, byte[] b) {
        return call(f, "merge", pair(a, b)).body();
    }

    @Override
    public Double finish(Spec f, byte[] state) {
        Object v = call(f, "finish", state).header().get("value");
        return v instanceof Number n ? n.doubleValue() : null;
    }

    /** Workers running now. */
    public int workers() {
        return started.get();
    }

    @Override
    public void close() {
        Worker w;
        while ((w = idle.poll()) != null) {
            try {
                send(w, Map.of("op", "shutdown"), new byte[0]);
            } catch (IOException ignored) {
                // already gone
            }
            w.process.destroy();
        }
        readers.shutdownNow();
    }
}
