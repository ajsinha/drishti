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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.ash.drishti.module.runtime.ModuleHealth;
import com.ash.drishti.server.modules.ModuleNode;
import com.ash.rachana.el.EvalContext;
import com.ash.rachana.format.Formats;
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
 * The kernel loads modules (docs/architecture/KERNEL_AND_MODULES.md sections 4 and 12): three {@code .dmod} packages in the modules folder
 * ({@code drishti.modules.home}) are booted and load at the server's start, the SDK's archetype module twice (hello, greeter) and a module
 * of several jars (a service jar, a compute jar and a third-party jar under {@code lib/}). Each contributes a kind, a Rachana function, a
 * REST route and a page, used from the kernel as its own. Nothing is deployed or removed while the server runs: a module is a file and a
 * restart (the unload and its leak check are the module runtime's own tests and the conformance check C14).
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=core"})
@AutoConfigureMockMvc
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class ModuleHostServerTest {

    private static Path folder;
    private static Path work;
    private static Path lib;
    private static final Map<String, String> KEPT = new LinkedHashMap<>();

    @Autowired
    MockMvc mvc;
    @Autowired
    ModuleNode node;
    @Autowired
    SourceRouter router;
    @Autowired
    SourceRegistry sources;
    @Autowired
    com.ash.drishti.rachana.LiveCompiler compiler;

    /** The modules folder with its packages, named before the server starts (the launch reads it from the process's settings). */
    @org.junit.jupiter.api.BeforeAll
    static synchronized void packages() throws IOException {
        for (String k : List.of("drishti.modules.home", "drishti.modules.sources", "drishti.modules.dev")) {
            KEPT.put(k, System.getProperty(k));
        }
        if (folder == null) {
            work = Path.of(System.getProperty("drishti.test.dir", System.getProperty("java.io.tmpdir"))).resolve("module-host-" + System.nanoTime());
            folder = work.resolve("modules");
            lib = sharedLib(work);
            Files.createDirectories(folder);
            for (Path dmod : List.of(archetypeModule(work), multiJarModule(work), archetypeModule(work, "greeter"))) {
                Files.copy(dmod, folder.resolve(dmod.getFileName().toString()));
            }
        }
        System.setProperty("drishti.modules.home", folder.toString());
        System.clearProperty("drishti.modules.sources");
        System.clearProperty("drishti.modules.dev");
    }

    @org.junit.jupiter.api.AfterAll
    static synchronized void restore() {
        KEPT.forEach((k, v) -> {
            if (v == null) {
                System.clearProperty(k);
            } else {
                System.setProperty(k, v);
            }
        });
    }

    @DynamicPropertySource
    static synchronized void settings(DynamicPropertyRegistry r) throws IOException {
        r.add("drishti.lib.path", () -> lib.toString());
        // hello and demo are placed by name on this node ("local" without a cluster); greeter is not named: on a single node a module
        // with no placement is placed here by default
        Path placement = work.resolve("placement.yaml");
        if (!Files.exists(placement)) {
            ModuleFixtures.write(placement, "modules:\n" + String.join("", java.util.stream.Stream.of("hello", "demo")
                    .map(id -> "  " + id + ":\n    parts:\n      api: { nodes: [local] }\n").toList()));
        }
        r.add("drishti.placement.file", placement::toString);
    }

    @Test
    void modulesLoadAtStartAndAreUsedFromTheKernel() throws Exception {
        assertThat(node.runtime().health()).extracting(ModuleHealth::id, ModuleHealth::state).contains(
                org.assertj.core.groups.Tuple.tuple("demo", ModuleHealth.State.LOADED), org.assertj.core.groups.Tuple.tuple("hello", ModuleHealth.State.LOADED),
                org.assertj.core.groups.Tuple.tuple("greeter", ModuleHealth.State.LOADED));
        used();
        assertThat(node.entries()).extracting(ModuleNode.Entry::id).containsExactly("demo", "greeter", "hello");
        assertThat(node.entry("demo").orElseThrow().version()).isEqualTo("1.0.0");
        assertThat(node.needs("see /api/v1/demo/shout/x")).as("a module that is on needs nothing").isEmpty();
        assertThat(Extensions.describe(null)).isNotEmpty();
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
        // the pages, as the console's dispatcher asks the route table, and a route that moved
        assertThat(page("/m/demo/widgets/W1")).isEqualTo(200);
        assertThat(node.runtime().routes().lookup("/m/demo/widgets/W1").orElseThrow().route().target()).isEqualTo("widget.html");
        assertThat(page("/m/hello/hello/Asha")).isEqualTo(200);
        assertThat(page("/m/demo/old")).isEqualTo(308);
        // Health: the module's own figures and the host's card
        assertThat(Extensions.of(HealthFigures.class)).extracting(HealthFigures::key).contains("demo-module");
        assertThat(node.status().toString()).contains("third-party", "KIND_PROVIDER", "FUNCTION", "endpoint /api/v1/demo/shout/{text}");
        assertThat(DemoState.started()).isTrue();
    }

    @Test
    void theSharedLibIsSeenByModulesAndJdbcButNeverReplacesAKernelClass() throws Exception {
        com.ash.drishti.common.SharedLib l = com.ash.drishti.common.SharedLib.get();
        assertThat(l.jars()).extracting(com.ash.drishti.common.SharedLib.Jar::name).containsExactly("acme-lib.jar");
        assertThat(l.jars().get(0).sha256()).hasSize(64);
        assertThat(l.info().toString()).contains("mark=lib");
        assertThat(l.loader().loadClass("com.ash.drishti.common.Branding").getClassLoader()).isNotSameAs(l.loader());
        assertThat(java.sql.DriverManager.getDriver("jdbc:acme:test")).isNotNull();
        // a module uses a lib class it does not bundle
        assertThat(eval("demo_tax(100)")).isEqualTo(107.0);
    }

    @Test
    void aModuleWithNoPlacementEntryIsPlacedHereOnASingleNode(@Autowired com.ash.drishti.server.placement.PlacementService placement) throws Exception {
        assertThat(placement.health().get("notes")).isEqualTo(List.of("greeter: single node: placed here"));
        mvc.perform(get("/api/v1/greeter/greeting/Asha")).andExpect(status().isOk())
                .andExpect(jsonPath("$.greeting").value("Hello, Asha, from the greeter module"));
        assertThat(eval("greeter_greet('Ravi')")).isEqualTo("Hello, Ravi, from the greeter module");
    }

    private int page(String path) {
        return node.runtime().routes().lookup(path).orElseThrow().status();
    }

    private Object eval(String expression) {
        // the server's compiler holder (the bean every reader takes), as the server compiles a Sutra's expressions
        return compiler.compile(expression).eval(EvalContext.of(DataNode.of(Map.of()), Formats.defaults()));
    }

    /**
     * Gate 4 F1: a module stopped after the server is up is gone from every screen the server binds, and one started later is callable at
     * once, through the API (Sutra Studio's preview), not a compiler of the test's own; the stopped module's class loader is unreachable.
     * Last: it stops and starts a module the other tests use.
     */
    @Test
    @org.junit.jupiter.api.Order(Integer.MAX_VALUE)
    void aModuleStoppedOrStartedAfterStartIsSeenByTheServersScreensAtOnce() throws Exception {
        String yaml = "rachana: 1.2\nsutra: greeted\nversion: 1\nmatch: { kind: widget }\ntitle: { pill: Widget, id: $.code }\n"
                + "strip:\n  - { label: Greeting, bind: \"greeter_greet($.code)\" }\n";
        String body = "{\"yaml\":" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(yaml)
                + ",\"kind\":\"widget\",\"id\":\"W-1\",\"document\":{\"code\":\"Ravi\"}}";
        org.springframework.test.web.servlet.RequestBuilder preview = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/studio/preview").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body);
        mvc.perform(preview).andExpect(status().isOk()).andExpect(jsonPath("$.strip[0].text").value("Hello, Ravi, from the greeter module"));

        com.ash.drishti.module.runtime.ModuleRuntime.UnloadReport r = node.runtime().unload("greeter", true);
        assertThat(r.failures()).isEmpty();
        assertThat(r.leakFree()).as("nothing of the server keeps the stopped module's class loader").isTrue();
        String refused = mvc.perform(preview).andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(refused).contains("the function 'greeter_greet' is off: its module 'greeter' is not running on this server");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> eval("greeter_greet('Ravi')")).hasMessageContaining("greeter_greet");

        node.runtime().start(List.of(folder), id -> true);              // started again, after the server is up
        assertThat(node.runtime().loaded("greeter")).isPresent();
        mvc.perform(preview).andExpect(status().isOk()).andExpect(jsonPath("$.strip[0].text").value("Hello, Ravi, from the greeter module"));
        assertThat(eval("greeter_greet('Asha')")).isEqualTo("Hello, Asha, from the greeter module");
    }

    // ---- the modules ------------------------------------------------------------------------------------------------------------------

    /** The SDK's archetype, generated as {@code mvn archetype:generate} would, compiled and packed. */
    private static Path archetypeModule(Path work) {
        return archetypeModule(work, "hello");
    }

    private static Path archetypeModule(Path work, String id) {
        Path project = ModuleFixtures.generate(Path.of(System.getProperty("drishti.sdk.archetype")), id, work.resolve(id));
        Path jar = ModuleFixtures.jarOf(project.resolve("java/" + id + "/src/main/java"), null, work.resolve(id + "-build"), work.resolve(id + "-build/" + id + "-0.1.0.jar"));
        return ModuleFixtures.pack(project, List.of(jar), work.resolve("dist-" + id));
    }

    /** The shared lib folder: one jar with a class modules use, a JDBC driver, and a kernel class's name it must never replace. */
    private static Path sharedLib(Path work) throws IOException {
        Path out = work.resolve("lib");
        Path build = work.resolve("acme-build");
        ModuleFixtures.write(build.resolve("classes/META-INF/services/java.sql.Driver"), "com.acme.AcmeDriver\n");
        Map<String, String> src = new LinkedHashMap<>();
        src.put("com/acme/Tax.java", "package com.acme;\npublic final class Tax {\n    private Tax() {}\n    public static double gross(double net) { return net * 1.07; }\n}\n");
        src.put("com/acme/AcmeDriver.java", """
                package com.acme;
                import java.sql.*;
                import java.util.Properties;
                public class AcmeDriver implements Driver {
                    public Connection connect(String url, Properties info) { return null; }
                    public boolean acceptsURL(String url) { return url != null && url.startsWith("jdbc:acme:"); }
                    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
                    public int getMajorVersion() { return 1; }
                    public int getMinorVersion() { return 0; }
                    public boolean jdbcCompliant() { return false; }
                    public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getLogger("acme"); }
                }
                """);
        src.put("com/ash/drishti/common/Branding.java", "package com.ash.drishti.common;\npublic final class Branding {}\n");
        ModuleFixtures.jarOf(src, null, build, out.resolve("acme-lib.jar"));
        return out;
    }

    /** A module of three jars: its service jar, its compute jar, and a third-party jar under lib/. */
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
                                        args -> thirdparty.shout.Shout.loud(String.valueOf(args.get(0)))),
                                        new Provided("demo_tax", 1, 1, "net", "the gross of a net amount (the site's lib)",
                                        args -> com.acme.Tax.gross(((Number) args.get(0)).doubleValue())));
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
        String cp = lib + File.pathSeparator + compute + File.pathSeparator + ModuleHostServerTest.lib.resolve("acme-lib.jar");
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
                elements: ">=2.4 <3"
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
        ModuleFixtures.write(src.resolve("console/pages/widget.html"), "<section data-drishti-module=\"demo\">a widget</section>\n");
        return ModuleFixtures.pack(src, List.of(svc, compute, lib), work.resolve("dist"));
    }

    /** The demo module's entry, seen from outside its class loader. */
    private static final class DemoState {

        static boolean started() {
            String s = System.getProperty("drishti.test.demo-module.started");
            return s != null && s.equals("demo@demo");
        }
    }
}
