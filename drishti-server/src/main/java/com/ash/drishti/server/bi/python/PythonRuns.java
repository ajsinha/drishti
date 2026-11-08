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
package com.ash.drishti.server.bi.python;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.ComputeException;
import com.ash.drishti.compute.ComputeRequest;
import com.ash.drishti.compute.ComputeResult;
import com.ash.drishti.compute.ComputeService;
import com.ash.drishti.compute.PoolSettings;
import com.ash.drishti.compute.env.LockFile;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.report.BiReportProperties;
import com.ash.drishti.server.bi.transform.TransformProperties;
import com.ash.drishti.server.bi.transform.TransformRunner;
import com.ash.drishti.server.compute.Viewers;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one Python runtime for code people write (docs/guides/NOTEBOOK_GUIDE.md section 19): every Python item, Python-driven table and
 * model, python visual, Notebook cell, step-by-step run and profile runs here, on the server's compute pool {@code python}. The inputs are read
 * on the server as the person running it (their row rules and masks) before they reach this class; it hands them to the pool's
 * {@code drishti_compute.items} engine, checks what comes back and audits every run. The console runs no Python.
 *
 * <p>Code runs only when it can run safely: compute on, the pool {@code python} configured with {@code isolation: strict} (no network,
 * files held by the kernel; Linux only), and the pool's interpreter carrying exactly the pinned versions of {@code locks/python.lock}.
 * Otherwise every run is refused in words ({@link #unavailable()}), and the reason is shown where the run would have been.
 */
public final class PythonRuns implements TransformRunner {

    /** The compute pool code people write runs on. */
    public static final String POOL = "python";
    /** The pinned libraries, relative to the compute configuration folder. */
    public static final String LOCK = "locks/python.lock";

    /** What a run answered: the engine's answer (its {@code kind} and payload, {@code ms}) and, for a Transform, its Parquet table. */
    public record Answer(Map<String, Object> body, byte[] file) {}

    /** The inputs of one run: files by name (Arrow IPC streams) and how the engine is to read them ({@code frame}, {@code frames}, {@code extracts}...). */
    public record Inputs(Map<String, byte[]> files, Map<String, Object> how) {
        /** One frame. */
        public static Inputs one(byte[] arrow) {
            return new Inputs(Map.of("frame.arrow", arrow), Map.of("frame", "frame.arrow"));
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CHECKED_FOR = Duration.ofMinutes(10);
    private static final long MAX_INPUT_BYTES = 256L << 20;
    private static final long MAX_ANSWER_BYTES = 4L << 20;

    private final ComputeService compute;
    private final BiReportProperties report;
    private final TransformProperties transform;
    private final AuditLog audit;
    private final AtomicLong runs = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private volatile Instant checkedAt = Instant.EPOCH;
    private volatile Optional<String> checked = Optional.of("not checked yet");
    private volatile Map<String, Object> about = Map.of();

    public PythonRuns(ComputeService compute, BiReportProperties report, TransformProperties transform, AuditLog audit) {
        this.compute = compute;
        this.report = report;
        this.transform = transform;
        this.audit = audit;
    }

    // ---- may code run here? ---------------------------------------------------------------------------------------------------------

    /** The packages an item may list: the configured allow-list ({@code drishti.bi.report.python-packages}) that the lock pins. */
    public List<String> packages() {
        Set<String> pinned = pins().keySet();
        return report.pythonPackages().stream().filter(pinned::contains).toList();
    }

    /** The lock's pins, name to version (empty when it cannot be read). */
    public Map<String, String> pins() {
        Map<String, String> out = new TreeMap<>();
        Path p = compute.configDir() == null ? null : compute.configDir().resolve(LOCK);
        if (p == null || !Files.isRegularFile(p)) {
            return out;
        }
        try {
            LockFile.parse(Files.readString(p, StandardCharsets.UTF_8), LOCK).pins().forEach(x -> out.put(x.name(), x.version()));
        } catch (IOException | IllegalArgumentException e) {
            return Map.of();
        }
        return out;
    }

    /**
     * Why code people write cannot run on this server now, in words, or empty when it can. Checked from the settings first; then the pool is
     * asked once (every ten minutes) for its interpreter's versions and its sandbox, so a pin that drifted or a sandbox that cannot be had
     * refuses every run until fixed.
     */
    public Optional<String> unavailable() {
        Optional<String> s = settingsProblem();
        if (s.isPresent()) {
            return s;
        }
        if (Duration.between(checkedAt, Instant.now()).compareTo(CHECKED_FOR) < 0) {
            return checked;
        }
        synchronized (this) {
            if (Duration.between(checkedAt, Instant.now()).compareTo(CHECKED_FOR) < 0) {
                return checked;
            }
            checked = askPool();
            checkedAt = Instant.now();
            return checked;
        }
    }

    private Optional<String> settingsProblem() {
        if (!transform.enabled()) {
            return Optional.of("Python is switched off on this server (drishti.bi.transform.enabled)");
        }
        if (!compute.enabled()) {
            return Optional.of("Python runs on the server's compute pool, and compute is off (compute.enabled in config/compute/compute.yaml)");
        }
        PoolSettings pool = compute.settings().pools().get(POOL);
        if (pool == null) {
            return Optional.of("the compute pool '" + POOL + "' is not configured (config/compute/compute.yaml)");
        }
        if (!pool.strict()) {
            return Optional.of("the compute pool '" + POOL + "' is not isolation: strict, and code people write runs only in the strict sandbox");
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return Optional.of("Python runs only on a Linux server: Windows has no network namespace or Landlock to hold code people write, so it is switched off here");
        }
        if (pins().isEmpty()) {
            return Optional.of("the pinned libraries (config/compute/" + LOCK + ") cannot be read");
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private Optional<String> askPool() {
        Map<String, String> pins = pins();
        ComputeRequest req = ComputeRequest.on(POOL, "about", "system").param("packages", new ArrayList<>(pins.keySet())).timeout(Duration.ofSeconds(60)).build();
        ComputeResult r;
        try {
            r = compute.run(req);
        } catch (ComputeException e) {
            return Optional.of("the compute pool '" + POOL + "' cannot start: " + e.getMessage());
        }
        Map<String, Object> a = r.rows().isEmpty() ? Map.of() : r.rows().get(0);
        about = a;
        Map<String, Object> have = a.get("packages") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        List<String> wrong = new ArrayList<>();
        for (String name : packages()) {
            Object v = have.get(name);
            if (!pins.get(name).equals(v)) {
                wrong.add(name + " " + (v == null ? "is not installed" : v + " is installed") + ", " + pins.get(name) + " is pinned");
            }
        }
        for (Map.Entry<String, String> pin : pins.entrySet()) {
            Object v = have.get(pin.getKey());
            if (v != null && !pin.getValue().equals(v) && !packages().contains(pin.getKey())) {
                wrong.add(pin.getKey() + " " + v + " is installed, " + pin.getValue() + " is pinned");
            }
        }
        if (!wrong.isEmpty()) {
            return Optional.of("the pool's interpreter does not carry the pinned libraries (config/compute/" + LOCK + "): " + String.join("; ", wrong));
        }
        return Optional.empty();
    }

    /** What the Python runtime is, for Admin and the pages that run Python: whether it runs, why not, the pool, the pins, the counters. */
    public Map<String, Object> status() {
        Optional<String> why = unavailable();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runs", why.isEmpty());
        m.put("why", why.orElse(null));
        m.put("where", "server");
        m.put("pool", POOL);
        m.put("packages", packages());
        m.put("pins", pins());
        m.put("interpreter", about.get("python"));
        m.put("isolation", "network namespace with no interface + Landlock + audit hook");
        m.put("calls", runs.get());
        m.put("failures", failures.get());
        return m;
    }

    // ---- one run --------------------------------------------------------------------------------------------------------------------

    /**
     * Runs {@code manifest} (a Python item: interface, entry, source, packages, inputs, output, timeout, memory) on {@code inputs} for {@code who}.
     * {@code author}: a failure carries the author's own traceback. {@code subject} names what ran, for the audit.
     */
    @SuppressWarnings("unchecked")
    public Answer run(Principal who, String interfaceKind, Map<String, Object> manifest, Map<String, Object> params, Inputs inputs, boolean author, String subject) {
        Optional<String> why = unavailable();
        if (why.isPresent()) {
            throw new DrishtiException(ErrorCode.PYTHON_BUSY, why.get());
        }
        List<String> allowed = packages();
        Object listed = manifest.get("packages");
        if (listed instanceof List<?> l) {
            for (Object x : l) {
                if (!allowed.contains(String.valueOf(x))) {
                    throw new DrishtiException(ErrorCode.PYTHON_SANDBOX, "package '" + x + "' is not on the pinned allow-list (" + String.join(", ", allowed) + ")");
                }
            }
        }
        long in = inputs.files().values().stream().mapToLong(b -> b.length).sum();
        if (in > MAX_INPUT_BYTES) {
            throw new DrishtiException(ErrorCode.PYTHON_LIMIT, "the rows handed to Python are " + in + " bytes; the limit is " + MAX_INPUT_BYTES);
        }
        int seconds = Math.max(1, Math.min(intOf(manifest.get("timeout"), 10), report.pythonMaxSeconds()));
        int memory = Math.max(64, Math.min(intOf(manifest.get("memory"), 256), report.pythonMaxMemoryMb()));
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("timeout", seconds);
        limits.put("memory", memory);
        limits.put("max_output_bytes", "transform".equals(interfaceKind) ? transform.maxBytes() : MAX_ANSWER_BYTES);
        limits.put("max_rows", transform.maxRows());
        limits.put("max_groups", 5000);
        ComputeRequest.Builder b = ComputeRequest.on(POOL, "run", who.user()).viewer(viewer(who)).param("interface", interfaceKind)
                .param("manifest", manifest).param("params", params == null ? Map.of() : params).param("limits", limits)
                .params(inputs.how()).timeout(Duration.ofSeconds(seconds + 15L));
        inputs.files().forEach(b::file);
        long t0 = System.nanoTime();
        runs.incrementAndGet();
        String outcome = "failed";
        try {
            ComputeResult r = compute.run(b.build());
            Map<String, Object> a = r.rows().isEmpty() ? Map.of() : new LinkedHashMap<>(r.rows().get(0));
            if (!Boolean.TRUE.equals(a.get("ok"))) {
                outcome = String.valueOf(a.getOrDefault("kind", "failed"));
                throw new PythonFailure(code(outcome), String.valueOf(a.getOrDefault("error", "the Python code failed")),
                        author ? String.valueOf(a.getOrDefault("traceback", "")) : null);
            }
            a.remove("ok");
            outcome = "done";
            return new Answer(a, r.files().get("result.parquet"));
        } catch (ComputeException e) {
            outcome = e.code().label();
            throw translate(e, seconds, memory);
        } finally {
            if (!"done".equals(outcome)) {
                failures.incrementAndGet();
            }
            if (audit != null) {
                audit.record(who.user(), "python-run", subject == null || subject.isBlank() ? interfaceKind : subject,
                        interfaceKind + " on pool " + POOL + ": " + outcome + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms, " + in + " bytes in");
            }
        }
    }

    /** The static checks of a source that need Python's parser (it parses, defines its entry, imports only what it lists): nothing runs. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> checkSource(Principal who, Map<String, Object> manifest) {
        Optional<String> why = unavailable();
        if (why.isPresent()) {
            return List.of(Map.of("level", "warning", "code", "DRS-9074", "path", "source", "message", "the source was not checked: " + why.get()));
        }
        try {
            ComputeResult r = compute.run(ComputeRequest.on(POOL, "check", who.user()).viewer(viewer(who)).param("manifest", manifest)
                    .param("allowed", packages()).timeout(Duration.ofSeconds(30)).build());
            Object problems = r.rows().isEmpty() ? null : r.rows().get(0).get("problems");
            return problems instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        } catch (ComputeException e) {
            return List.of(Map.of("level", "warning", "code", "DRS-9074", "path", "source", "message", "the source was not checked: " + e.getMessage()));
        }
    }

    /** Who runs it, as the compute request carries it: the user, their roles and the signature of their rights (never a credential). */
    private static ComputeRequest.Viewer viewer(Principal who) {
        com.ash.drishti.api.compute.Viewer v = Viewers.of(who);
        return new ComputeRequest.Viewer(v.user(), v.roles(), v.viewSignature());
    }

    private static ErrorCode code(String kind) {
        return switch (kind) {
            case "sandbox" -> ErrorCode.PYTHON_SANDBOX;
            case "limit" -> ErrorCode.PYTHON_LIMIT;
            case "invalid" -> ErrorCode.PYTHON_INVALID;
            default -> ErrorCode.PYTHON_FAILED;
        };
    }

    private static DrishtiException translate(ComputeException e, int seconds, int memory) {
        return switch (e.code()) {
            case TIMEOUT -> new DrishtiException(ErrorCode.PYTHON_LIMIT, "the Python code ran longer than its " + seconds + " s and was stopped");
            case CRASHED, LIMIT -> new DrishtiException(ErrorCode.PYTHON_LIMIT, "the Python code passed its limits (" + seconds + " s of CPU, " + memory
                    + " MB of memory) and was stopped");
            case OFF, REFUSED, BUSY, UNAVAILABLE -> new DrishtiException(ErrorCode.PYTHON_BUSY, e.getMessage());
            default -> new DrishtiException(ErrorCode.PYTHON_FAILED, e.getMessage());
        };
    }

    private static int intOf(Object v, int dflt) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? dflt : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    // ---- the Transform runner of datasets and Python-driven tables, and Python measures ---------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public Result run(Request r) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < r.frames().size(); i++) {
            files.put("in_" + i + ".arrow", r.frames().get(i));
            names.add("in_" + i + ".arrow");
        }
        Map<String, Object> how = new LinkedHashMap<>();
        how.put("frames", names);
        if (r.manifest().get("inputs") instanceof List<?> declared && declared.size() == names.size()) {
            how.put("names", declared.stream().map(String::valueOf).toList());     // build(inputs, params) reads the frames by these names
        }
        if (!r.contract().isEmpty()) {
            how.put("contract", r.contract());                                   // text the contract declares a date or timestamp is read as one
        }
        Answer a = run(r.who(), "transform", r.manifest(), r.params(), new Inputs(files, how), false,
                "transform " + r.manifest().getOrDefault("name", ""));
        if (a.file() == null) {
            throw new DrishtiException(ErrorCode.PYTHON_FAILED, "the Transform returned no frame");
        }
        try {
            Files.write(r.output(), a.file());
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.PYTHON_BUSY, "the Transform's result could not be kept: " + e.getMessage());
        }
        List<Column> cols = new ArrayList<>();
        if (a.body().get("columns") instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> c = (Map<String, Object>) o;
                cols.add(new Column(String.valueOf(c.get("name")), String.valueOf(c.getOrDefault("type", "string"))));
            }
        }
        return new Result(r.output(), cols, ((Number) a.body().getOrDefault("rows", 0)).longValue(), ((Number) a.body().getOrDefault("ms", 0)).longValue());
    }

    @Override
    public JsonNode measure(Principal who, Map<String, Object> manifest, Map<String, Object> params, List<String> groupBy, List<String> columns, List<List<Object>> rows) {
        Map<String, Object> how = new LinkedHashMap<>();
        how.put("frame", "frame.arrow");
        how.put("group_by", groupBy);
        Answer a = run(who, "measure", manifest, params, new Inputs(Map.of("frame.arrow", arrow(columns, rows)), how), false,
                "measure " + manifest.getOrDefault("name", ""));
        Map<String, Object> out = new LinkedHashMap<>(a.body());
        out.put("ok", true);
        return JSON.valueToTree(out);
    }

    /** Rows of JSON values as an Arrow stream: a column of whole numbers is int64, of numbers float64, of true/false boolean, else text. */
    public static byte[] arrow(List<String> columns, List<List<Object>> rows) {
        List<ArrowIpcWriter.Column> cols = new ArrayList<>();
        Set<Integer> booleans = new java.util.HashSet<>();
        for (int c = 0; c < columns.size(); c++) {
            boolean ints = true;
            boolean nums = true;
            boolean bools = true;
            boolean any = false;
            for (List<Object> row : rows) {
                Object v = row.get(c);
                if (v == null) {
                    continue;
                }
                any = true;
                bools &= v instanceof Boolean;
                nums &= v instanceof Number;
                ints &= v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof java.math.BigInteger;
            }
            ArrowIpcWriter.Kind kind = !any ? ArrowIpcWriter.Kind.TEXT : bools ? ArrowIpcWriter.Kind.INT64 : ints ? ArrowIpcWriter.Kind.INT64
                    : nums ? ArrowIpcWriter.Kind.FLOAT64 : ArrowIpcWriter.Kind.TEXT;
            if (any && bools) {
                booleans.add(c);
            }
            Object[] values = new Object[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                Object v = rows.get(i).get(c);
                values[i] = v == null ? null : booleans.contains(c) ? v : kind == ArrowIpcWriter.Kind.TEXT ? String.valueOf(v)
                        : kind == ArrowIpcWriter.Kind.FLOAT64 ? (Object) ((Number) v).doubleValue() : (Object) ((Number) v).longValue();
            }
            cols.add(new ArrowIpcWriter.Column(columns.get(c), kind, values));
        }
        return ArrowIpcWriter.write(cols, rows.size(), Map.of(), Set.of(), booleans);
    }
}
