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

import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.design.AutoDesigner;
import com.ash.drishti.engine.shape.ShapeService;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.about.AboutCatalog;
import com.ash.drishti.rachana.about.AboutProperties;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.server.cli.HelpChecks;
import com.ash.drishti.server.cli.SutraCli;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Pack lint's help warnings ({@code DRS-2045} to {@code DRS-2047}), {@code --strict}, the {@code expect.yaml help:} assertions
 * and the coverage floor of shipped packs (docs/architecture/CONTEXT_HELP.md, step 6).
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=market-risk",
        "drishti.identity.database-url=jdbc:sqlite:target/sutra-cli-help-${random.uuid}/identity.db",
        "drishti.builder.designs.dir=target/sutra-cli-help-files-${random.uuid}"})
class SutraCliHelpTest {

    private static final String SUTRA = """
            rachana: 1
            sutra: widget
            version: 1
            match: { kind: widget }
            title: { pill: Widget, id: $.id }
            keys:
              F1: raw
            strip:
              - { label: Size, bind: $.size }
            panels:
              - id: facts
                kind: kv
                title: Facts
                columns:
                  - { label: Size, bind: $.size }
                  - { label: Colour, bind: $.colour }
            """;

    private static final String ABOUT = """
            about: 1
            kinds:
              widget:
                glossary:
                  size: { term: Size, means: How big the widget is. }
                panels:
                  facts: { about: Facts of the widget. }
                  ghost: { about: No such panel. }
            """;

    @Autowired SutraRegistry sutras;
    @Autowired ViewPipeline pipeline;
    @Autowired ShapeService shapes;
    @Autowired AutoDesigner designer;
    @Autowired JsonCodec codec;
    @Autowired ElCompiler el;
    @Autowired Formats formats;
    @Autowired AboutCatalog shipped;
    @TempDir Path tmp;

    private record Run(int code, String out, String err) {}

    private Run run(AboutCatalog catalog, String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        HelpChecks help = new HelpChecks(catalog, null, formats, codec);
        int code = new SutraCli(new SutraCli.Services(sutras, pipeline, shapes, designer, codec, help),
                new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8)).run(List.of(args));
        return new Run(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    private AboutCatalog widgetCatalog() throws IOException {
        Path file = Files.writeString(tmp.resolve("about.yaml"), ABOUT);
        return new AboutCatalog(new AboutProperties(List.of(new AboutProperties.PackSource("p", "P", file.toString(), null,
                List.of("widget"), List.of("p"))), null, null), el);
    }

    private Path pack(String sutra, String expect) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("pack-" + System.nanoTime()));
        Files.createDirectories(dir.resolve("sutras"));
        Files.writeString(dir.resolve("sutras/widget.v1.sutra.yaml"), sutra);
        Path tests = Files.createDirectories(dir.resolve("tests/widget"));
        Files.writeString(tests.resolve("a.json"), "{\"id\":\"A\",\"size\":3,\"colour\":\"red\"}");
        if (expect != null) {
            Files.writeString(tests.resolve("expect.yaml"), expect);
        }
        return dir;
    }

    @Test
    void lintWarnsOnF1UnknownAboutPanelAndFieldsWithoutAnEntryButStillExitsZero() throws IOException {
        Run r = run(widgetCatalog(), "lint", pack(SUTRA, null).toString());
        assertThat(r.code()).isZero();
        assertThat(r.err()).contains("warning DRS-2045").contains("warning DRS-2046").contains("'ghost'")
                .contains("warning DRS-2047").contains("'colour'").doesNotContain("field 'size'");
    }

    @Test
    void strictTurnsTheWarningsIntoAFailure() throws IOException {
        Path xml = tmp.resolve("lint.xml");
        Run r = run(widgetCatalog(), "lint", pack(SUTRA, null).toString(), "--strict", "--junit", xml.toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(Files.readString(xml)).contains("<failure").contains("DRS-2045");
    }

    @Test
    void aCleanSutraHasNoWarningsEvenWithStrict() throws IOException {
        String clean = SUTRA.replace("keys:\n  F1: raw\n", "").replace("  - { label: Colour, bind: $.colour }\n", "");
        String about = ABOUT.replace("      ghost: { about: No such panel. }\n", "");
        Path file = Files.writeString(tmp.resolve("clean.yaml"), about);
        AboutCatalog c = new AboutCatalog(new AboutProperties(List.of(new AboutProperties.PackSource("p", "P", file.toString(), null,
                List.of("widget"), List.of("p"))), null, null), el);
        Run r = run(c, "lint", pack(clean.replace("            keys:\n              F1: raw\n", ""), null).toString(), "--strict");
        assertThat(r.err()).doesNotContain("warning");
        assertThat(r.code()).isZero();
    }

    @Test
    void testReportsCoverageAndFailsUnderTheExpectFloor() throws IOException {
        AboutCatalog c = widgetCatalog();
        Run low = run(c, "test", pack(SUTRA, "help: { coverage: 1.0 }\n").toString());
        assertThat(low.out()).contains("help coverage 1/2 (50%)");
        assertThat(low.code()).isEqualTo(1);
        assertThat(low.err()).contains("under the floor of 100%").contains("colour");
        Run ok = run(c, "test", pack(SUTRA, "help: { coverage: 0.5, about: true }\n").toString());
        assertThat(ok.code()).isZero();
    }

    @Test
    void helpAboutFailsWhenTheKindHasNoAboutText() throws IOException {
        AboutCatalog empty = new AboutCatalog(new AboutProperties(List.of(), null, null), el);
        Run r = run(empty, "test", pack(SUTRA, "help: { about: true }\n").toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("no about text for kind 'widget'");
    }

    @Test
    void aBadHelpKeyInExpectYamlIsAUsageError() throws IOException {
        Run r = run(widgetCatalog(), "test", pack(SUTRA, "help: { covrage: 0.5 }\n").toString());
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("unknown key 'covrage' under help");
        assertThat(run(widgetCatalog(), "test", pack(SUTRA, "help: { coverage: 2 }\n").toString()).code()).isEqualTo(2);
    }

    @Test
    void theRecordedCoverageFloorOfAShippedPackHolds() throws IOException {
        Path pack = Path.of("..", "packs", "market-risk");
        assertThat(pack.resolve("tests/help-coverage.txt")).exists();
        Run r = run(shipped, "test", pack.toString());
        assertThat(r.err()).isEmpty();
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("help coverage");
    }

    @Test
    void aFallenCoverageUnderTheRatchetFileFails() throws IOException {
        Path dir = pack(SUTRA, null);
        Files.writeString(dir.resolve("tests/help-coverage.txt"), "# floors\nwidget=0.9\n");
        Run r = run(widgetCatalog(), "test", dir.toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("under the floor of 90%");
    }
}
