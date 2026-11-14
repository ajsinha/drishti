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
package com.ash.drishti.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.dataload.Routes;
import com.ash.drishti.lakeconfig.ModuleDirs;
import com.ash.drishti.module.runtime.Dmod;
import com.ash.drishti.module.tool.ModuleTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A source module as a real package: {@code source-swapone} packed with the module tool, installed under {@code modules/installed/<id>/<version>/}
 * (what a deployment stages), seen through {@link ModuleDirs} (its inbox rules and its connector templates), then uninstalled and gone.
 */
class SourceModuleInstallTest {

    @TempDir
    Path tmp;

    private static Path repo() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("modules/source-swapone"))) {
            p = p.getParent();
        }
        return p;
    }

    private static void delete(Path root) throws Exception {
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static void copy(Path from, Path to) throws Exception {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.copy(p, t);
                }
            }
        }
    }

    /** The module's rules as the server reads them: the dataload file of each module folder {@link ModuleDirs} lists. */
    private static List<Routes.ModuleRules> rulesOf(Path modules) throws Exception {
        List<Routes.ModuleRules> out = new ArrayList<>();
        YAMLMapper yaml = new YAMLMapper();
        for (Path m : ModuleDirs.of(modules)) {
            JsonNode d = yaml.readTree(Files.readString(m.resolve("module.yaml"))).path("dataload");
            if (d.isTextual()) {
                out.add(new Routes.ModuleRules(ModuleDirs.idOf(m), yaml.readTree(Files.readString(m.resolve(d.asText())))));
            }
        }
        return out;
    }

    @Test
    void aPackedSourceModuleIsInstalledSeenAndUninstalledAway() throws Exception {
        Path folder = tmp.resolve("source-swapone");
        copy(repo().resolve("modules/source-swapone"), folder);
        Path out = tmp.resolve("dmod");
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        int code = ModuleTool.run(new String[] {"pack", folder.toString(), "--out", out.toString()}, new PrintStream(said, true, StandardCharsets.UTF_8),
                new PrintStream(said, true, StandardCharsets.UTF_8));
        assertThat(code).as(said.toString(StandardCharsets.UTF_8)).isZero();
        Path dmod = out.resolve(Dmod.fileName("source-swapone", "1.0.0"));
        assertThat(dmod).isRegularFile();

        Path modules = tmp.resolve("modules");
        assertThat(ModuleDirs.of(modules)).as("nothing installed yet").isEmpty();
        assertThat(rulesOf(modules)).isEmpty();

        Path installed = modules.resolve("installed/source-swapone/1.0.0");     // what a deployment stages
        Files.createDirectories(installed);
        Dmod.unpack(dmod, installed);

        assertThat(ModuleDirs.of(modules)).containsExactly(installed);
        assertThat(ModuleDirs.idOf(installed)).isEqualTo("source-swapone");
        List<Routes.ModuleRules> rules = rulesOf(modules);
        assertThat(rules).hasSize(1);
        JsonNode rule = rules.get(0).document().path("dataload").path("rules").get(0);
        assertThat(rule.path("match").asText()).isEqualTo("swapone-*.jsonl.gz");
        assertThat(rule.path("date-pattern").asText()).isNotBlank();
        assertThat(rule.path("connector").asText()).isEqualTo("trade-swapone");
        try (Stream<Path> s = Files.list(installed.resolve("connectors"))) {
            assertThat(s.map(p -> p.getFileName().toString()).sorted().toList()).contains("delta.yaml", "files.yaml", "iceberg.yaml", "kafka.yaml");
        }
        assertThat(installed.resolve("mappings/swapone-trade.yaml")).isRegularFile();

        delete(modules.resolve("installed/source-swapone"));                       // uninstalled
        assertThat(ModuleDirs.of(modules)).as("the module's folder is gone").isEmpty();
        assertThat(rulesOf(modules)).as("its inbox rules are gone").isEmpty();
    }
}
