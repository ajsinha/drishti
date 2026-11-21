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
package com.ash.drishti.banking.var;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.banking.BankingTestServer;
import com.ash.drishti.testkit.ModuleFixtures;
import com.ash.drishti.packs.PackRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The daily-loads rehearsal's release blocker: the banking module INSTALLED from its {@code .dmod} (dropped into the modules folder beside
 * the server and booted from it, unpacked into the cache) must serve VaR from its packs' connector templates at every start. The package once dropped the packs' {@code connectors:} section, and the server
 * writes no connector file (KERNEL_AND_MODULES.md section 18.5), so after the first restart risk-store, var-engine, var-positions,
 * var-system-baskets, var-results and mr-derived were gone and VaR answered 404 DRS-1001 "no P&amp;L vectors in any connector". Here the
 * package is made by the same packer, installed, the demo is off, and the server is started twice over the same folders; each start
 * finds the templates in the installed module and answers VaR from the lake.
 */
class VarInstalledModuleRestartTest {

    static final List<String> MARKET_RISK = List.of("risk-store", "var-engine", "var-positions", "var-system-baskets", "var-results", "mr-derived");

    @TempDir
    Path work;

    @Test
    void varAnswersFromTheInstalledModuleAfterEachRestart() throws Exception {
        Path repo = TestDataBook.root();
        install(repo.resolve("modules/market-data"), work, "drishti-market-data.jar");              // banking requires it
        Path modules = install(repo.resolve("modules/banking"), work, "drishti-banking.jar", "drishti-compute-banking.jar");
        assertThat(modules.resolve("banking-1.0.0.dmod")).isRegularFile();
        Path data = Files.createDirectories(work.resolve("data"));
        VarLakeFixture.write(data.resolve("delta/demo"));                  // the demo lake's folder, as config/lakes/demo.yaml names it

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("server.port", "0");
        props.put("drishti.data.dir", data.toString());
        props.put("lake.lakes.dir", repo.resolve("config/lakes").toString());
        props.put("drishti.modules.home", modules.toString());             // where the installer put the package; the runtime finds no class in its stand-in jars: banking's are here
        props.put("drishti.modules.sources", "false");
        props.put("drishti.packs.enabled", "market-data,market-risk");
        props.put("drishti.sources.plugins.demo.enabled", "false");      // the demo off, as the owner's stack runs
        props.put("drishti.sources.connectors-dir", Files.createDirectories(work.resolve("connectors")).toString());
        props.put("drishti.rachana.hot-reload", "false");
        props.put("drishti.var.official.on-start", "false");
        props.put("drishti.var.threads", "2");
        props.put("drishti.identity.database-url", "jdbc:sqlite:" + work.resolve("identity.db"));

        for (int start = 1; start <= 3; start++) {                         // the first start and two restarts over the same folders
            try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(BankingTestServer.class).run(args(props))) {
                PackRegistry packs = ctx.getBean(PackRegistry.class);
                com.ash.drishti.packs.Pack mr = packs.packs().stream().filter(p -> p.name().equals("market-risk")).findFirst().orElse(null);
                assertThat(mr).as("start " + start).isNotNull();
                assertThat(mr.dir().toString()).as("read from the module's unpacked package").contains("cache");
                assertThat(mr.connectorTemplates()).as("start " + start).containsKeys(MARKET_RISK.toArray(String[]::new));
                assertThat(Files.list(work.resolve("connectors")).toList()).as("the server writes no connector file").isEmpty();
                String port = ctx.getEnvironment().getProperty("local.server.port");
                HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/api/v1/risk/var/scope?scope=BANK&asOf=" + VarLakeFixture.DAY)).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(r.statusCode()).as("start " + start + ": " + r.body()).isEqualTo(200);
                JsonNode h = new ObjectMapper().readTree(r.body());
                assertThat(h.path("var").isNumber() || h.path("headline").path("var").isNumber()).as(r.body()).isTrue();
            }
        }
    }

    /** The settings as command-line arguments: they win over the test fork's system properties (drishti.packs.enabled, drishti.data.dir ...). */
    private static String[] args(Map<String, Object> props) {
        return props.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
    }

    /**
     * The module packed by the one packer (ModulePack) and dropped into a modules folder: {@code <modules>/banking-1.0.0.dmod}. Its
     * jars are stand-ins: the test's server has banking's classes already.
     */
    static Path install(Path source, Path work, String... jarNames) throws Exception {
        Path jars = Files.createDirectories(work.resolve("jars"));
        List<Path> stand = new java.util.ArrayList<>();
        for (String name : jarNames) {
            stand.add(jar(jars.resolve(name)));
        }
        Path dmod = ModuleFixtures.pack(source, stand, work.resolve("dist"), "test-kernel-build");
        Path modules = Files.createDirectories(work.resolve("modules"));
        Files.copy(dmod, modules.resolve(dmod.getFileName()));
        return modules;
    }

    private static Path jar(Path file) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new java.util.jar.JarEntry("META-INF/"));
            out.closeEntry();
        }
        return file;
    }
}
