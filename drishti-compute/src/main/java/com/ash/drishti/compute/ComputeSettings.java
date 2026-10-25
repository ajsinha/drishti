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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The compute settings ({@code config/compute/compute.yaml}, docs/architecture/PYTHON_COMPUTE.md section 13), read once at start. Off as
 * shipped. Everything the workers write goes under {@code <data dir>/compute/}: environments, wheels, the extracted SDK and every
 * request's scratch folder.
 *
 * @param enabled the master switch; off: no worker starts and requests are refused with "compute is off"
 * @param python the interpreter workers and environments use (empty: {@code python3}, {@code python} on Windows)
 * @param root {@code <data dir>/compute}
 * @param scratch where request folders are made ({@code <root>/scratch} by default)
 * @param build {@code manual} or {@code on-approval}: when an approved environment is built
 * @param approvalRoles roles whose administrators may approve requirements (empty: any administrator)
 * @param maxInflightPerUser requests of one user running at once on one pool
 * @param queueInteractive interactive requests that may wait per pool before "busy, try again"
 * @param queueEvent event requests that may wait per pool
 * @param queueBatch batch requests that may wait per pool
 * @param chunkRows the default rows per chunk when a request is fanned out
 * @param fanOutPeers whether fan-out spreads chunks over the other Drishti*net nodes serving the pool
 * @param maxResultBytes the largest result file a worker may hand back
 * @param indexes named Python package sources (name to URL), the bank's mirror; never public PyPI unless named
 * @param shipped the folder of shipped wheels and jars, flat ({@code <root>/shipped}): the source {@code shipped}
 * @param javaRepository a Maven repository (a folder or URL in Maven layout) for {@code java:} requirements
 * @param pools the pools, with this node's worker counts applied
 * @param origin where the settings came from
 */
public record ComputeSettings(boolean enabled, String python, Path root, Path scratch, String build, List<String> approvalRoles,
        int maxInflightPerUser, int queueInteractive, int queueEvent, int queueBatch, int chunkRows, boolean fanOutPeers, long maxResultBytes,
        Map<String, String> indexes, Path shipped, String javaRepository, Map<String, PoolSettings> pools, String origin) {

    public ComputeSettings {
        approvalRoles = List.copyOf(approvalRoles);
        indexes = Map.copyOf(indexes);
        pools = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(pools));
        if (!"manual".equals(build) && !"on-approval".equals(build)) {
            throw new IllegalArgumentException("compute.build is manual or on-approval, not '" + build + "'");
        }
    }

    /** The interpreter to start: the setting, else {@code python} on Windows and {@code python3} elsewhere. */
    public String interpreter() {
        if (python != null && !python.isBlank()) {
            return python;
        }
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? "python" : "python3";
    }

    public Path envs() {
        return root.resolve("envs");
    }

    public Path sdk() {
        return root.resolve("sdk");
    }

    public Path jars() {
        return root.resolve("jars");
    }

    /** A temporary folder for pip and venv during a build: under the data folder, never the machine's {@code /tmp}. */
    public Path work() {
        return root.resolve("work");
    }

    public PoolSettings pool(String name) {
        PoolSettings p = pools.get(name);
        if (p == null) {
            throw new ComputeException(ComputeException.Code.REFUSED, "there is no compute pool '" + name + "' (pools: " + pools.keySet() + ")");
        }
        return p;
    }

    /** The same settings with other pools (tests, a reload). */
    public ComputeSettings withPools(Map<String, PoolSettings> p) {
        return new ComputeSettings(enabled, python, root, scratch, build, approvalRoles, maxInflightPerUser, queueInteractive, queueEvent,
                queueBatch, chunkRows, fanOutPeers, maxResultBytes, indexes, shipped, javaRepository, p, origin);
    }

    public ComputeSettings withEnabled(boolean on) {
        return new ComputeSettings(on, python, root, scratch, build, approvalRoles, maxInflightPerUser, queueInteractive, queueEvent,
                queueBatch, chunkRows, fanOutPeers, maxResultBytes, indexes, shipped, javaRepository, pools, origin);
    }

    /** The defaults: off, no pools, everything under {@code <dataDir>/compute}. */
    public static ComputeSettings defaults(Path dataDir) {
        return parse(Map.of(), dataDir, "", UnaryOperator.identity(), "defaults");
    }

    /**
     * Reads the file (absent: the defaults). {@code node} is this node's Drishti*net id (empty off a cluster), which picks its own worker
     * counts from {@code compute.nodes.<node>.pools.<pool>.workers}.
     */
    public static ComputeSettings load(Path file, Path dataDir, String node, UnaryOperator<String> resolve) {
        if (file == null || !Files.isRegularFile(file)) {
            return parse(Map.of(), dataDir, node, resolve, "defaults (no " + file + ")");
        }
        try {
            Map<String, Object> doc = new ObjectMapper(new YAMLFactory()).readValue(file.toFile(), new TypeReference<Map<String, Object>>() {});
            return parse(doc == null ? Map.of() : doc, dataDir, node, resolve, file.toString());
        } catch (IOException e) {
            throw new IllegalArgumentException(file + " is not valid YAML: " + e.getMessage(), e);
        }
    }

    /** Parses a document whose top-level key is {@code compute}. */
    public static ComputeSettings parse(Map<String, Object> doc, Path dataDir, String node, UnaryOperator<String> resolve, String origin) {
        ConfigValues top = new ConfigValues(doc, "", resolve);
        ConfigValues c = top.section("compute");
        Path root = dataDir.resolve("compute").toAbsolutePath().normalize();
        String scratch = c.text("scratch", null);
        ConfigValues sched = c.section("scheduling");
        ConfigValues queue = sched.section("queue");
        ConfigValues fan = c.section("fan-out");
        ConfigValues limits = c.section("limits");
        Map<String, String> indexes = new LinkedHashMap<>();
        ConfigValues idx = c.section("indexes");
        for (String k : idx.keys()) {
            ConfigValues one = idx.section(k);
            String url = one.text("url", null);
            if (url == null) {
                throw new IllegalArgumentException("compute.indexes." + k + ".url is required");
            }
            indexes.put(k, url);
        }
        ConfigValues pools = c.section("pools");
        ConfigValues nodes = c.section("nodes");
        ConfigValues mine = node == null || node.isBlank() ? new ConfigValues(Map.of(), "compute.nodes", resolve) : nodes.section(node).section("pools");
        Map<String, PoolSettings> ps = new LinkedHashMap<>();
        for (String name : pools.keys()) {
            ps.put(name, PoolSettings.parse(name, pools.section(name), mine.section(name)));
        }
        String shipped = c.text("shipped", null);
        return new ComputeSettings(c.flag("enabled", false), c.text("python", ""), root,
                scratch == null ? root.resolve("scratch") : Path.of(scratch).toAbsolutePath().normalize(),
                c.text("build", "manual"), c.section("approval").list("roles"),
                sched.integer("max-inflight-per-user", 4, 1, 1_000_000),
                queue.integer("interactive", 256, 1, 10_000_000), queue.integer("event", 10_000, 1, 10_000_000),
                queue.integer("batch", 100_000, 1, 100_000_000),
                fan.integer("chunk-rows", 5_000, 1, 100_000_000), fan.flag("peers", true),
                limits.bytes("max-result-bytes", 256L << 20),
                indexes, shipped == null ? root.resolve("shipped") : Path.of(shipped).toAbsolutePath().normalize(),
                c.text("java-repository", ""), ps, origin);
    }

    /** A request's default budget on a pool. */
    public Duration timeout(PoolSettings p, RequestClass cls) {
        return cls == RequestClass.BATCH ? p.timeoutBatch() : p.timeout();
    }
}
