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

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * One pool of worker processes (docs/architecture/PYTHON_COMPUTE.md section 7.1): its engines, its environment and how many workers it may
 * run on <em>this node</em>. There is no upper bound in the code on {@code workersMax}: the node's CPU and memory are the limit, and a site
 * scales further by adding Drishti*net nodes that serve the same pool (each with its own count, {@code compute.nodes.<node>.pools}).
 *
 * @param name the pool's name ({@code [a-z][a-z0-9-]*})
 * @param type {@code python} (Java workers arrive with C6)
 * @param engines the engine entry points the pool's workers load ({@code module:Class}); a request names one, or gets the first
 * @param environment {@code system} (the site's interpreter and the SDK, no third-party packages) or the lock file whose approved,
 *        built environment the workers run in (shown in Admin)
 * @param workersMin workers kept warm
 * @param workersMax workers at most, on this node
 * @param memory per-worker memory limit in bytes (0: none)
 * @param cpuSeconds CPU seconds per request (0: none)
 * @param recycleAfter requests before a worker is replaced (bounds slow leaks)
 * @param batchShare the share of the pool's workers batch requests may hold at once
 * @param idleStop idle workers beyond {@code workersMin} stop after this long
 * @param mode {@code fork} (a fresh child per request, Linux), {@code warm} (reset between requests, the Windows way) or {@code auto}
 * @param pythonPath extra folders on the workers' module path (an administrator's setting; tests use it for their engines)
 * @param timeout the default wall-time budget of an interactive or event request
 * @param timeoutBatch the default budget of a batch request
 * @param startTimeout how long a new worker may take to say it is ready
 * @param locked the locked environment ({@code environment: {python, lock, index}}), or null for {@code system}
 */
public record PoolSettings(String name, String type, List<String> engines, String environment, int workersMin, int workersMax, long memory,
        int cpuSeconds, int recycleAfter, double batchShare, Duration idleStop, Mode mode, List<String> pythonPath, Duration timeout,
        Duration timeoutBatch, Duration startTimeout, com.ash.drishti.compute.env.Requires.Python locked) {

    /** How a worker isolates one request from the next. */
    public enum Mode {
        AUTO, FORK, WARM;

        /** {@code auto} is fork where the platform has it (Linux, macOS), warm on Windows. */
        public Mode resolved() {
            if (this != AUTO) {
                return this;
            }
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? WARM : FORK;
        }
    }

    public PoolSettings {
        if (name == null || !name.matches("[a-z][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("a pool name is lower case letters, digits and hyphens, not '" + name + "'");
        }
        type = type == null ? "python" : type;
        if (!"python".equals(type)) {
            throw new IllegalArgumentException("compute.pools." + name + ".type is '" + type + "': python (java workers arrive with step C6)");
        }
        engines = List.copyOf(engines == null ? List.of() : engines);
        if (engines.isEmpty()) {
            throw new IllegalArgumentException("compute.pools." + name + ".engines names at least one engine (module:Class)");
        }
        for (String e : engines) {
            if (!e.matches("[A-Za-z_][A-Za-z0-9_.]*:[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("compute.pools." + name + ".engines: '" + e + "' is not module:Class");
            }
        }
        environment = locked != null ? locked.lock() : "system";
        if (workersMax < 1 || workersMin < 0 || workersMin > workersMax) {
            throw new IllegalArgumentException("compute.pools." + name + ".workers: min " + workersMin + ", max " + workersMax
                    + " (0 <= min <= max, max >= 1)");
        }
        pythonPath = List.copyOf(pythonPath == null ? List.of() : pythonPath);
        mode = mode == null ? Mode.AUTO : mode;
        recycleAfter = recycleAfter <= 0 ? (mode.resolved() == Mode.WARM ? 50 : 200) : recycleAfter;
    }

    /** The same pool with another worker range (a node's own count). */
    public PoolSettings withWorkers(int min, int max) {
        return new PoolSettings(name, type, engines, environment, min, max, memory, cpuSeconds, recycleAfter, batchShare, idleStop, mode,
                pythonPath, timeout, timeoutBatch, startTimeout, locked);
    }

    /** The most batch requests that may run at once: the batch share of the workers, at least one. */
    public int batchSlots() {
        return Math.max(1, (int) Math.floor(workersMax * batchShare));
    }

    /** True when the pool runs in the site's interpreter rather than a locked environment. */
    public boolean systemEnvironment() {
        return locked == null;
    }

    static PoolSettings parse(String name, ConfigValues c, ConfigValues node) {
        ConfigValues w = c.section("workers");
        ConfigValues nw = node.section("workers");
        int min = nw.integer("min", w.integer("min", 0, 0, 1_000_000), 0, 1_000_000);
        int max = nw.integer("max", w.integer("max", 2, 1, 1_000_000), 1, 1_000_000);
        if (min > max) {
            min = max;
        }
        String mode = c.text("mode", "auto").toUpperCase(Locale.ROOT);
        Mode m;
        try {
            m = Mode.valueOf(mode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(c.where() + ".mode is auto, fork or warm, not '" + mode.toLowerCase(Locale.ROOT) + "'");
        }
        com.ash.drishti.compute.env.Requires.Python locked = null;
        Object env = c.raw("environment");
        if (env instanceof java.util.Map<?, ?>) {
            ConfigValues e = c.section("environment");
            if (e.raw("python") instanceof Number n) {
                throw new IllegalArgumentException(e.where() + ".python is " + n + " as a number: write it in quotes, such as \"3.10\"");
            }
            locked = com.ash.drishti.compute.env.Requires.parse(java.util.Map.of("python", java.util.Map.of("version", e.text("python", ""),
                    "lock", e.text("lock", ""), "index", e.text("index", "shipped"))), c.where()).python();
        } else if (env != null && !"system".equals(c.text("environment", "system"))) {
            throw new IllegalArgumentException(c.where() + ".environment is system or a mapping {python, lock, index}");
        }
        return new PoolSettings(name, c.text("type", "python"), c.list("engines"), "", min, max,
                c.bytes("memory", 0), c.integer("cpu-seconds", 0, 0, 86_400 * 7), c.integer("recycle-after", 0, 0, 1_000_000),
                c.decimal("batch-share", 0.25, 0.0, 1.0), c.duration("idle-stop", Duration.ofMinutes(5)), m, c.list("python-path"),
                c.duration("timeout", Duration.ofSeconds(30)), c.duration("timeout-batch", Duration.ofMinutes(10)),
                c.duration("start-timeout", Duration.ofSeconds(60)), locked);
    }
}
