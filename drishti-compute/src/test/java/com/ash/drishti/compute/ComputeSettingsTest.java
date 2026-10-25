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

import com.ash.drishti.compute.env.LockFile;
import com.ash.drishti.compute.env.Requires;
import com.ash.drishti.compute.worker.Sdk;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The settings file, lock files, {@code requires:} and the SDK's file list. */
class ComputeSettingsTest {

    @TempDir
    Path dir;

    private static Map<String, Object> doc(String yaml) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).readValue(yaml,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    @Test
    void offAsShippedWithEverythingUnderTheDataFolder() {
        ComputeSettings s = ComputeSettings.defaults(dir);
        assertThat(s.enabled()).isFalse();
        assertThat(s.pools()).isEmpty();
        assertThat(s.scratch()).isEqualTo(dir.resolve("compute/scratch").toAbsolutePath().normalize());
        assertThat(s.envs().startsWith(dir.toAbsolutePath().normalize())).isTrue();
    }

    @Test
    void theWorkerCountIsConfigurationPerPoolAndPerNode() throws Exception {
        String yaml = """
                compute:
                  enabled: true
                  pools:
                    risk:
                      engines: [m:E]
                      workers:
                        min: 2
                        max: "${RISK_WORKERS:8}"
                      memory: 4GB
                      timeout: 45s
                  nodes:
                    big-box:
                      pools:
                        risk: { workers: { max: 64 } }
                """;
        UnaryOperator<String> resolve = v -> v.replace("${RISK_WORKERS:8}", "12");
        ComputeSettings small = ComputeSettings.parse(doc(yaml), dir, "small-box", resolve, "t");
        ComputeSettings big = ComputeSettings.parse(doc(yaml), dir, "big-box", resolve, "t");
        assertThat(small.pool("risk").workersMax()).isEqualTo(12);
        assertThat(big.pool("risk").workersMax()).isEqualTo(64);
        assertThat(big.pool("risk").workersMin()).isEqualTo(2);
        assertThat(big.pool("risk").memory()).isEqualTo(4L << 30);
        assertThat(big.pool("risk").timeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(big.pool("risk").batchSlots()).isEqualTo(16);
    }

    @Test
    void badSettingsAreRefusedWithTheirKeyNamed() {
        assertThatThrownBy(() -> ComputeSettings.parse(doc("compute: { pools: { p: { engines: [m:E], workers: { max: 0 } } } }"), dir, "",
                UnaryOperator.identity(), "t")).hasMessageContaining("compute.pools.p.workers");
        assertThatThrownBy(() -> ComputeSettings.parse(doc("compute: { pools: { p: { engines: [nope] } } }"), dir, "", UnaryOperator.identity(), "t"))
                .hasMessageContaining("module:Class");
        assertThatThrownBy(() -> ComputeSettings.parse(doc("compute: { pools: { p: { engines: [m:E], timeout: soon } } }"), dir, "",
                UnaryOperator.identity(), "t")).hasMessageContaining("compute.pools.p.timeout");
    }

    @Test
    void aLockPinsEveryPackageByVersionAndHash() {
        String h1 = "a".repeat(64);
        String h2 = "b".repeat(64);
        LockFile l = LockFile.parse("""
                # a comment
                numpy==2.1.3 --hash=sha256:%s \\
                    --hash=sha256:%s
                Demo_Pkg==1.0.0 --hash=sha256:%s   # shipped
                """.formatted(h1, h2, h1), "x.lock");
        assertThat(l.pins()).extracting(LockFile.Pin::name).containsExactly("numpy", "demo-pkg");
        assertThat(l.pins().get(0).hashes()).containsExactly(h1, h2);
        assertThatThrownBy(() -> LockFile.parse("numpy>=2", "x.lock")).hasMessageContaining("name==version");
        assertThatThrownBy(() -> LockFile.parse("numpy==2.1", "x.lock")).hasMessageContaining("no --hash");
        assertThatThrownBy(() -> LockFile.parse("--index-url http://x\nnumpy==2.1 --hash=sha256:" + h1, "x.lock")).hasMessageContaining("option lines");
    }

    @Test
    void requiresNamesPythonJavaAndPluginsAndTheirApprovalItems() throws Exception {
        Files.writeString(dir.resolve("e.lock"), "demo-pkg==1.0.0 --hash=sha256:" + "c".repeat(64) + "\n");
        Map<String, Object> raw = doc("""
                requires:
                  python: { version: "3.12", lock: e.lock, index: shipped }
                  java:
                    - coords: org.quantlib:quantlib-java:1.36.0-drishti.1
                      sha256: %s
                      classifier: linux-x86_64
                      native: true
                  plugins: [ { name: drishti-plugin-delta } ]
                  requested-by: dev1
                """.formatted("d".repeat(64)));
        Requires r = Requires.parse(raw.get("requires"), "var-engine");
        assertThat(r.java().get(0).fileName()).isEqualTo("quantlib-java-1.36.0-drishti.1-linux-x86_64.jar");
        assertThat(Requires.requestedBy(raw.get("requires"))).isEqualTo("dev1");
        assertThat(r.items(dir)).extracting(i -> i.kind() + ":" + i.name())
                .containsExactly("python:demo-pkg", "java:org.quantlib:quantlib-java:linux-x86_64", "plugin:drishti-plugin-delta");
        assertThatThrownBy(() -> Requires.parse(Map.of("python", Map.of("version", "3", "lock", "x")), "c")).hasMessageContaining("major.minor");
        assertThatThrownBy(() -> Requires.parse(Map.of("pip", 1), "c")).hasMessageContaining("unknown key 'pip'");
    }

    @Test
    void theSdkFileListMatchesTheSources() throws Exception {
        Path src = Path.of("src", "main", "python");
        List<String> files;
        try (Stream<Path> s = Files.walk(src.resolve("drishti_compute"))) {
            files = s.filter(p -> p.toString().endsWith(".py")).map(p -> src.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
        assertThat(Sdk.FILES.stream().sorted().toList()).isEqualTo(files);
        Path installed = Sdk.install(dir.resolve("sdk"));
        assertThat(installed.resolve("drishti_compute/worker.py")).exists();
        assertThat(Sdk.install(dir.resolve("sdk"))).isEqualTo(installed);          // the same SDK is extracted once
    }

    @Test
    void theShippedFileIsOffWithTheSamplePool() {
        UnaryOperator<String> defaults = v -> v.replaceAll("\\$\\{[A-Z_]+:([^}]*)}", "$1");
        ComputeSettings s = ComputeSettings.load(Path.of("..", "config", "compute", "compute.yaml"), dir, "", defaults);
        assertThat(s.enabled()).isFalse();
        assertThat(s.pool("sample").workersMax()).isEqualTo(4);
        assertThat(s.pool("sample").engines()).containsExactly(TestCompute.VECTORS, "drishti_compute.samples.paths:PathSumEngine");
        assertThat(s.pool("sample").systemEnvironment()).isTrue();
        assertThat(s.queueBatch()).isEqualTo(100_000);
    }
}
