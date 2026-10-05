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
package com.ash.drishti.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.rachana.about.AboutCatalog;
import com.ash.drishti.rachana.about.AboutText;
import com.ash.drishti.rachana.about.GlossaryEntry;
import com.ash.drishti.rachana.about.GlossaryResolver;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.common.JsonCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The about text of the shipped packs (docs/architecture/CONTEXT_HELP.md, step 5): every file loads with no problem, every
 * kind of the QUICKSTART packs (and the trading kinds) has a sentence and a glossary, every sentence renders over every shipped
 * sample with no failed expression, and every guide it points to exists.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=market-risk,counterparty-risk,liquidity-risk,climate-risk,operational-risk,retail-banking,genomics,politics-society,economics",
        "drishti.identity.database-url=jdbc:sqlite:target/aboutpacks-${random.uuid}/identity.db",
        "drishti.builder.designs.dir=target/aboutpacks-files-${random.uuid}"})
class AboutPacksTest {

    /** The packs whose kinds all need text: the QUICKSTART packs, and the trading pack their trades come from. */
    private static final List<String> PACKS = List.of("market-risk", "counterparty-risk", "liquidity-risk", "climate-risk", "operational-risk",
            "retail-banking", "genomics", "politics-society", "economics", "trading");

    private static final Path PACKS_DIR = Path.of("..", "packs");
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Autowired AboutCatalog catalog;
    @Autowired Formats formats;
    @Autowired JsonCodec codec;

    private List<String> kindsOf(String pack) throws IOException {
        List<String> out = new ArrayList<>();
        yaml.readTree(Files.readString(PACKS_DIR.resolve(pack).resolve("pack.yaml"))).path("kinds").forEach(k -> out.add(k.asText()));
        return out;
    }

    @Test
    void everyShippedAboutFileLoadsWithNoProblem() {
        assertThat(catalog.problems()).isEmpty();
    }

    @Test
    void everyKindOfEveryQuickstartPackHasATitleASentenceAndAGlossary() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String pack : PACKS) {
            for (String kind : kindsOf(pack)) {
                AboutText t = catalog.forKind(kind).orElse(null);
                if (t == null || t.title() == null || t.about() == null || t.glossary().isEmpty() || t.guide() == null) {
                    missing.add(pack + "/" + kind);
                }
            }
        }
        // desk-pnl is a derived kind: its fields also get their formula from the definition, but the text is still the pack's
        assertThat(missing).as("kinds without a title, sentence, guide or glossary").isEmpty();
    }

    @Test
    void everySentenceAndPanelNoteRendersOverEverySampleWithNoFailedExpression() throws IOException {
        List<String> failures = new ArrayList<>();
        int rendered = 0;
        for (String pack : PACKS) {
            Path samples = PACKS_DIR.resolve(pack).resolve("samples");
            for (String kind : kindsOf(pack)) {
                AboutText t = catalog.forKind(kind).orElseThrow();
                Path dir = samples.resolve(kind);
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                        DataNode doc = codec.read(Files.readString(f));
                        AboutText.Rendered r = t.render(EvalContext.of(doc, formats), t.panels().keySet(), catalog.maxRendered());
                        rendered++;
                        if (r.errors() > 0 || r.text() == null || r.text().isBlank() || r.text().contains(AboutText.FAILED)
                                || r.panels().values().stream().anyMatch(p -> p.contains(AboutText.FAILED))) {
                            failures.add(pack + "/" + kind + "/" + f.getFileName() + ": " + r.text());
                        }
                    }
                }
            }
        }
        assertThat(rendered).as("documents rendered").isGreaterThan(300);
        assertThat(failures).as("sentences with a failed expression").isEmpty();
    }

    @Test
    void everyGlossaryEntryHasATermAndAMeaningAndResolvesThroughTheResolver() throws IOException {
        for (String pack : PACKS) {
            for (String kind : kindsOf(pack)) {
                AboutText t = catalog.forKind(kind).orElseThrow();
                t.glossary().forEach((key, e) -> assertThat(e.term() != null && e.means() != null).as(pack + "/" + kind + "." + key).isTrue());
            }
        }
        GlossaryResolver resolver = new GlossaryResolver(catalog);
        GlossaryEntry var99 = resolver.resolve("var", "var99", null).orElseThrow();
        assertThat(var99.term()).isEqualTo("Value at risk, 99%, 1 day");
        assertThat(resolver.resolve("trade", "mtm", null).orElseThrow().term()).isEqualTo("Mark to market");
    }

    @Test
    void everyGuideTheTextPointsToExistsInTheHelpCentre() throws IOException {
        Set<String> slugs = new HashSet<>();
        List<Path> helpFiles = new ArrayList<>(List.of(Path.of("..", "console", "config", "help.yaml")));
        try (Stream<Path> packs = Files.list(PACKS_DIR)) {
            packs.map(p -> p.resolve("config").resolve("help.yaml")).filter(Files::isRegularFile).forEach(helpFiles::add);
        }
        for (Path f : helpFiles) {
            JsonNode guides = yaml.readTree(Files.readString(f)).path("guides");
            guides.forEach(g -> slugs.add(g.path("slug").asText()));
        }
        List<String> broken = new ArrayList<>();
        for (String pack : PACKS) {
            for (String kind : kindsOf(pack)) {
                String guide = catalog.forKind(kind).orElseThrow().guide();
                String slug = guide.indexOf('#') < 0 ? guide : guide.substring(0, guide.indexOf('#'));
                if (!slugs.contains(slug)) {
                    broken.add(pack + "/" + kind + " -> " + guide);
                }
            }
        }
        assertThat(broken).as("guides that do not exist").isEmpty();
    }
}
