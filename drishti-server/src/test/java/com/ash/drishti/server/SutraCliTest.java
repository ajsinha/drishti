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
import com.ash.drishti.server.cli.CliLauncher;
import com.ash.drishti.server.cli.SutraCli;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The headless {@code sutra} command: exit codes, JUnit XML, HTML snapshots, the {@code packs/<p>/tests/<sutra>/} convention,
 * and `sutra test` over every shipped pack's tests and the documented examples (so a pack's tests run in the build).
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=market-risk,counterparty-risk",
        "drishti.identity.database-url=jdbc:sqlite:target/sutra-cli-${random.uuid}/identity.db",
        "drishti.builder.designs.dir=target/sutra-cli-files-${random.uuid}"})
class SutraCliTest {

    private static final Path ROOT = Path.of("..");
    private static final String SUTRA = """
            rachana: 1
            sutra: widget
            version: 1
            match: { kind: widget }
            title: { pill: Widget, id: $.id }
            panels:
              - id: facts
                kind: kv
                title: Facts
                columns:
                  - { label: Size, bind: $.size }
            """;

    @Autowired SutraRegistry sutras;
    @Autowired ViewPipeline pipeline;
    @Autowired ShapeService shapes;
    @Autowired AutoDesigner designer;
    @Autowired JsonCodec codec;
    @TempDir Path tmp;

    private record Run(int code, String out, String err) {}

    private Run run(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = new SutraCli(new SutraCli.Services(sutras, pipeline, shapes, designer, codec),
                new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8)).run(List.of(args));
        return new Run(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    private Path pack(String sutra, String expect, String... samples) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("pack-" + System.nanoTime()));
        Files.createDirectories(dir.resolve("sutras"));
        Files.writeString(dir.resolve("sutras/widget.v1.sutra.yaml"), sutra);
        Path tests = Files.createDirectories(dir.resolve("tests/widget"));
        for (int i = 0; i < samples.length; i += 2) {
            Files.writeString(tests.resolve(samples[i]), samples[i + 1]);
        }
        if (expect != null) {
            Files.writeString(tests.resolve("expect.yaml"), expect);
        }
        return dir;
    }

    @Test
    void usageErrorsExitTwoAndSayHow() {
        assertThat(run().code()).isEqualTo(2);
        assertThat(run("frobnicate", "x").code()).isEqualTo(2);
        assertThat(run("lint").code()).isEqualTo(2);
        Run r = run("lint", "x", "--bogus");
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("unknown option").contains("exit codes");
        assertThat(run("lint", tmp.resolve("missing").toString()).code()).isEqualTo(2);
    }

    @Test
    void lintExitsZeroForAGoodSutraAndOneWithLocatedProblemsForABadOne() throws IOException {
        Path good = pack(SUTRA, null);
        assertThat(run("lint", good.toString()).code()).isZero();
        Path bad = pack(SUTRA.replace("$.size", "$.size +"), null);
        Run r = run("lint", bad.toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("DRS-2101").contains("widget.v1.sutra.yaml:");
    }

    @Test
    void testRunsTheTestsFolderAndWritesJUnitXml() throws IOException {
        Path ok = pack(SUTRA, "noErrors: true\nnonEmpty: [facts]\n", "a.json", "{\"id\":\"A\",\"size\":3}", "b.json", "{\"id\":\"B\",\"size\":4}");
        Path xml = tmp.resolve("out/junit.xml");
        assertThat(run("test", ok.toString(), "--junit", xml.toString()).code()).isZero();
        String x = Files.readString(xml);
        assertThat(x).contains("<testsuite name=\"widget\" tests=\"2\" failures=\"0\"").contains("name=\"a.json\"").contains("name=\"b.json\"");

        Path empty = pack(SUTRA, "nonEmpty: [facts]\nsamples:\n  b.json: { nonEmpty: [nothing] }\n", "a.json", "{\"id\":\"A\",\"size\":3}", "b.json", "{\"id\":\"B\"}");
        Path xml2 = tmp.resolve("junit2.xml");
        Run r = run("test", empty.toString(), "--junit", xml2.toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("b.json").contains("panel nothing is not in the Sutra");
        assertThat(Files.readString(xml2)).contains("failures=\"1\"").contains("<failure message=");
    }

    @Test
    void anUnreadableSampleStillWritesTheJUnitFileAndFailsAsATestCase() throws IOException {
        Path p = pack(SUTRA, null, "a.json", "{\"id\":\"A\",\"size\":3}", "bad.json", "{not json", "bom.json", "\uFEFF{\"id\":\"B\",\"size\":4}",
                "empty.json", "");
        Path xml = tmp.resolve("junit-bad.xml");
        Run r = run("test", p.toString(), "--junit", xml.toString());
        assertThat(r.code()).isEqualTo(1);
        String x = Files.readString(xml);
        assertThat(x).contains("name=\"bad.json\"").contains("not valid JSON").contains("name=\"empty.json\"").contains("is empty");
        assertThat(x).contains("failures=\"2\"").contains("name=\"bom.json\"").contains("name=\"a.json\"");
        Path xml2 = tmp.resolve("junit-usage.xml");
        assertThat(run("test", "--junit", xml2.toString()).code()).isEqualTo(2);
        assertThat(Files.readString(xml2)).contains("<failure");
        Path xml3 = tmp.resolve("junit-missing.xml");
        assertThat(run("test", tmp.resolve("nope").toString(), "--junit", xml3.toString()).code()).isEqualTo(2);
        assertThat(xml3).exists();
    }

    @Test
    void expectYamlNamingAMissingFileFailsAndJsonlSamplesAreRead() throws IOException {
        Path p = pack(SUTRA, "samples:\n  typo.json: { nonEmpty: [facts] }\n", "a.json", "{\"id\":\"A\",\"size\":3}",
                "many.jsonl", "{\"id\":\"B\",\"size\":1}\n\n{\"id\":\"C\",\"size\":2}\n");
        Path xml = tmp.resolve("junit-expect.xml");
        Run r = run("test", p.toString(), "--junit", xml.toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("typo.json");
        assertThat(Files.readString(xml)).contains("tests=\"4\"").contains("name=\"many.jsonl:2\"");
    }

    @Test
    void testWithNoSamplesIsSkippedNotFailed() throws IOException {
        Path none = pack(SUTRA, null);
        Files.delete(none.resolve("tests/widget"));
        Files.delete(none.resolve("tests"));
        Run r = run("test", none.toString());
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("no samples");
    }

    @Test
    void anUnknownKeyInExpectYamlIsAUsageError() throws IOException {
        Path p = pack(SUTRA, "nonEmpy: [facts]\n", "a.json", "{\"id\":\"A\"}");
        Run r = run("test", p.toString());
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("unknown key 'nonEmpy'");
    }

    @Test
    void previewWritesAnHtmlSnapshotPerSample() throws IOException {
        Path p = pack(SUTRA, null, "a.json", "{\"id\":\"A\",\"size\":3}");
        Path out = tmp.resolve("snaps");
        assertThat(run("preview", p.toString(), "--out", out.toString()).code()).isZero();
        String html = Files.readString(out.resolve("widget--a.html"));
        assertThat(html).contains("<!doctype html>").contains("data-panel=\"facts\"").contains("data-state=\"ok\"");
    }

    @Test
    void shapeAndDesignWorkOnPlainJsonFiles() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("samples"));
        Files.writeString(dir.resolve("1.json"), "{\"id\":\"T1\",\"notional\":100,\"desk\":\"FI\"}");
        Files.writeString(dir.resolve("2.json"), "{\"id\":\"T2\",\"notional\":250,\"desk\":\"FX\"}");
        Run shape = run("shape", dir.toString());
        assertThat(shape.code()).isZero();
        assertThat(shape.out()).contains("notional");
        Path out = tmp.resolve("drafts");
        assertThat(run("design", dir.toString(), "--kind", "deal", "--out", out.toString()).code()).isZero();
        String yaml = Files.readString(out.resolve("deal.sutra.yaml"));
        assertThat(yaml).contains("sutra:").contains("panels:");
        assertThat(run("lint", out.toString()).code()).isZero();            // a drafted Sutra passes the linter
        assertThat(run("shape", tmp.resolve("empty-none").toString()).code()).isEqualTo(2);
        // a kind is a name, never a path: nothing is written outside --out
        for (String bad : new String[] {"../../evil", "bad kind!", "a/b", "x, priority: 9999 }\nzzz: { a"}) {
            Run r = run("design", dir.toString(), "--kind", bad, "--out", out.toString());
            assertThat(r.code()).as(bad).isNotZero();
            assertThat(r.err()).contains("'kind' is letters, digits");
        }
        assertThat(Files.exists(tmp.resolve("evil.sutra.yaml"))).isFalse();
        assertThat(Files.exists(out.resolve("../../evil.sutra.yaml"))).isFalse();
    }

    /** S2-12: the CLI keeps the server's input limit, says what failed in one line, and looks for samples only inside what it was given. */
    @Test
    void theCliRefusesOversizeInputInOneLineAndStaysInsideItsInput() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("big"));
        Files.writeString(dir.resolve("1.json"), "{\"blob\":\"" + "x".repeat(6 * 1024 * 1024) + "\"}");
        Run big = run("shape", dir.toString());
        assertThat(big.code()).isEqualTo(1);
        assertThat(big.err()).contains("max-file-mb").doesNotContain("\tat ").doesNotContain("Exception");
        Path bad = Files.createDirectories(tmp.resolve("bytes"));
        Files.write(bad.resolve("1.json"), new byte[] {(byte) 0xff, (byte) 0xfe, (byte) 0xfd, '{', '}'});
        Run utf = run("shape", bad.toString());
        assertThat(utf.code()).isEqualTo(1);
        assertThat(utf.err()).contains("not valid UTF-8").doesNotContain("Input length");
        Run gone = run("lint", tmp.resolve("nothing-here.sutra.yaml").toString());
        assertThat(gone.err().lines().filter(l -> l.startsWith("sutra:")).findFirst().orElse("")).isNotBlank();
        // a tests folder above the input is not read: only folders inside the paths named on the command line count
        Path outer = Files.createDirectories(tmp.resolve("outer"));
        Files.createDirectories(outer.resolve("tests/widget"));
        Files.writeString(outer.resolve("tests/widget/leak.json"), "{\"id\":\"LEAK\",\"size\":1}");
        Path inner = Files.createDirectories(outer.resolve("pack/sutras/d"));
        Files.writeString(inner.resolve("widget.v1.sutra.yaml"), SUTRA);
        Run r = run("test", inner.toString());
        assertThat(r.out() + r.err()).doesNotContain("leak.json");
    }

    @Test
    void launcherRunsWithoutAWebServerAndExitsWithTheCode() throws IOException {
        Path good = pack(SUTRA, null);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int code = CliLauncher.launch(new String[] {"sutra", "lint", good.toString()}, new PrintStream(o, true), new PrintStream(o, true));
        assertThat(code).isZero();
        assertThat(CliLauncher.isCli(new String[] {"sutra", "lint"})).isTrue();
        assertThat(CliLauncher.isCli(new String[] {"--server.port=1"})).isFalse();
        assertThat(CliLauncher.launch(new String[] {"sutra", "bogus"}, new PrintStream(o, true), new PrintStream(o, true))).isEqualTo(2);
    }

    /** Every shipped pack that has a tests/ folder, and the examples: `sutra test` is green. */
    @Test
    void sutraTestPassesOverEveryShippedPacksTestsAndTheExamples() throws IOException {
        List<String> targets = new ArrayList<>();
        try (Stream<Path> packs = Files.list(ROOT.resolve("packs"))) {
            packs.filter(p -> Files.isDirectory(p.resolve("tests"))).sorted().forEach(p -> targets.add(p.toString()));
        }
        assertThat(targets).as("packs with a tests/ folder").hasSizeGreaterThanOrEqualTo(3);
        targets.add(ROOT.resolve("docs/guides/examples").toString());
        List<String> args = new ArrayList<>(List.of("test"));
        args.addAll(targets);
        Path xml = tmp.resolve("shipped.xml");
        args.addAll(List.of("--junit", xml.toString()));
        Run r = run(args.toArray(String[]::new));
        assertThat(r.err()).isEmpty();
        assertThat(r.code()).isZero();
        assertThat(Files.readString(xml)).doesNotContain("<failure");
        assertThat(r.out()).contains("all-panels-showcase");
    }
}
