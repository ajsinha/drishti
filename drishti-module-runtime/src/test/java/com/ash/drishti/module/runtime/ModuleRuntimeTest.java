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
package com.ash.drishti.module.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.kernel.api.modules.ApiAlias;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** The runtime end to end with a module built in the test: load, list, route, unload with the leak check; and every refusal. */
class ModuleRuntimeTest {

    @TempDir
    Path tmp;

    private Path modules() {
        return tmp.resolve("modules");
    }

    private ModuleRuntime runtime(KernelFacts facts, List<String> events) {
        ModuleHost host = new ModuleHost() {
            @Override
            public void event(String name, String module) {
                events.add(name + " " + module);
            }
        };
        return new ModuleRuntime(facts, null, ModuleRuntime.class.getClassLoader(), host, tmp.resolve("tombstones"));
    }

    private ModuleHealth only(ModuleRuntime rt, String id) {
        return rt.health(id).orElseThrow();
    }

    @Test
    void noModulesChangesNothing() throws Exception {
        ModuleRuntime rt = runtime(KernelFacts.ofKernel(), new ArrayList<>());
        assertThat(rt.start(List.of(tmp.resolve("absent")), Map.of(), id -> true)).isEmpty();
        Files.createDirectories(modules());
        assertThat(rt.start(List.of(modules()), Map.of(), id -> true)).isEmpty();
        assertThat(rt.routes().snapshot().routes()).isEmpty();
        rt.close();
    }

    @Test
    void loadRouteUnloadLeakFree() throws Exception {
        new TestModule("hello").install(tmp, modules());
        List<String> events = new ArrayList<>();
        try (AnnotationConfigApplicationContext kernel = new AnnotationConfigApplicationContext()) {
            kernel.refresh();
            ModuleRuntime rt = new ModuleRuntime(KernelFacts.ofKernel(), kernel, ModuleRuntime.class.getClassLoader(), new ModuleHost() {
                @Override
                public void event(String name, String module) {
                    events.add(name + " " + module);
                }
            }, tmp.resolve("tombstones"));
            List<ModuleHealth> rows = rt.start(List.of(modules()), Map.of(), id -> true);
            assertThat(rows).extracting(ModuleHealth::state).containsExactly(ModuleHealth.State.LOADED);
            assertThat(only(rt, "hello").notes()).anyMatch(n -> n.startsWith("unsigned"));
            assertThat(events).containsExactly("module.started hello");

            boolean[] withdrawn = {false};
            inspect(rt, kernel, withdrawn);
            ModuleRuntime.UnloadReport r = rt.unload("hello", ModuleRuntime.Leave.REMOVE, true);
            assertThat(r.failures()).isEmpty();
            assertThat(r.leakFree()).isTrue();
            assertThat(r.steps()).hasSize(9);
            assertThat(withdrawn[0]).isTrue();
            assertThat(rt.contributions().of("hello")).isEmpty();
            assertThat(rt.loaded("hello")).isEmpty();
            assertThat(rt.routes().lookup("/m/hello/greet/world").orElseThrow().status()).isEqualTo(410);
            assertThat(rt.routes().lookup("/api/v1/hello/x").orElseThrow().status()).isEqualTo(410);
            assertThat(only(rt, "hello").state()).isEqualTo(ModuleHealth.State.REMOVED);
            assertThat(Files.readString(tmp.resolve("tombstones/hello.yaml"))).contains("function HELLO", "/m/hello/greet/{name}");
            assertThat(events).containsExactly("module.started hello", "module.leaving hello", "module.removed hello");
        }
    }

    /** Looks at the loaded module in a frame of its own, so nothing of the module stays reachable from the test once it returns. */
    private static void inspect(ModuleRuntime rt, AnnotationConfigApplicationContext kernel, boolean[] withdrawn) throws Exception {
        // the class loader: own classes, the kernel API shared, kernel internals hidden
        LoadedModule m = rt.loaded("hello").orElseThrow();
        assertThat(m.context().getParent()).isSameAs(kernel);
        assertThat(m.context().getBean("greeting")).isEqualTo("hello from hello");
        assertThat(m.context().getBean(ApiAlias.class).to()).isEqualTo("/api/v1/hello");      // one ApiAlias class: the kernel's
        assertThat(((Supplier<?>) m.context().getBean("greeter")).get()).isEqualTo("greeter of hello");
        assertThat(m.loader().loadClass("test.hello.Greeter").getClassLoader()).isSameAs(m.loader());
        assertThatThrownBy(() -> m.loader().loadClass(ModuleRuntime.class.getName())).isInstanceOf(ClassNotFoundException.class);
        assertThat(m.loader().loadClass("java.util.List")).isSameAs(List.class);

        // the live route table
        RouteTable.Match page = rt.routes().lookup("/m/hello/greet/world?x=1").orElseThrow();
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.params()).containsEntry("name", "world");
        assertThat(page.route().function()).isEqualTo("HELLO");
        assertThat(rt.routes().lookup("/hello-short").orElseThrow().route().id()).isEqualTo("greet");
        assertThat(rt.routes().lookup("/m/hello/old").orElseThrow().status()).isEqualTo(308);
        assertThat(rt.routes().lookup("/api/v1/hello/var/desk").orElseThrow().params()).containsEntry("rest", "var/desk");
        assertThat(rt.routes().lookup("/m/other/x")).isEmpty();

        // a host contribution from the module's loader, withdrawn at step 4
        rt.contributions().add("hello", Contributions.Surface.SPI, "greeter", m.loader(), () -> withdrawn[0] = true);
        assertThat(rt.contributions().byLoader(m.loader())).hasSize(1);
    }

    @Test
    void loadAndUnloadManyTimesWithoutLeaking() {
        new TestModule("hello").install(tmp, modules());
        ModuleRuntime rt = runtime(KernelFacts.ofKernel(), new ArrayList<>());
        rt.start(List.of(modules()), Map.of(), id -> true);
        for (int i = 0; i < 5; i++) {
            ModuleRuntime.UnloadReport r = rt.unload("hello", ModuleRuntime.Leave.DISABLE, true);
            assertThat(r.leakFree()).as("round " + i).isTrue();
            assertThat(rt.routes().lookup("/m/hello/greet/a").orElseThrow().status()).isEqualTo(404);
            rt.start(List.of(modules()), Map.of(), id -> true);
            assertThat(rt.routes().lookup("/m/hello/greet/a").orElseThrow().status()).isEqualTo(200);
        }
        rt.close();
    }

    @Test
    void contentOnlyModuleAndDependencyOrder() {
        TestModule base = new TestModule("base");
        base.java = false;
        base.install(tmp, modules());
        TestModule app = new TestModule("app");
        app.requires = "base: \">=1.0 <2.0\"";
        app.functions = "[{word: APP}]";
        app.install(tmp, modules());
        List<String> events = new ArrayList<>();
        ModuleRuntime rt = runtime(KernelFacts.ofKernel(), events);
        rt.start(List.of(modules()), Map.of(), id -> true);
        assertThat(events).containsExactly("module.started base", "module.started app");
        assertThat(rt.loaded("base").orElseThrow().loader()).isNull();
        assertThatThrownBy(() -> rt.unload("base", ModuleRuntime.Leave.REMOVE, true)).hasMessageContaining("required by app 1.0.0");
        rt.close();
        assertThat(rt.health()).allMatch(h -> h.state() == ModuleHealth.State.OFF);
    }

    @Test
    void switchedOffIsNotARefusal() {
        new TestModule("hello").install(tmp, modules());
        ModuleRuntime rt = runtime(KernelFacts.ofKernel(), new ArrayList<>());
        rt.start(List.of(modules()), Map.of(), id -> false);
        assertThat(only(rt, "hello").state()).isEqualTo(ModuleHealth.State.OFF);
        assertThat(rt.routes().lookup("/m/hello/greet/a")).isEmpty();
    }

    @Test
    void activeVersionAndFirstFolderWin() {
        TestModule v1 = new TestModule("hello");
        v1.install(tmp, modules());
        TestModule v2 = new TestModule("hello");
        v2.version = "1.2.0";
        v2.install(tmp, modules());
        assertThat(ModuleDiscovery.discover(List.of(modules()), Map.of()).candidates().get(0).manifest().version()).isEqualTo("1.2.0");
        assertThat(ModuleDiscovery.discover(List.of(modules()), Map.of("hello", "1.0.0")).candidates().get(0).manifest().version())
                .isEqualTo("1.0.0");
        Dmod.write(tmp.resolve("active.yaml"), "modules:\n  hello: 1.0.0\n");
        assertThat(ModuleDiscovery.readActive(tmp.resolve("active.yaml"))).containsEntry("hello", "1.0.0");
    }

    // ---- refusals: each leaves the module absent, with plain words

    private ModuleHealth refused(KernelFacts facts, String id) {
        ModuleRuntime rt = runtime(facts, new ArrayList<>());
        rt.start(List.of(modules()), Map.of(), x -> true);
        ModuleHealth h = only(rt, id);
        assertThat(h.state()).as(h.line()).isEqualTo(ModuleHealth.State.REFUSED);
        assertThat(rt.loaded(id)).isEmpty();
        assertThat(rt.routes().snapshot().of(id)).isEmpty();
        return h;
    }

    @Test
    void refusesAChangedFile() throws Exception {
        Path dir = new TestModule("hello").install(tmp, modules());
        Files.writeString(dir.resolve("data/needs.yaml"), "needs: []\n");
        ModuleHealth h = refused(KernelFacts.ofKernel(), "hello");
        assertThat(h.check()).isEqualTo("V2");
        assertThat(h.reason()).contains("file data/needs.yaml changed");
    }

    @Test
    void refusesAFileNotInTheHashList() throws Exception {
        Path dir = new TestModule("hello").install(tmp, modules());
        Files.writeString(dir.resolve("extra.txt"), "x");
        assertThat(refused(KernelFacts.ofKernel(), "hello").reason()).contains("extra.txt is not in the hash list");
    }

    @Test
    void refusesAnExecutable() {
        TestModule t = new TestModule("hello");
        t.extraFiles.put("python/tool.exe", "MZ");
        t.install(tmp, modules());
        assertThat(refused(KernelFacts.ofKernel(), "hello").reason()).contains("python/tool.exe is an executable");
    }

    @Test
    void refusesBundledKernelApiClasses() {
        TestModule t = new TestModule("hello");
        t.extraJarClasses.put("com/ash/drishti/kernel/api/Fake.java", "package com.ash.drishti.kernel.api; public class Fake {}");
        t.install(tmp, modules());
        assertThat(refused(KernelFacts.ofKernel(), "hello").reason()).contains("bundles kernel API classes");
    }

    @Test
    void refusesTheWrongKernelRange() {
        TestModule t = new TestModule("hello");
        t.kernel = ">=2.0";
        t.install(tmp, modules());
        ModuleHealth h = refused(KernelFacts.ofKernel(), "hello");
        assertThat(h.check()).isEqualTo("V3");
        assertThat(h.reason()).contains("built for kernel API >=2.0; this kernel has " + KernelFacts.kernelApiVersion());
    }

    @Test
    void refusesMissingAndRefusedDependenciesAndCycles() {
        TestModule a = new TestModule("a");
        a.requires = "b: \">=1.0\"";
        a.functions = "[{word: AA}]";
        a.install(tmp, modules());
        TestModule b = new TestModule("b");
        b.requires = "a: \">=1.0\"";
        b.functions = "[{word: BB}]";
        b.install(tmp, modules());
        TestModule c = new TestModule("c");
        c.requires = "nowhere: \">=1.2\"";
        c.functions = "[{word: CC}]";
        c.install(tmp, modules());
        TestModule d = new TestModule("d");
        d.requires = "c: \"*\"";
        d.functions = "[{word: DD}]";
        d.install(tmp, modules());
        ModuleRuntime rt = runtime(KernelFacts.ofKernel(), new ArrayList<>());
        rt.start(List.of(modules()), Map.of(), x -> true);
        assertThat(only(rt, "a").reason()).contains("cycle");
        assertThat(only(rt, "b").reason()).contains("cycle");
        assertThat(only(rt, "c").reason()).contains("needs nowhere >=1.2; it is not installed");
        assertThat(only(rt, "d").reason()).contains("requires c, which is refused");
        assertThat(rt.health()).allMatch(h -> h.state() == ModuleHealth.State.REFUSED && "V4".equals(h.check()));
    }

    @Test
    void refusesAnUnlicensedModule() {
        new TestModule("hello").install(tmp, modules());
        ModuleHealth h = refused(KernelFacts.ofKernel().withLicence(f -> false), "hello");
        assertThat(h.reason()).contains("the licence does not include hello");
    }

    @Test
    void refusesAClaimClashAndAReservedAlias() {
        TestModule one = new TestModule("one");
        one.functions = "[{word: SAME}]";
        one.install(tmp, modules());
        TestModule two = new TestModule("two");
        two.functions = "[{word: SAME}]";
        two.install(tmp, modules());
        TestModule three = new TestModule("three");
        three.functions = "[{word: THREE}]";
        three.alias = "/admin/x";
        three.install(tmp, modules());
        ModuleRuntime rt = runtime(KernelFacts.ofKernel(), new ArrayList<>());
        rt.start(List.of(modules()), Map.of(), x -> true);
        assertThat(only(rt, "one").state()).isEqualTo(ModuleHealth.State.LOADED);
        assertThat(only(rt, "two").reason()).contains("function SAME is claimed by one");
        assertThat(only(rt, "three").reason()).contains("alias /admin/x is a reserved name");
        rt.close();
    }

    @Test
    void refusesAnUnreadableManifestAndUnknownFields() {
        TestModule t = new TestModule("hello");
        t.extraManifest = "colour: blue\n";
        t.install(tmp, modules());
        ModuleHealth h = refused(KernelFacts.ofKernel(), "hello");
        assertThat(h.check()).isEqualTo("V1");
        assertThat(h.reason()).contains("unknown field `colour`");
    }

    @Test
    void refusesUnsatisfiableNeedsAndOverBudgetWhenStrict() {
        new TestModule("hello").install(tmp, modules());
        assertThat(refused(KernelFacts.ofKernel().withKnownKinds(Set.of("trade")), "hello").reason())
                .contains("needs kind greeting, which no module or the kernel provides");
        assertThat(refused(KernelFacts.ofKernel().withBudget(32, true), "hello").reason()).contains("needs 64 MB; 32 MB");
        ModuleRuntime warned = runtime(KernelFacts.ofKernel().withBudget(32, false), new ArrayList<>());
        warned.start(List.of(modules()), Map.of(), x -> true);
        assertThat(only(warned, "hello").notes()).anyMatch(n -> n.startsWith("warning:"));
        warned.close();
    }

    @Test
    void refusesAModuleWhoseClassesDoNotLoad() {
        TestModule t = new TestModule("hello");
        t.spring = "test.hello.Missing";
        t.install(tmp, modules());
        ModuleHealth h = refused(KernelFacts.ofKernel(), "hello");
        assertThat(h.check()).isEqualTo("START");
        assertThat(h.reason()).contains("did not start: class test.hello.Missing");
        assertThatThrownBy(() -> DmodManifest.read(new java.io.ByteArrayInputStream("format: 2\n".getBytes()), "x"))
                .hasMessageContaining("`format` must be 1");
    }

    @Test
    void unpackRefusesAPathLeavingTheFolder() throws Exception {
        Path evil = tmp.resolve("evil.dmod");
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(evil))) {
            z.putNextEntry(new java.util.zip.ZipEntry("../outside.txt"));
            z.write(1);
            z.closeEntry();
        }
        assertThatThrownBy(() -> Dmod.unpack(evil, tmp.resolve("in"))).hasMessageContaining("leaves the module folder");
        assertThat(tmp.resolve("outside.txt")).doesNotExist();
    }

    @Test
    void versionRangesAndRouteTemplates() {
        assertThat(VersionRange.parse(">=1.0 <2.0").contains("1.10.3")).isTrue();
        assertThat(VersionRange.parse(">=1.0 <2.0").contains("2.0")).isFalse();
        assertThat(VersionRange.parse("1.4").contains("1.4.0")).isTrue();
        assertThat(VersionRange.parse("*").contains("0.0.1")).isTrue();
        assertThatThrownBy(() -> VersionRange.parse(">=x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(RouteTable.match("/var/{scope...}", "/var")).containsEntry("scope", "");
        assertThat(RouteTable.match("/var/{scope...}", "/var/desk/rates")).containsEntry("scope", "desk/rates");
        assertThat(RouteTable.match("/v/{c}", "/v")).isNull();
        assertThat(RouteTable.match("/v/{c}", "/v/a/b")).isNull();
    }
}
