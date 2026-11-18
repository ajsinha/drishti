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
package com.ash.drishti.server.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.packs.distribution.Distribution;
import com.ash.drishti.packs.distribution.Distributions;
import com.ash.drishti.packs.distribution.ModuleManifests;
import com.ash.drishti.packs.edition.EditionEnvironmentPostProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ash.drishti.testroots.RecordedAnswers;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The recorded installation answers the console's unit tests read (docs/architecture/THIN_CONSOLE_DESIGN.md 5.2, "Recorded answers"):
 * {@code drishti-console/tests/answers/bootstrap-<distribution>.json}, one per distribution of the product, made here by the server's own
 * code from the repository's files (the distribution's own lenses offered, every lens's arrangement kept, every module loaded). Kept by
 * {@link RecordedAnswers}, as the modules' landing recordings are: one direction only (the server makes them, the console reads them);
 * {@code -Ddrishti.record=true} writes them again, otherwise a file that differs from what the server makes fails here.
 */
class ConsoleAnswersRecordTest {

    static final Path REPO = Path.of(com.ash.drishti.testroots.TestRoots.repo());

    @Test
    void theRecordedBootstrapsAreTheServersAnswers() throws Exception {
        Path dir = REPO.resolve("config/distributions");
        Map<String, ModuleManifests.Entry> known = ModuleManifests.read(com.ash.drishti.api.resolution.ModuleHomes.source(REPO));
        List<String> lenses = lensNames(REPO.resolve("config/lenses"));
        Map<String, List<String>> mapping = EditionEnvironmentPostProcessor.featureMapping(dir, "console");
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        RecordedAnswers rec = RecordedAnswers.under(REPO, "");
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.getFileName().toString().matches("drishti\\.[a-z0-9-]+\\.yaml")).sorted().toList()) {
                Distribution d = Distributions.read(f, dir.resolve(Distributions.KERNEL), com.ash.drishti.api.resolution.ModuleHomes.source(REPO), REPO.resolve("config/packs"));
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("schema", ConsoleAnswers.SCHEMA);
                Map<String, Object> dist = ConsoleDistribution.of(d, REPO.resolve("config/lenses"), lenses, mapping);
                @SuppressWarnings("unchecked")
                Map<String, Object> l = (Map<String, Object>) dist.get("lenses");
                l.put("offered", d.lenses());                    // the file's own; every lens's arrangement is kept for tests that offer others
                l.put("default", d.lenses().contains(String.valueOf(l.get("default"))) ? l.get("default") : "");
                doc.put("distribution", dist);
                List<Map<String, Object>> modules = new ArrayList<>();
                known.forEach((id, k) -> modules.add(ConsoleAnswers.moduleRow(id, k, null, null, null, true)));
                doc.put("modules", modules);
                doc.put("engines", engines());
                doc.put("under-construction", underConstruction());
                doc.put("limits", ConsoleAnswers.limits(com.ash.drishti.engine.shape.BuilderProperties.defaults()));
                doc.put("commands", commands(d.packs()));
                rec.keep("bootstrap-" + d.shortName() + ".json", json.writeValueAsString(doc));
            }
        }
        rec.assertCurrent();
    }

    /** The product's engine catalogue and each module's (banking's jar resource), every engine running. */
    static List<Map<String, Object>> engines() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, com.ash.drishti.server.engines.EngineCatalogue.Entry> all = new LinkedHashMap<>();
        // a module's jar parts are modules/<module>/java/<part>/ (KERNEL_AND_MODULES.md): each part's engines.yaml, in a sorted order
        java.nio.file.PathMatcher jarPart = REPO.getFileSystem().getPathMatcher(
                "glob:modules/*/java/*/src/main/resources/" + com.ash.drishti.server.engines.EngineCatalogue.MODULE_RESOURCE);
        try (Stream<Path> s = Files.walk(REPO.resolve("modules"))) {
            for (Path f : s.filter(p -> jarPart.matches(REPO.relativize(p))).sorted().toList()) {
                try (java.io.InputStream in = Files.newInputStream(f)) {
                    all.putAll(com.ash.drishti.server.engines.EngineCatalogue.moduleEntries(in));
                }
            }
        }
        assertThat(all).as("the modules' engines (modules/<module>/java/<part>/src/main/resources/META-INF/drishti/engines.yaml)").isNotEmpty();
        com.ash.drishti.server.engines.EngineCatalogue.load().all().forEach(e -> all.putIfAbsent(e.id(), e));
        all.values().forEach(e -> out.add(com.ash.drishti.server.engines.EngineCluster.consoleRow(e, true, true)));
        return out;
    }

    /** The Admin pages an administrator's answer lists (GET /api/v1/me/console {@code admin-pages}), for the console's stand-in server. */
    @Test
    void theRecordedAdminPagesAreTheServers() throws Exception {
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        RecordedAnswers rec = RecordedAnswers.under(REPO, "");
        rec.keep("admin-pages.json", json.writeValueAsString(Map.of("admin-pages", ConsoleController.ADMIN_PAGES)));
        rec.assertCurrent();
    }

    /**
     * Every command word of the repository's installation as a server running the distribution answers it: every module on, loaded the
     * packs the distribution names, else the default list ({@link ConsoleAnswers#commands(com.ash.drishti.api.resolution.ConfigResolution, Map)}).
     */
    static Map<String, Object> commands(List<String> named) {
        com.ash.drishti.api.resolution.ConfigResolution r = com.ash.drishti.api.resolution.ConfigResolution.repository(REPO);
        Map<String, Map<String, Object>> loaded = new LinkedHashMap<>();
        for (String name : named.isEmpty() ? com.ash.drishti.packs.distribution.Distributions.defaultPacks(java.nio.file.Path.of(TestRoots.repo(), "config", "distributions", "kernel.yaml"), com.ash.drishti.api.resolution.ModuleHomes.source(java.nio.file.Path.of(TestRoots.repo()))) : named) {
            com.ash.drishti.api.resolution.PackResolver.Located at = r.packs().packs().get(name);
            if (at != null) {
                loaded.put(name, ConsoleAnswers.read(at.dir().resolve("pack.yaml")));
            }
        }
        return ConsoleAnswers.commands(r, loaded);
    }

    /** The shipped under-construction registry as the console draws it. */
    static List<Map<String, Object>> underConstruction() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (com.ash.drishti.rachana.UnderConstruction.Item i : com.ash.drishti.rachana.UnderConstruction.load(REPO.resolve("config/under-construction.yaml")).items()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.id());
            m.put("treatment", i.treatment());
            m.put("menus", i.menus() == null ? List.of() : i.menus());
            m.put("help", i.help() == null ? List.of() : i.help());
            out.add(m);
        }
        return out;
    }

    static List<String> lensNames(Path dir) throws Exception {
        TreeSet<String> out = new TreeSet<>();
        try (Stream<Path> s = Files.walk(dir, 2)) {
            s.filter(p -> p.getFileName().toString().endsWith(".yaml")).forEach(p -> out.add(p.getFileName().toString().replace(".yaml", "")));
        }
        return List.copyOf(out);
    }
}
