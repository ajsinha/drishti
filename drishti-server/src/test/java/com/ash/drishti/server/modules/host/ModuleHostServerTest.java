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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.Extensions;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.kernel.api.health.HealthFigures;
import com.ash.drishti.module.runtime.Dmod;
import com.ash.drishti.module.runtime.ModuleHealth;
import com.ash.drishti.module.runtime.ModuleRuntime;
import com.ash.drishti.module.tool.ModuleAssembler;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.ElException;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.testkit.ModuleFixtures;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The kernel loads modules (docs/architecture/KERNEL_AND_MODULES.md sections 4 and 12): two {@code .dmod} modules in the folder of
 * {@code drishti.modules.path} load at the server's start, the SDK's archetype module and a module of several jars (a service jar, a compute
 * jar and a third-party jar under {@code java/lib/}). Each contributes a kind, a Rachana function, a REST route and a page, used from the
 * kernel as its own; unloaded, every one is gone (the route answers 410); put back, they return, and the class loader is collected each time.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=trading"})
@AutoConfigureMockMvc
class ModuleHostServerTest {

    private static Path folder;

    @Autowired
    MockMvc mvc;
    @Autowired
    KernelModuleHost host;
    @Autowired
    SourceRouter router;
    @Autowired
    SourceRegistry sources;

    @DynamicPropertySource
    static synchronized void modules(DynamicPropertyRegistry r) throws IOException {
        if (folder == null) {
            Path work = Path.of(System.getProperty("drishti.test.dir", System.getProperty("java.io.tmpdir"))).resolve("module-host-" + System.nanoTime());
            folder = work.resolve("modules");
            install(archetypeModule(work), folder);
            install(multiJarModule(work), folder);
        }
        r.add("drishti.modules.path", () -> folder.toString());
    }

    @Test
    void modulesLoadAtStartAndAreUsedFromTheKernelThenUnloadLeakFreeAndComeBack() throws Exception {
        assertThat(host.health()).extracting(ModuleHealth::id, ModuleHealth::state).containsExactly(
                org.assertj.core.groups.Tuple.tuple("demo", ModuleHealth.State.LOADED), org.assertj.core.groups.Tuple.tuple("hello", ModuleHealth.State.LOADED));
        used();
        // the off switch: off (404), back with a rescan
        assertThat(host.unload("hello", false).leakFree()).isTrue();
        mvc.perform(get("/api/v1/hello/greeting/Asha")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-1025"));
        // removed: everything gone, its routes 410
        ModuleRuntime.UnloadReport r = host.unload("demo", true);
        assertThat(r.failures()).isEmpty();
        assertThat(r.leakFree()).as("the multi-jar module's class loader is collected").isTrue();
        gone();
        // reinstalled (the folder looked at again): everything back, and gone again without a leak
        assertThat(host.rescan()).extracting(ModuleHealth::state).containsOnly(ModuleHealth.State.LOADED);
        used();
        assertThat(host.unload("demo", true).leakFree()).isTrue();
        assertThat(host.unload("hello", true).leakFree()).isTrue();
        gone();
        assertThat(Extensions.describe(null)).isEmpty();
    }

    private void used() throws Exception {
        // the kind, read through the kernel's router like any other
        List<EntityDocument> widgets = new ArrayList<>();
        assertThat(router.documents("demo-widget", AsOf.LATEST, widgets::add)).isTrue();
        assertThat(widgets).extracting(d -> d.ref().id()).containsExactly("W1", "W2");
        assertThat(router.fetch(EntityRef.of("hello-greeting", "ASHA"), AsOf.LATEST).get(10, TimeUnit.SECONDS).data().toString())
                .contains("Hello, ASHA, from the hello module");
        // the functions, in any Rachana expression
        assertThat(eval("demo_shout('hi')")).isEqualTo("HI!");
        assertThat(eval("hello_greet('Ravi')")).isEqualTo("Hello, Ravi, from the hello module");
        // the REST routes, served by each module's own controllers (the third-party and compute jars in the answer)
        mvc.perform(get("/api/v1/demo/shout/quiet")).andExpect(status().isOk()).andExpect(jsonPath("$.loud").value("QUIET!"))
                .andExpect(jsonPath("$.area").value(6.0));
        mvc.perform(get("/api/v1/hello/greeting/Asha")).andExpect(status().isOk()).andExpect(jsonPath("$.greeting").value("Hello, Asha, from the hello module"));
        mvc.perform(get("/api/v1/demo/nothing-here")).andExpect(status().isNotFound());
        // the pages, and a route that moved
        mvc.perform(get(ModuleDispatchFilter.PAGES + "/demo/widgets/W1")).andExpect(status().isOk()).andExpect(header().string("X-Drishti-Route", "widgets"));
        mvc.perform(get(ModuleDispatchFilter.PAGES + "/hello/hello/Asha")).andExpect(status().isOk());
        mvc.perform(get(ModuleDispatchFilter.PAGES + "/demo/old")).andExpect(status().isPermanentRedirect())
                .andExpect(header().string("Location", ModuleDispatchFilter.PAGES + "/demo/widgets/{id}"));
        // Health: the module's own figures and the host's card
        assertThat(Extensions.of(HealthFigures.class)).extracting(HealthFigures::key).contains("demo-module");
        assertThat(host.describe().toString()).contains("KIND_PROVIDER", "FUNCTION", "controllers of /api/v1/demo");
        assertThat(DemoState.started()).isTrue();
    }

    private void gone() throws Exception {
        assertThat(sources.plugin("demo-widgets")).isEmpty();
        assertThat(router.documents("demo-widget", AsOf.LATEST, d -> { })).isFalse();
        assertThatThrownBy(() -> eval("demo_shout('hi')")).isInstanceOf(ElException.class);
        mvc.perform(get("/api/v1/demo/shout/quiet")).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("DRS-1028"));
        mvc.perform(get(ModuleDispatchFilter.PAGES + "/demo/widgets/W1")).andExpect(status().isGone());
        assertThat(Extensions.describe("demo")).isEmpty();
        assertThat(DemoState.started()).isFalse();
    }

    private static Object eval(String expression) {
        return new ElCompiler(16).compile(expression).eval(EvalContext.of(DataNode.of(Map.of()), Formats.defaults()));
    }

    // ---- the modules ------------------------------------------------------------------------------------------------------------------

    /** The SDK's archetype, generated as {@code mvn archetype:generate} would, compiled and packed. */
    private static Path archetypeModule(Path work) {
        Path project = ModuleFixtures.generate(Path.of(System.getProperty("drishti.sdk.archetype")), "hello", work.resolve("hello"));
        Path jar = ModuleFixtures.jarOf(project.resolve("src/main/java"), null, work.resolve("hello-build"), work.resolve("hello-build/hello-0.1.0.jar"));
        return ModuleAssembler.assemble(project.resolve("src/dmod"), jar, work.resolve("stage"), work.resolve("dist"));
    }

    /** A module of three jars: its service jar, its compute jar, and a third-party jar under java/lib/. */
    private static Path multiJarModule(Path work) throws IOException {
        Path lib = ModuleFixtures.jarOf(Map.of("thirdparty/shout/Shout.java", """
                package thirdparty.shout;
                public final class Shout {
                    private Shout() {}
                    public static String loud(String s) { return s.toUpperCase(java.util.Locale.ROOT) + "!"; }
                }
                """), null, work.resolve("shout-build"), work.resolve("jars/shout-1.0.jar"));
        Path compute = ModuleFixtures.jarOf(Map.of("test/demo/compute/WidgetMaths.java", """
                package test.demo.compute;
                public final class WidgetMaths {
                    private WidgetMaths() {}
                    public static double area(double w, double h) { return w * h; }
                }
                """), null, work.resolve("compute-build"), work.resolve("jars/demo-compute.jar"));
        Map<String, String> service = new LinkedHashMap<>();
        service.put("test/demo/DemoConfig.java", """
                package test.demo;
                import com.ash.drishti.api.*;
                import com.ash.drishti.kernel.api.data.KindProvider;
                import com.ash.drishti.kernel.api.function.FunctionProvider;
                import com.ash.drishti.kernel.api.health.HealthFigures;
                import java.util.*;
                import java.util.function.Consumer;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                @Configuration
                public class DemoConfig {
                    @Bean public KindProvider widgets() {
                        return new KindProvider() {
                            public String name() { return "demo-widgets"; }
                            public Set<String> kinds() { return Set.of("demo-widget"); }
                            public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) {
                                return "demo-widget".equals(ref.kind()) ? Optional.of(doc(ref.id())) : Optional.empty();
                            }
                            public boolean documents(String kind, AsOf asOf, Consumer<EntityDocument> sink) {
                                if (!"demo-widget".equals(kind)) { return false; }
                                sink.accept(doc("W1")); sink.accept(doc("W2")); return true;
                            }
                            private EntityDocument doc(String id) {
                                return new EntityDocument(new EntityRef("demo-widget", id), DataNode.of(Map.of("ID", id)),
                                        new Provenance("demo-widgets", 1, java.time.Instant.now(), false, null));
                            }
                        };
                    }
                    @Bean public FunctionProvider functions() {
                        return new FunctionProvider() {
                            public String source() { return "the demo module"; }
                            public List<Provided> functions() {
                                return List.of(new Provided("demo_shout", 1, 1, "text", "the text, loud",
                                        args -> thirdparty.shout.Shout.loud(String.valueOf(args.get(0)))));
                            }
                        };
                    }
                    @Bean public HealthFigures figures() {
                        return new HealthFigures() {
                            public String key() { return "demo-module"; }
                            public Object value() { return "ok"; }
                        };
                    }
                    @Bean public ShoutController shoutController() { return new ShoutController(); }
                }
                """);
        service.put("test/demo/ShoutController.java", """
                package test.demo;
                import com.ash.drishti.kernel.api.module.ModuleApi;
                import java.util.Map;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @ModuleApi("demo")
                public class ShoutController {
                    @GetMapping("/shout/{text}")
                    public Map<String, Object> shout(@PathVariable("text") String text) {
                        return Map.of("loud", thirdparty.shout.Shout.loud(text), "area", test.demo.compute.WidgetMaths.area(2, 3));
                    }
                }
                """);
        service.put("test/demo/DemoEntry.java", """
                package test.demo;
                import com.ash.drishti.kernel.api.module.DrishtiModule;
                import com.ash.drishti.kernel.api.module.ModuleContext;
                public class DemoEntry implements DrishtiModule {
                    public String id() { return "demo"; }
                    public void start(ModuleContext context) {
                        System.setProperty("drishti.test.demo-module.started", context.moduleId() + "@" + context.dataDir().getFileName());
                    }
                    public void stop() { System.clearProperty("drishti.test.demo-module.started"); }
                }
                """);
        String cp = lib + File.pathSeparator + compute;
        Path svc = ModuleFixtures.jarOf(service, cp, work.resolve("service-build"), work.resolve("jars/demo-service.jar"));
        Path src = work.resolve("demo-src");
        ModuleFixtures.write(src.resolve("module.yaml"), """
                format: 1
                id: demo
                version: 1.0.0
                title: Demo
                about: A module of several jars, built in tests
                domain: testing
                publisher: {name: Drishti tests, id: drishti-tests}
                licence: {feature: demo}
                kernel: ">=0.3 <1.0"
                rachana: ">=1.0"
                contracts: ">=1.0"
                plugin-api: ">=2.0"
                elements: ">=1.4 <2"
                provides: {kinds: {demo-pack: [demo-widget]}}
                api: {prefix: demo}
                console: {plugin: console/plugin.yaml}
                functions: [{word: DEMO}]
                resources: {memory-mb: 64}
                java: {jars: [demo-service.jar, demo-compute.jar], lib: [shout-1.0.jar], entry: test.demo.DemoEntry, spring: [test.demo.DemoConfig]}
                """);
        ModuleFixtures.write(src.resolve("console/plugin.yaml"), """
                format: 2
                routes:
                  - {id: widgets, path: "/widgets/{id}", page: widget.html, function: DEMO}
                  - {id: old, path: /old, moved: widgets}
                """);
        ModuleFixtures.write(src.resolve("console/pages/widget.html"), "<section data-drishti-module=\\"demo\\">a widget</section>\\n");
        return ModuleAssembler.assemble(src, svc, List.of(compute, lib), work.resolve("stage"), work.resolve("dist"));
    }

    /** Installs a package as an administrator would: unpacked under {@code <modules>/<id>/<version>/}, the package beside it. */
    private static void install(Path dmod, Path modules) throws IOException {
        String name = dmod.getFileName().toString();
        String id = name.substring(0, name.lastIndexOf('-'));
        String version = name.substring(name.lastIndexOf('-') + 1, name.length() - ".dmod".length());
        Dmod.unpack(dmod, modules.resolve(id).resolve(version));
        Files.copy(dmod, modules.resolve(id).resolve(name));
    }

    /** The demo module's entry, seen from outside its class loader. */
    private static final class DemoState {

        static boolean started() {
            String s = System.getProperty("drishti.test.demo-module.started");
            return s != null && s.equals("demo@demo");
        }
    }
}
