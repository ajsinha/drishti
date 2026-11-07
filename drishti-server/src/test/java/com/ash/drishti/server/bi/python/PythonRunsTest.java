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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.compute.ComputeService;
import com.ash.drishti.compute.ComputeSettings;
import com.ash.drishti.compute.approval.ApprovalStore;
import com.ash.drishti.compute.approval.Approvals;
import com.ash.drishti.compute.cluster.PeerTransport;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.bi.report.BiReportProperties;
import com.ash.drishti.server.bi.transform.TransformProperties;
import com.ash.drishti.server.bi.transform.TransformRunner;
import com.ash.drishti.server.bi.transform.TransformService;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one Python runtime on the server (ARCHITECTURE.md section 24.19): runs refused in words until they can be safe (compute on, the pool
 * {@code python} strict, the interpreter carrying the pinned versions), then a measure, a Transform and a failure through a real strict worker,
 * each run audited. The worker needs Linux with Landlock and an interpreter with pandas and pyarrow ({@code -Ddrishti.compute.test.python},
 * {@code DRISHTI_PYTHON} or the console's virtual environment); without them those tests are skipped.
 */
class PythonRunsTest {

    @TempDir
    Path tmp;

    final List<String> audited = new ArrayList<>();
    final AuditLog audit = new AuditLog() {
        @Override
        public void record(String actor, String action, String target, String detail) {
            audited.add(actor + "|" + action + "|" + target + "|" + detail);
        }

        @Override
        public List<Event> recent(int limit, String subject) {
            return List.of();
        }
    };
    final Principal ann = new Principal("ann", List.of("analyst"));

    static String python() {
        return PythonPoolForTests.interpreter();
    }

    static String version(String python, String pkg) throws Exception {
        Process p = new ProcessBuilder(python, "-c", "from importlib import metadata as m\ntry:\n print(m.version('" + pkg + "'))\nexcept Exception:\n print('')").start();
        String out = new String(p.getInputStream().readAllBytes()).strip();
        p.waitFor(60, TimeUnit.SECONDS);
        return out;
    }

    ComputeService compute(String yaml, Map<String, String> pins) throws Exception {
        Path config = tmp.resolve("config");
        Files.createDirectories(config.resolve("locks"));
        StringBuilder lock = new StringBuilder("# test lock\n");
        pins.forEach((k, v) -> lock.append(k).append("==").append(v).append(" --hash=sha256:").append("0".repeat(64)).append('\n'));
        Files.writeString(config.resolve("locks/python.lock"), lock);
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).readValue(yaml, Map.class);
        ComputeSettings s = ComputeSettings.parse(doc, tmp.resolve("data"), "", UnaryOperator.identity(), "test");
        return new ComputeService(s, "test", config, new Approvals(new ApprovalStore.Memory(), null, Clock.systemUTC(), 0), PeerTransport.NONE).start();
    }

    static String pool(String python, String isolation) {
        return "compute:\n  enabled: true\n  python: \"" + python + "\"\n  pools:\n    python:\n      engines: [drishti_compute.items:ItemsEngine]\n"
                + "      workers: { min: 0, max: 2 }\n      mode: fork\n      memory: 8GB\n      cpu-seconds: 60\n      isolation: " + isolation + "\n      timeout: 60s\n";
    }

    PythonRuns runs(ComputeService c) {
        return new PythonRuns(c, BiReportProperties.defaults(), TransformProperties.defaults(), audit);
    }

    @Test
    void codeRunsOnlyWhereItCanRunSafelyAndSaysWhyNot() throws Exception {
        try (ComputeService off = compute("compute:\n  enabled: false\n", Map.of("pandas", "1"))) {
            assertThat(runs(off).unavailable()).hasValueSatisfying(w -> assertThat(w).contains("compute is off"));
            assertThatThrownBy(() -> runs(off).run(ann, "visual", Map.of("source", "x"), Map.of(), PythonRuns.Inputs.one(new byte[0]), false, "t"))
                    .isInstanceOf(DrishtiException.class).hasMessageContaining("DRS-9074");
        }
        try (ComputeService noPool = compute("compute:\n  enabled: true\n  pools: {}\n", Map.of("pandas", "1"))) {
            assertThat(runs(noPool).unavailable()).hasValueSatisfying(w -> assertThat(w).contains("'python' is not configured"));
        }
        try (ComputeService standard = compute(pool("python3", "standard"), Map.of("pandas", "1"))) {
            assertThat(runs(standard).unavailable()).hasValueSatisfying(w -> assertThat(w).contains("not isolation: strict"));
        }
    }

    @Test
    void theInterpreterMustCarryThePinnedVersions() throws Exception {
        String py = python();
        assumeTrue(py != null && com.ash.drishti.compute.PoolSettings.Mode.AUTO.resolved() == com.ash.drishti.compute.PoolSettings.Mode.FORK, "needs Linux and pandas");
        try (ComputeService c = compute(pool(py, "strict"), Map.of("pandas", "0.0.1", "numpy", version(py, "numpy")))) {
            String why = runs(c).unavailable().orElse("");
            assumeTrue(!why.contains("strict sandbox is not available"), "needs Landlock: " + why);
            assertThat(why).contains("pandas " + version(py, "pandas") + " is installed, 0.0.1 is pinned");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMeasureATransformAndAFailureRunOnTheStrictPoolAndAreAudited() throws Exception {
        String py = python();
        assumeTrue(py != null && com.ash.drishti.compute.PoolSettings.Mode.AUTO.resolved() == com.ash.drishti.compute.PoolSettings.Mode.FORK, "needs Linux and pandas");
        Map<String, String> pins = new LinkedHashMap<>();
        for (String p : List.of("pandas", "numpy", "pyarrow")) {
            pins.put(p, version(py, p));
        }
        try (ComputeService c = compute(pool(py, "strict"), pins)) {
            PythonRuns r = runs(c);
            assumeTrue(r.unavailable().isEmpty(), "the strict pool cannot run here: " + r.unavailable().orElse(""));
            assertThat(r.packages()).containsExactly("pandas", "numpy", "pyarrow");
            Map<String, Object> m = Map.of("name", "vol", "interface", "measure", "inputs", List.of("pnl"), "packages", List.of("pandas"),
                    "source", "def compute(t, params):\n    return float(t['pnl'].sum()) * params['k']\n");
            JsonNode a = r.measure(ann, m, Map.of("k", 2), List.of("desk"), List.of("desk", "pnl"), List.of(List.of("A", 1.0), List.of("A", 2.0), List.of("B", 5.0)));
            assertThat(a.path("rows").toString()).isEqualTo("[[\"A\",6.0],[\"B\",10.0]]");

            Path out = tmp.resolve("out.parquet");
            byte[] frame = PythonRuns.arrow(List.of("desk", "pnl"), List.of(List.of("A", 1.0), List.of("B", 5.0)));
            Map<String, Object> t = Map.of("name", "build", "interface", "transform", "entry", "build", "inputs", List.of("legs"), "packages", List.of("pandas"),
                    "source", "def build(inputs, params):\n    return inputs['legs'][inputs['legs']['pnl'] > 2]\n");
            TransformRunner.Result res = r.run(new TransformRunner.Request(t, List.of(frame), Map.of(), out, 1000, 1 << 20, ann, Map.of()));
            assertThat(res.rows()).isEqualTo(1);
            assertThat(res.columns()).containsExactly(new TransformRunner.Column("desk", "string"), new TransformRunner.Column("pnl", "decimal"));
            assertThat(Files.size(out)).isPositive();

            Map<String, Object> bad = Map.of("name", "bad", "interface", "visual", "packages", List.of(), "source", "def render(t, p):\n    return 1 / 0\n");
            assertThatThrownBy(() -> r.run(ann, "visual", bad, Map.of(), PythonRuns.Inputs.one(frame), true, "bad"))
                    .isInstanceOfSatisfying(PythonFailure.class, f -> assertThat(f.traceback()).contains("<python-item>").contains("ZeroDivisionError"))
                    .hasMessageContaining("DRS-9071");
            assertThatThrownBy(() -> r.run(ann, "visual", bad, Map.of(), PythonRuns.Inputs.one(frame), false, "bad"))
                    .isInstanceOfSatisfying(PythonFailure.class, f -> assertThat(f.traceback()).isNull());
            Map<String, Object> net = Map.of("name", "net", "interface", "visual", "packages", List.of("pyarrow"),
                    "source", "import pyarrow.fs as fs\ndef render(t, p):\n    return {'x': str(fs.LocalFileSystem().open_input_stream('/etc/passwd').read(4))}\n");
            assertThatThrownBy(() -> r.run(ann, "visual", net, Map.of(), PythonRuns.Inputs.one(frame), false, "net")).hasMessageContaining("DRS-9073");
            assertThatThrownBy(() -> r.run(ann, "visual", Map.of("source", "x", "packages", List.of("requests")), Map.of(), PythonRuns.Inputs.one(frame), false, "x"))
                    .hasMessageContaining("not on the pinned allow-list");
            assertThat(audited).anyMatch(l -> l.startsWith("ann|python-run|measure vol|measure on pool python: done"))
                    .anyMatch(l -> l.startsWith("ann|python-run|bad|visual on pool python: failed"));
        }
    }

    @Test
    void aTransformsColumnsAreCheckedAgainstTheContract() {
        assertThat(TransformService.fits("integer", "decimal")).isTrue();
        assertThat(TransformService.fits("string", "decimal")).isFalse();
        assertThat(TransformService.fits("date", "timestamp")).isFalse();
        assertThat(TransformService.fits("timestamp", "date")).isTrue();
        assertThat(TransformService.fits("decimal", "string")).isTrue();
    }

    @Test
    void rowsBecomeATypedArrowStream() {
        byte[] a = PythonRuns.arrow(List.of("n", "x", "b", "s"), java.util.Arrays.asList(java.util.Arrays.asList(1L, 1.5, true, "a"), java.util.Arrays.asList(null, 2, false, null)));
        assertThat(a.length).isGreaterThan(100);
    }
}
