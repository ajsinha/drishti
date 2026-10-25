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
package com.ash.drishti.compute;

import com.ash.drishti.compute.approval.ApprovalItem;
import com.ash.drishti.compute.approval.ApprovalRequest;
import com.ash.drishti.compute.approval.Approvals;
import com.ash.drishti.compute.cluster.FanOut;
import com.ash.drishti.compute.cluster.PeerTransport;
import com.ash.drishti.compute.env.Environments;
import com.ash.drishti.compute.env.LockFile;
import com.ash.drishti.compute.env.Requires;
import com.ash.drishti.compute.worker.Scratch;
import com.ash.drishti.compute.worker.Sdk;
import com.ash.drishti.compute.worker.WorkerProcess;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The compute supervisor (docs/architecture/PYTHON_COMPUTE.md): this node's pools of out-of-process Python workers, the fan-out of one
 * request over many workers and nodes, the locked environments pools run in and the approval that gates them. Generic: nothing here knows
 * about VaR or pricing; a reactive connector (REACTIVE_CONNECTORS.md section 16) hands it requests with inputs already read and masked as
 * the viewer.
 *
 * <p>There is no limit on workers in the code: each pool's {@code workers.max} on this node is configuration, every node of a cluster has
 * its own count ({@code compute.nodes.<node>.pools.<pool>.workers}), and {@link #fanOut} spreads one request's chunks over all of them.
 */
public final class ComputeService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ComputeService.class);
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private final ComputeSettings settings;
    private final String node;
    private final Path configDir;
    private final Environments environments;
    private final Approvals approvals;
    private final PeerTransport peers;
    private final Map<String, Pool> pools = new LinkedHashMap<>();
    private final ArrayDeque<Map<String, Object>> recent = new ArrayDeque<>();
    private final Map<String, ComputeHandle> live = new ConcurrentHashMap<>();
    private volatile Path sdk;
    private volatile Thread ticker;
    private volatile boolean started;

    /**
     * @param settings the compute settings
     * @param node this node's name (its Drishti*net id, or {@code local})
     * @param configDir the folder pool lock files are relative to ({@code config/compute})
     * @param approvals the approval of requirements
     * @param peers the other nodes serving pools ({@link PeerTransport#NONE} off a cluster)
     */
    public ComputeService(ComputeSettings settings, String node, Path configDir, Approvals approvals, PeerTransport peers) {
        this.settings = settings;
        this.node = node == null || node.isBlank() ? "local" : node;
        this.configDir = configDir;
        this.environments = new Environments(settings);
        this.approvals = approvals;
        this.peers = peers == null ? PeerTransport.NONE : peers;
        if (approvals != null) {
            approvals.onDecision(this::decided);
        }
    }

    public ComputeSettings settings() {
        return settings;
    }

    public Environments environments() {
        return environments;
    }

    public Approvals approvals() {
        return approvals;
    }

    public String node() {
        return node;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    /**
     * Starts the supervisor: removes scratch folders a crash left, extracts the SDK, makes the pools, files approval requests for pools
     * whose environment is not approved, and starts each pool's minimum of workers. Off ({@code compute.enabled: false}): nothing starts.
     */
    public synchronized ComputeService start() {
        if (started || !settings.enabled()) {
            return this;
        }
        try {
            Files.createDirectories(settings.root());
            Files.createDirectories(settings.work().resolve("tmp"));
        } catch (IOException e) {
            throw new UncheckedIOException("could not create " + settings.root(), e);
        }
        int swept = Scratch.sweep(settings.scratch());
        if (swept > 0) {
            LOG.info("compute: removed {} scratch folder(s) left by an earlier run", swept);
        }
        sdk = Sdk.install(settings.sdk());
        for (PoolSettings p : settings.pools().values()) {
            pools.put(p.name(), new Pool(p, settings, node, n -> spec(p, n), this::finished));
            if (!p.systemEnvironment() && approvals != null) {
                try {
                    approvals.request("pool:" + p.name(), poolItems(p), "compute.yaml");
                } catch (RuntimeException e) {
                    LOG.warn("compute: pool {}: {}", p.name(), e.getMessage());
                }
            }
        }
        started = true;
        ticker = Thread.ofPlatform().daemon().name("compute-supervisor").start(this::tickLoop);
        LOG.info("compute: on, node {}, {} pool(s): {} (settings {}, data {})", node, pools.size(), describePools(), settings.origin(), settings.root());
        return this;
    }

    private String describePools() {
        List<String> out = new ArrayList<>();
        settings.pools().values().forEach(p -> out.add(p.name() + " " + p.workersMin() + ".." + p.workersMax() + " " + p.mode().resolved().name().toLowerCase(Locale.ROOT)));
        return String.join(", ", out);
    }

    private void tickLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(1000);
                for (Pool p : pools.values()) {
                    if (ready(p.settings()).isEmpty()) {
                        p.tick();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                LOG.warn("compute: the supervisor's tick failed once: {}", e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------ environments

    /** The approval items of a pool's locked environment. */
    public List<ApprovalItem> poolItems(PoolSettings p) {
        return new Requires(p.locked(), List.of(), List.of()).items(configDir);
    }

    /** Why the pool cannot run now (not approved, not built), or empty when it can. */
    public Optional<String> ready(PoolSettings p) {
        if (p.systemEnvironment()) {
            return Optional.empty();
        }
        try {
            if (approvals == null) {
                return Optional.of("no approval store: a locked environment cannot be approved");
            }
            Approvals.Status s = approvals.status("pool:" + p.name(), poolItems(p));
            if (!s.approved()) {
                return Optional.of(s.words());
            }
            String id = Environments.id(p.locked().version(), lock(p));
            if (!environments.built(id)) {
                return Optional.of("its environment " + id + " is approved but not built"
                        + environments.lastFailure(id).map(f -> " (the last build failed: " + f + ")").orElse(": Admin → Compute → Build"));
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.of(e.getMessage());
        }
    }

    private LockFile lock(PoolSettings p) {
        return new Requires(p.locked(), List.of(), List.of()).lock(configDir);
    }

    /** Builds a pool's approved environment (an administrator's action, or automatic after approval with {@code build: on-approval}). */
    public Environments.Built build(String pool, String by) {
        PoolSettings p = settings.pool(pool);
        if (p.systemEnvironment()) {
            throw new ComputeException(ComputeException.Code.REFUSED, "pool " + pool + " runs in the site's interpreter: there is nothing to build");
        }
        Approvals.Status s = approvals == null ? null : approvals.status("pool:" + pool, poolItems(p));
        if (s == null || !s.approved()) {
            throw new ComputeException(ComputeException.Code.REFUSED, "pool " + pool + ": " + (s == null ? "no approval store" : s.words())
                    + "; nothing is built before it is approved");
        }
        return environments.build(p.locked(), lock(p), by);
    }

    private void decided(ApprovalRequest r) {
        if (!ApprovalRequest.APPROVED.equals(r.state()) || !"on-approval".equals(settings.build()) || !r.subject().startsWith("pool:")) {
            return;
        }
        String pool = r.subject().substring(5);
        if (settings.pools().containsKey(pool)) {
            Thread.ofVirtual().name("compute-build-" + pool).start(() -> {
                try {
                    build(pool, "on-approval");
                } catch (RuntimeException e) {
                    LOG.warn("compute: building pool {}'s environment after its approval failed: {}", pool, e.getMessage());
                }
            });
        }
    }

    private WorkerProcess.Spec spec(PoolSettings p, int n) {
        String python;
        if (p.systemEnvironment()) {
            python = settings.interpreter();
        } else {
            Optional<String> why = ready(p);
            if (why.isPresent()) {
                throw new ComputeException(ComputeException.Code.REFUSED, "compute pool " + p.name() + ": " + why.get());
            }
            python = environments.verifiedPython(Environments.id(p.locked().version(), lock(p))).toString();
        }
        PoolSettings.Mode mode = p.mode().resolved();
        String id = node + "/" + p.name() + "#" + n;
        List<String> cmd = new ArrayList<>(List.of(python, "-s", "-B", "-m", "drishti_compute.worker", "--engines", String.join(",", p.engines()),
                "--mode", mode == PoolSettings.Mode.FORK ? "fork" : "warm", "--memory", Long.toString(p.memory()), "--cpu-seconds",
                Integer.toString(p.cpuSeconds()), "--worker", id));
        return new WorkerProcess.Spec(id, cmd, workerEnv(p), settings.work(), p.startTimeout(), mode == PoolSettings.Mode.WARM);
    }

    /** The worker's whole environment: nothing inherited but the system path; no tokens, no credentials; temp files under the data folder. */
    private Map<String, String> workerEnv(PoolSettings p) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String k : List.of("PATH", "SYSTEMROOT", "WINDIR", "LANG", "LC_ALL")) {
            String v = System.getenv(k);
            if (v != null) {
                env.put(k, v);
            }
        }
        List<String> path = new ArrayList<>();
        path.add(sdk.toString());
        p.pythonPath().forEach(x -> path.add(Path.of(x).toAbsolutePath().toString()));
        env.put("PYTHONPATH", String.join(java.io.File.pathSeparator, path));
        env.put("PYTHONHASHSEED", "0");
        env.put("PYTHONDONTWRITEBYTECODE", "1");
        env.put("PYTHONNOUSERSITE", "1");
        env.put("PYTHONIOENCODING", "utf-8");
        for (String k : List.of("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS", "NUMEXPR_NUM_THREADS", "POLARS_MAX_THREADS")) {
            env.put(k, "1");                                                // the pool, not the library, decides parallelism
        }
        String tmp = settings.work().resolve("tmp").toString();
        env.put("TMPDIR", tmp);
        env.put("TEMP", tmp);
        env.put("TMP", tmp);
        env.put("QLRISK_TMP_DIR", tmp);
        env.put("HOME", settings.work().toString());
        if (WINDOWS) {
            env.put("USERPROFILE", settings.work().toString());
        }
        return env;
    }

    // ------------------------------------------------------------------------------------------------------------ requests

    /** Submits a request to this node's pool. Refused at once (a failed handle) when compute is off, the pool unknown or not ready. */
    public ComputeHandle submit(ComputeRequest req, ProgressSink progress) {
        try {
            Pool p = pool(req.pool());
            ComputeHandle h = p.submit(req, progress);
            live.put(h.id(), h);
            h.result().whenComplete((r, e) -> live.remove(h.id()));
            return h;
        } catch (ComputeException e) {
            remember(req, e.code().label(), 0, 0, null);
            return ComputeHandle.failed(req.id(), e);
        }
    }

    /** Submits and waits (the pool's budget plus a margin). */
    public ComputeResult run(ComputeRequest req) {
        java.time.Duration budget = req.timeout() != null ? req.timeout() : settings.timeout(poolSettings(req.pool()), req.cls());
        return submit(req, ProgressSink.NONE).await(budget.plusSeconds(10));
    }

    /**
     * Splits the request's input {@code split} into chunks of {@code chunkRows} rows (0: {@code compute.fan-out.chunk-rows}) and runs them on
     * every worker of the pool on this node and, with {@code compute.fan-out.peers}, on the other nodes that serve it; gathers in order.
     */
    public ComputeHandle fanOut(ComputeRequest req, String split, int chunkRows, ProgressSink progress) {
        try {
            Pool p = pool(req.pool());
            FanOut f = FanOut.start(req, split, chunkRows > 0 ? chunkRows : settings.chunkRows(), this::submit, p.settings().workersMax(), peers,
                    settings.fanOutPeers(), progress);
            live.put(f.id(), f);
            f.result().whenComplete((r, e) -> live.remove(f.id()));
            return f;
        } catch (ComputeException e) {
            return ComputeHandle.failed(req.id(), e);
        }
    }

    /** Cancels a running or waiting request of this node by id; false when it is not known here. */
    public boolean cancel(String requestId) {
        ComputeHandle h = live.get(requestId);
        if (h == null) {
            return false;
        }
        h.cancel();
        return true;
    }

    /** The state of a request still known here (waiting or running), or empty. */
    public Optional<ComputeHandle> handle(String requestId) {
        return Optional.ofNullable(live.get(requestId));
    }

    private Pool pool(String name) {
        if (!settings.enabled()) {
            throw new ComputeException(ComputeException.Code.OFF, "compute is off on this server (compute.enabled)");
        }
        if (!started) {
            throw new ComputeException(ComputeException.Code.OFF, "compute is not started on this server");
        }
        Pool p = pools.get(name);
        if (p == null) {
            throw new ComputeException(ComputeException.Code.REFUSED, "there is no compute pool '" + name + "' (pools: " + pools.keySet() + ")");
        }
        Optional<String> why = ready(p.settings());
        if (why.isPresent()) {
            throw new ComputeException(ComputeException.Code.REFUSED, "compute pool " + name + ": " + why.get());
        }
        return p;
    }

    private PoolSettings poolSettings(String name) {
        return settings.pool(name);
    }

    /** This node's load for a pool, as other nodes see it when they fan out. */
    public PeerTransport.PeerLoad load(String pool) {
        Pool p = pools.get(pool);
        if (p == null || ready(p.settings()).isPresent()) {
            return new PeerTransport.PeerLoad(node, 0, 0, 0);
        }
        int[] l = p.load();
        return new PeerTransport.PeerLoad(node, l[0], l[1], l[2]);
    }

    private void finished(Pool.Finished f) {
        remember(f.req(), f.outcome(), f.queuedMs(), f.runMs(), f.worker());
    }

    private void remember(ComputeRequest req, String outcome, long queuedMs, long runMs, String worker) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", req.id());
        m.put("at", Instant.now().toString());
        m.put("user", req.viewer().user());
        m.put("pool", req.pool());
        m.put("kind", req.kind());
        m.put("class", req.cls().label());
        m.put("outcome", outcome);
        m.put("queuedMs", queuedMs);
        m.put("runMs", runMs);
        m.put("worker", worker);
        synchronized (recent) {
            recent.addFirst(m);
            while (recent.size() > 200) {
                recent.removeLast();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------ Admin

    /** Admin → Compute's data: the switch, pools and workers, queues, environments and approval requests. */
    public Map<String, Object> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", settings.enabled());
        m.put("node", node);
        m.put("origin", settings.origin());
        m.put("python", settings.interpreter());
        m.put("root", settings.root().toString());
        m.put("build", settings.build());
        m.put("sdk", sdk == null ? null : sdk.toString());
        m.put("scheduling", Map.of("maxInflightPerUser", settings.maxInflightPerUser(), "queue",
                Map.of("interactive", settings.queueInteractive(), "event", settings.queueEvent(), "batch", settings.queueBatch())));
        m.put("fanOut", Map.of("chunkRows", settings.chunkRows(), "peers", settings.fanOutPeers()));
        List<Map<String, Object>> ps = new ArrayList<>();
        for (PoolSettings p : settings.pools().values()) {
            Pool pool = pools.get(p.name());
            Map<String, Object> s = pool == null ? new LinkedHashMap<>(Map.of("name", p.name(), "workersMin", p.workersMin(), "workersMax",
                    p.workersMax(), "environment", p.environment(), "engines", p.engines())) : new LinkedHashMap<>(pool.stats());
            s.put("ready", ready(p).orElse("ready"));
            List<Map<String, Object>> others = new ArrayList<>();
            if (settings.enabled()) {
                try {
                    peers.peers(p.name()).forEach(l -> others.add(Map.of("node", l.node(), "workers", l.workers(), "busy", l.busy(), "queued", l.queued())));
                } catch (RuntimeException e) {
                    LOG.debug("compute: peers of {} unknown: {}", p.name(), e.getMessage());
                }
            }
            s.put("peers", others);
            ps.add(s);
        }
        m.put("pools", ps);
        synchronized (recent) {
            m.put("recent", new ArrayList<>(recent));
        }
        m.put("environments", environments.list());
        if (approvals != null) {
            m.put("approvals", approvals.recent(100));
            m.put("approvedItems", approvals.approvedCount());
        }
        return m;
    }

    @Override
    public synchronized void close() {
        Thread t = ticker;
        if (t != null) {
            t.interrupt();
        }
        pools.values().forEach(Pool::close);
        started = false;
    }
}
