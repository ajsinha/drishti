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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.drishti.compute.approval.ApprovalStore;
import com.ash.drishti.compute.approval.Approvals;
import com.ash.drishti.compute.cluster.PeerTransport;
import com.ash.drishti.compute.env.Environments;
import com.ash.drishti.compute.env.JavaLibraries;
import com.ash.drishti.compute.env.LockFile;
import com.ash.drishti.compute.env.Requires;
import com.ash.drishti.compute.worker.ReadOnly;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Steps C2 and C3's acceptance (docs/architecture/PYTHON_COMPUTE.md section 16): a pool's environment builds from a local wheel folder
 * with no network; a tampered wheel fails the build and names it; a tampered file in a built environment stops the worker; a pool with
 * unapproved requirements stays off and says so; approval by its requester is refused. The wheel is made by the test (pure Python), so this
 * runs on Windows too, with the console's Python.
 */
class EnvironmentTest {

    @TempDir
    Path dir;
    String version;

    @BeforeEach
    void python() throws Exception {
        String py = TestCompute.python();
        assumeTrue(py != null, "no Python 3.10+ interpreter");
        Process p = new ProcessBuilder(py, "-c", "import ensurepip, venv, sys; print('%d.%d' % sys.version_info[:2])").redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).strip();
        assumeTrue(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0, "the interpreter has no venv/ensurepip: " + out);
        version = out;
    }

    /** A pure-Python wheel holding an engine that answers with its package's VALUE. */
    static byte[] wheel(int value) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("demo_pkg/__init__.py", ("VALUE = " + value + "\n").getBytes(StandardCharsets.UTF_8));
        files.put("demo_pkg/engine.py", """
                import demo_pkg
                from drishti_compute import Engine, Result

                class DemoEngine(Engine):
                    kinds = ("value",)

                    def compute(self, req, inputs, progress):
                        return Result(rows=[{"value": demo_pkg.VALUE, "file": demo_pkg.__file__}])
                """.getBytes(StandardCharsets.UTF_8));
        files.put("demo_pkg-1.0.0.dist-info/METADATA", "Metadata-Version: 2.1\nName: demo-pkg\nVersion: 1.0.0\n".getBytes(StandardCharsets.UTF_8));
        files.put("demo_pkg-1.0.0.dist-info/WHEEL", "Wheel-Version: 1.0\nGenerator: drishti-test\nRoot-Is-Purelib: true\nTag: py3-none-any\n"
                .getBytes(StandardCharsets.UTF_8));
        StringBuilder record = new StringBuilder();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(e.getValue());
            record.append(e.getKey()).append(",sha256=").append(Base64.getUrlEncoder().withoutPadding().encodeToString(h)).append(',')
                    .append(e.getValue().length).append('\n');
        }
        record.append("demo_pkg-1.0.0.dist-info/RECORD,,\n");
        files.put("demo_pkg-1.0.0.dist-info/RECORD", record.toString().getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private ComputeSettings settings(String extra) {
        return TestCompute.settings(dir, """
                compute:
                  enabled: true
                  pools:
                    risk:
                      engines: [demo_pkg.engine:DemoEngine]
                      workers: { min: 0, max: 2 }
                      environment: { python: "%s", lock: risk.lock, index: shipped }
                %s""".formatted(version, extra == null ? "" : extra));
    }

    private void ship(byte[] wheel, byte[] lockedAs) throws Exception {
        Path shipped = dir.resolve("compute").resolve("shipped");
        Files.createDirectories(shipped);
        Files.write(shipped.resolve("demo_pkg-1.0.0-py3-none-any.whl"), wheel);
        Files.writeString(dir.resolve("risk.lock"), "demo-pkg==1.0.0 --hash=sha256:" + LockFile.sha256(lockedAs) + "\n");
    }

    @Test
    void anApprovedEnvironmentBuildsOfflineAndATamperedFileStopsTheWorker() throws Exception {
        byte[] w = wheel(42);
        ship(w, w);
        Approvals approvals = new Approvals(new ApprovalStore.Memory(), null, Clock.systemUTC(), 0);
        ComputeSettings s = settings(null);
        try (ComputeService c = new ComputeService(s, "test", dir, approvals, PeerTransport.NONE).start()) {
            ComputeRequest req = ComputeRequest.on("risk", "value", "asha").build();
            assertThatThrownBy(() -> c.run(req)).isInstanceOfSatisfying(ComputeException.class, e -> {
                assertThat(e.code()).isEqualTo(ComputeException.Code.REFUSED);
                assertThat(e.getMessage()).contains("waiting for approval");
            });
            assertThatThrownBy(() -> c.build("risk", "admin1")).hasMessageContaining("nothing is built before it is approved");
            String id = approvals.recent(5).get(0).id();
            assertThatThrownBy(() -> approvals.approve(id, "compute.yaml", "mine")).hasMessageContaining("four eyes");
            approvals.approve(id, "admin1", "reviewed: pure Python, our own");
            assertThat(c.ready(s.pool("risk"))).hasValueSatisfying(w2 -> assertThat(w2).contains("not built"));
            Environments.Built b = c.build("risk", "admin1");
            assertThat(b.dir()).isEqualTo(s.envs().resolve(b.id()));
            assertThat(Files.isWritable(b.dir().resolve("drishti-environment.json"))).isFalse();
            ComputeResult r = c.run(req);
            assertThat(r.rows().get(0)).containsEntry("value", 42);
            assertThat(String.valueOf(r.rows().get(0).get("file"))).startsWith(b.dir().toString());
            assertThat(c.build("risk", "admin1").reused()).isTrue();             // never changed in place
            Path init;
            try (Stream<Path> st = Files.walk(b.dir())) {
                init = st.filter(p -> p.endsWith(Path.of("demo_pkg", "__init__.py"))).findFirst().orElseThrow();
            }
            ReadOnly.undo(b.dir());
            Files.writeString(init, "VALUE = 666\n");
            ReadOnly.apply(b.dir());
            try (ComputeService fresh = new ComputeService(s, "test", dir, approvals, PeerTransport.NONE).start()) {
                assertThatThrownBy(() -> fresh.run(req)).isInstanceOfSatisfying(ComputeException.class, e -> {
                    assertThat(e.code()).isEqualTo(ComputeException.Code.REFUSED);
                    assertThat(e.getMessage()).contains("changed after it was built").contains("demo_pkg/__init__.py");
                });
            }
        }
    }

    @Test
    void aTamperedWheelFailsTheBuildAndLeavesNothingBehind() throws Exception {
        byte[] good = wheel(42);
        ship(wheel(43), good);                                                  // the shipped wheel is not the one the lock pins
        Approvals approvals = new Approvals(new ApprovalStore.Memory(), null, Clock.systemUTC(), 0);
        ComputeSettings s = settings(null);
        try (ComputeService c = new ComputeService(s, "test", dir, approvals, PeerTransport.NONE).start()) {
            approvals.approve(approvals.recent(5).get(0).id(), "admin1", "ok");
            assertThatThrownBy(() -> c.build("risk", "admin1")).isInstanceOfSatisfying(ComputeException.class, e -> {
                assertThat(e.getMessage()).contains("pip refused the lock").containsIgnoringCase("demo");
                assertThat(e.getMessage()).containsIgnoringCase("hash");
            });
            String id = Environments.id(version, new Requires(s.pool("risk").locked(), List.of(), List.of()).lock(dir));
            assertThat(s.envs().resolve(id)).doesNotExist();
            assertThat(c.ready(s.pool("risk"))).hasValueSatisfying(w -> assertThat(w).contains("the last build failed"));
            assertThat(s.work()).isDirectory();
            try (Stream<Path> st = Files.list(s.work())) {
                assertThat(st.filter(p -> p.getFileName().toString().startsWith("build-")).toList()).isEmpty();
            }
        }
    }

    @Test
    void javaLibrariesAreCheckedByHashAndAnUndeclaredNativeLibraryIsRefused() throws Exception {
        ComputeSettings s = settings(null);
        Files.createDirectories(s.shipped());
        ByteArrayOutputStream jar = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(jar)) {
            z.putNextEntry(new ZipEntry("native/linux-x86_64/libQuantLibJNI.so"));
            z.write(new byte[] {1, 2, 3});
            z.closeEntry();
        }
        Files.write(s.shipped().resolve("quantlib-java-1.36.0-linux-x86_64.jar"), jar.toByteArray());
        String sha = LockFile.sha256(jar.toByteArray());
        JavaLibraries libs = new JavaLibraries(s);
        assertThatThrownBy(() -> libs.stage(new Requires.JavaLib("org.quantlib:quantlib-java:1.36.0", sha, "linux-x86_64", "shipped", false)))
                .hasMessageContaining("does not say native: true");
        assertThatThrownBy(() -> libs.stage(new Requires.JavaLib("org.quantlib:quantlib-java:1.36.0", "0".repeat(64), "linux-x86_64", "shipped", true)))
                .hasMessageContaining("sha256 is " + sha);
        JavaLibraries.Staged st = libs.stage(new Requires.JavaLib("org.quantlib:quantlib-java:1.36.0", sha, "linux-x86_64", "shipped", true));
        assertThat(st.jar()).startsWith(s.jars());
        assertThat(st.nativeFiles()).containsExactly("native/linux-x86_64/libQuantLibJNI.so");
    }
}
