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
package com.ash.drishti.server.modules.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.drishti.compute.ComputeRequest;
import com.ash.drishti.compute.ComputeResult;
import com.ash.drishti.compute.ComputeService;
import com.ash.drishti.compute.ComputeSettings;
import com.ash.drishti.compute.approval.ApprovalStore;
import com.ash.drishti.compute.approval.Approvals;
import com.ash.drishti.compute.cluster.PeerTransport;
import com.ash.drishti.module.runtime.Contributions;
import com.ash.drishti.module.runtime.Dmod;
import com.ash.drishti.module.runtime.KernelFacts;
import com.ash.drishti.module.runtime.LoadedModule;
import com.ash.drishti.module.runtime.ModuleHealth;
import com.ash.drishti.module.runtime.ModuleHost;
import com.ash.drishti.module.runtime.ModuleRuntime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module that ships Python compute (KERNEL_AND_MODULES.md section 3.3): loaded by the module runtime, its pure-Python wheel is unpacked
 * and its pool joins the compute service; a request names the pool and runs the module's engine, which imports the wheel; unloaded, the
 * pool, its workers and the unpacked folder are gone and a request is refused; loaded again, it answers again.
 */
class ModulePythonTest {

    @TempDir
    Path tmp;

    private static String python() {
        for (String c : List.of("python3", "python")) {
            try {
                Process p = new ProcessBuilder(c, "-c", "import sys;print(sys.version_info[:2] >= (3, 10))").redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes()).strip();
                if (p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0 && out.equals("True")) {
                    return c;
                }
            } catch (IOException | InterruptedException e) {
                // the next
            }
        }
        return null;
    }

    private Path moduleFolder() throws IOException {
        Path src = tmp.resolve("src");
        Dmod.write(src.resolve("module.yaml"), """
                format: 1
                id: pyeng
                version: 1.0.0
                title: Python engine
                about: A module that ships Python compute
                domain: testing
                publisher: {name: Drishti tests, id: drishti-tests}
                licence: {feature: pyeng}
                kernel: ">=0.1 <1.0"
                rachana: ">=1.0"
                contracts: ">=1.0"
                plugin-api: ">=2.0"
                elements: ">=1.4 <2"
                api: {prefix: pyeng}
                python:
                  version: "3.10"
                  requires: ["tinylib==1.0"]
                  engines: [pyeng_calc.engine:SquareEngine]
                  pool: {workers: {min: 0, max: 2}, mode: auto, timeout: 20s}
                resources: {memory-mb: 32}
                """);
        Dmod.write(src.resolve("python/pyeng_calc/__init__.py"), "");
        Dmod.write(src.resolve("python/pyeng_calc/engine.py"), """
                from drishti_compute.engine import ComputeError, Engine, Result

                import tinylib


                class SquareEngine(Engine):
                    kinds = ("square",)
                    version = "1.0"

                    def compute(self, req, inputs, progress):
                        n = int(req.param("n", 0))
                        if n < 0:
                            raise ComputeError("n is negative")
                        return Result(rows=[{"SQUARE": n * n, "LIB": tinylib.VALUE}])
                """);
        Path wheel = src.resolve("python/wheels/tinylib-1.0-py3-none-any.whl");
        Files.createDirectories(wheel.getParent());
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(wheel))) {
            put(z, "tinylib/__init__.py", "VALUE = 42\n");
            put(z, "tinylib-1.0.dist-info/METADATA", "Metadata-Version: 2.1\nName: tinylib\nVersion: 1.0\n");
        }
        Path dmod = Dmod.pack(src, tmp.resolve(Dmod.fileName("pyeng", "1.0.0")));
        Path modules = tmp.resolve("modules");
        Dmod.unpack(dmod, modules.resolve("pyeng").resolve("1.0.0"));
        Files.copy(dmod, modules.resolve("pyeng").resolve(dmod.getFileName()));
        return modules;
    }

    private static void put(ZipOutputStream z, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private ComputeResult ask(ComputeService compute, int n) {
        return compute.run(ComputeRequest.on("m-pyeng", "square", "asha").engine("pyeng_calc.engine:SquareEngine").param("n", n).build());
    }

    @Test
    void aModuleShipsPythonInstalledOnLoadWithdrawnOnUnloadAndInstalledAgain() throws Exception {
        String python = python();
        assumeTrue(python != null, "no Python 3.10+ interpreter to run workers");
        Path modules = moduleFolder();
        Path data = tmp.resolve("data");
        ComputeSettings settings = ComputeSettings.parse(Map.of("compute", Map.of("enabled", true, "python", python)), data, "", UnaryOperator.identity(), "test");
        Path site = data.resolve("compute").resolve("modules").resolve("pyeng").resolve("site");
        try (ComputeService compute = new ComputeService(settings, "test", tmp, new Approvals(new ApprovalStore.Memory(), null, Clock.systemUTC(), 0),
                PeerTransport.NONE).start()) {
            ModulePython py = new ModulePython(() -> compute, data, m -> true);
            ModuleRuntime rt = new ModuleRuntime(KernelFacts.ofKernel(), null, ModuleRuntime.class.getClassLoader(), new ModuleHost() {
                @Override
                public void started(LoadedModule module, Contributions contributions) {
                    py.install(module, contributions);
                }
            }, tmp.resolve("tombstones"));
            for (int round = 1; round <= 2; round++) {
                List<ModuleHealth> rows = rt.start(List.of(modules), Map.of(), id -> true);
                assertThat(rows).extracting(ModuleHealth::state).containsExactly(ModuleHealth.State.LOADED);
                assertThat(compute.hasPool("m-pyeng")).isTrue();
                assertThat(Files.isRegularFile(site.resolve("tinylib/__init__.py"))).isTrue();
                assertThat(rt.contributions().of("pyeng")).extracting(Contributions.Entry::surface)
                        .contains(Contributions.Surface.PYTHON_ITEM, Contributions.Surface.PYTHON_PATH);

                ComputeResult r = ask(compute, 12);
                assertThat(r.rows()).hasSize(1);
                assertThat(((Number) r.rows().get(0).get("SQUARE")).longValue()).isEqualTo(144);
                assertThat(((Number) r.rows().get(0).get("LIB")).longValue()).isEqualTo(42);      // the module's own wheel, imported by its engine
                assertThat(r.worker()).startsWith("test/m-pyeng#");

                ModuleRuntime.UnloadReport u = rt.unload("pyeng", ModuleRuntime.Leave.DISABLE, true);
                assertThat(u.failures()).isEmpty();
                assertThat(rt.contributions().of("pyeng")).isEmpty();
                assertThat(compute.hasPool("m-pyeng")).isFalse();
                assertThat(compute.poolSettings("m-pyeng")).isEmpty();
                assertThat(site).doesNotExist();
                assertThat(site.getParent()).doesNotExist();
                assertThat(compute.submit(ComputeRequest.on("m-pyeng", "square", "asha").engine("pyeng_calc.engine:SquareEngine").build(), null)
                        .result().isCompletedExceptionally()).isTrue();      // refused: there is no such pool any more
                try (Stream<Path> left = Files.list(data.resolve("compute"))) {
                    assertThat(left.map(p -> p.getFileName().toString()).toList()).doesNotContain("modules");
                }
            }
            rt.close();
        }
    }
}
