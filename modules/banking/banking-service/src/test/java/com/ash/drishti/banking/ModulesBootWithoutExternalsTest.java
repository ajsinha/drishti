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
package com.ash.drishti.banking;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.module.tool.ModuleAssembler;
import com.ash.drishti.server.modules.deploy.ModuleAutoDeploy;
import com.ash.drishti.testkit.ModuleFixtures;
import com.ash.drishti.testroots.TestRoots;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The owner's rule: every module starts with no external system (Kafka, ActiveMQ, RabbitMQ, an AI provider, an outside HTTP API, a live feed,
 * a cluster peer), and the absence only switches that feature off. One server per shipped manifest (the modules folder, the banking
 * package, the genomics sources), one after the other and closed after each (bounded memory): only that module on (and what it needs), the
 * broker and every outside URL pointed at a port nothing listens on, its stream switches ON so the streams start. Health is UP, the module
 * loaded, its packs listed, every stream connector WAITING (or switched off), and the log holds no ERROR line.
 */
@ExtendWith(OutputCaptureExtension.class)
class ModulesBootWithoutExternalsTest {

    private static final String NOTHING = "127.0.0.1:1";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> OK_STREAM = Set.of("WAITING", "DISABLED", "NOT CONFIGURED", "NOT_CONFIGURED");

    /** One shipped manifest: its id, its file, the packs (folders with a pack.yaml) it brings and the modules it names in requires. */
    record Shipped(String id, Path manifest, List<String> packs, List<String> requires) {
        @Override public String toString() { return id; }
    }

    static Stream<Shipped> shipped() throws IOException {
        Path root = Path.of(TestRoots.repo(), "modules");
        List<Path> manifests = new ArrayList<>();
        try (Stream<Path> s = Files.list(root)) {
            s.sorted().forEach(d -> {
                for (String rel : List.of("module.yaml", "dmod/module.yaml", "src/dmod/module.yaml")) {
                    if (Files.isRegularFile(d.resolve(rel))) {
                        manifests.add(d.resolve(rel));
                        break;
                    }
                }
            });
        }
        List<Shipped> out = new ArrayList<>();
        for (Path m : manifests) {
            JsonNode n = YAML.readTree(m.toFile());
            Path packs = m.getParent().resolve(n.path("packs").path("folder").asText("packs"));
            List<String> names = new ArrayList<>();
            if (Files.isDirectory(packs)) {
                try (Stream<Path> s = Files.list(packs)) {
                    s.filter(p -> Files.isRegularFile(p.resolve("pack.yaml"))).map(p -> p.getFileName().toString()).sorted().forEach(names::add);
                }
            }
            List<String> req = new ArrayList<>();
            n.path("requires").fieldNames().forEachRemaining(f -> {
                if (!"optional".equals(f)) req.add(f);
            });
            n.path("requires").path("optional").fieldNames().forEachRemaining(req::add);
            out.add(new Shipped(n.path("id").asText(), m, names, req));
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shipped")
    void startsWithNoExternalSystem(Shipped module, @TempDir Path temp, CapturedOutput output) throws Exception {
        List<Shipped> all = shipped().toList();
        boolean genomics = module.id().equals("genomics");
        Set<String> on = new LinkedHashSet<>();
        collect(module.id(), all, on);
        if (module.id().startsWith("source-") || module.id().equals("trading")) {
            collect("banking", all, on);                                   // the canonical trade kind lives in the banking packs
        }
        Set<String> packs = new LinkedHashSet<>(List.of("core", "market-data"));
        for (Shipped s : all) {
            if (on.contains(s.id())) packs.addAll(s.packs());
        }
        Map<String, String> p = new LinkedHashMap<>();
        p.put("server.port", "0");
        p.put("drishti.rachana.hot-reload", "false");
        p.put("drishti.sources.plugins.demo.enabled", "false");
        p.put("drishti.data.dir", temp.resolve("data").toAbsolutePath().toString());
        p.put("drishti.identity.database-url", "jdbc:sqlite:" + temp.resolve("identity/identity.db").toAbsolutePath());
        p.put("drishti.packs.enabled", String.join(",", genomics ? List.of("core") : packs));
        p.put("drishti.modules.autodeploy-seconds", "3600");
        for (Shipped s : all) {
            if (!on.contains(s.id())) p.put("drishti.modules." + s.id() + ".enabled", "false");
        }
        if (genomics) {
            p.put("drishti.modules.banking.enabled", "false");
            Path project = module.manifest().getParent().getParent().getParent();
            Path jar = ModuleFixtures.jarOf(project.resolve("src/main/java"), null, temp.resolve("build"), temp.resolve("build/genomics.jar"));
            Path dmod = ModuleAssembler.assemble(project.resolve("src/dmod"), jar, temp.resolve("stage"), temp.resolve("dist"));
            Files.createDirectories(temp.resolve("modules"));
            Files.copy(dmod, temp.resolve("modules").resolve(dmod.getFileName()));
            ModuleFixtures.write(temp.resolve("placement.yaml"), "modules:\n  genomics:\n    parts:\n      api: { nodes: [local] }\n");
            p.put("drishti.modules.path", temp.resolve("modules").toAbsolutePath().toString());
            p.put("drishti.placement.file", temp.resolve("placement.yaml").toAbsolutePath().toString());
            p.put("drishti.bindings.file", temp.resolve("bindings.yaml").toAbsolutePath().toString());
        }
        // no external system: the broker and every outside address at a port nothing listens on; the streams switched on so they start
        p.put("DRISHTI_KAFKA_BOOTSTRAP", NOTHING);
        p.put("DRISHTI_YFINANCE_URL", "http://" + NOTHING + "/yf");
        p.put("DRISHTI_NEWS_URL", "http://" + NOTHING + "/news");
        p.put("DRISHTI_REST_URL", "http://" + NOTHING + "/api");
        for (String s : List.of("TRADING", "ENDUR", "MUREX", "CALYPSO", "WSS", "ICI", "SWAPONE")) p.put("DRISHTI_STREAM_" + s, "true");
        p.put("DRISHTI_VAR_PV_STREAM", "true");

        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(BankingTestServer.class)
                .properties(p.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new)).run()) {
            String base = "http://127.0.0.1:" + ctx.getEnvironment().getProperty("local.server.port");
            HttpClient http = HttpClient.newHttpClient();
            if (genomics) {
                assertThat(ctx.getBean(ModuleAutoDeploy.class).scan()).as("the dropped genomics package").containsExactly("deployed genomics 0.1.0");
            }
            assertThat(get(http, base + "/actuator/health").path("status").asText()).as("health").isEqualTo("UP");
            boolean loaded = false;
            for (JsonNode m : get(http, base + "/api/v1/admin/modules").path("modules")) {
                loaded |= module.id().equals(m.path("id").asText()) && m.path("loaded").asBoolean();
            }
            assertThat(loaded).as("module " + module.id() + " loaded").isTrue();
            List<String> served = new ArrayList<>();
            get(http, base + "/api/v1/packs").forEach(n -> served.add(n.path("name").asText()));
            assertThat(served).as("packs served").containsAll(module.packs());
            if (module.id().equals("banking")) {
                get(http, base + "/api/v1/risk/var/status");
                get(http, base + "/api/v1/risk/simm/versions");
            }
            SourceRegistry sources = ctx.getBean(SourceRegistry.class);
            long deadline = System.currentTimeMillis() + 30_000;
            Map<String, String> bad;
            do {
                bad = new LinkedHashMap<>();
                for (var e : sources.connectorStatuses().entrySet()) {
                    String name = e.getKey().toLowerCase();
                    String state = e.getValue().state();
                    if ((name.contains("stream") || name.contains("live") || name.contains("kafka") || name.contains("pv")) && !OK_STREAM.contains(state)) {
                        bad.put(e.getKey(), state + " " + e.getValue().problem());
                    }
                }
                if (!bad.isEmpty()) Thread.sleep(500);
            } while (!bad.isEmpty() && System.currentTimeMillis() < deadline);
            assertThat(bad).as("stream connectors WAITING with no broker").isEmpty();
            sources.connectorStatuses().forEach((k, v) -> assertThat(v.state()).as("connector " + k).isNotIn("FAILED", "DOWN"));
        }
        assertThat(output.getAll()).as("the log of " + module.id()).doesNotContain(" ERROR ");
    }

    /** The module and, through its manifest, what it requires (and the optional ones), by id. */
    private static void collect(String id, List<Shipped> all, Set<String> into) {
        if (!into.add(id)) return;
        for (Shipped s : all) {
            if (s.id().equals(id)) s.requires().forEach(r -> collect(r, all, into));
        }
    }

    private static JsonNode get(HttpClient http, String url) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(url + ": " + r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }
}
