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
package com.ash.drishti.server.cli;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.design.AutoDesigner;
import com.ash.drishti.engine.design.Design;
import com.ash.drishti.engine.design.SampleChecker;
import com.ash.drishti.engine.shape.Sample;
import com.ash.drishti.engine.shape.Shape;
import com.ash.drishti.engine.shape.ShapeService;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.rachana.SutraException;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.server.cli.CliReports.Case;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The headless Sutra tool, {@code java -jar drishti-server-*-exec.jar sutra lint|test|shape|design|preview <path>}: the same
 * services the Build workbench uses (the Sutra checker, {@link SampleChecker}, the shape extractor, auto-design and the view
 * pipeline) without a web server. Exit codes: 0 ok, 1 problems found, 2 usage. See docs/guides/SUTRA_CLI.md.
 */
public final class SutraCli {

    public static final int OK = 0;
    public static final int PROBLEMS = 1;
    public static final int USAGE = 2;

    /** The engine services the commands need. */
    public record Services(SutraRegistry sutras, ViewPipeline pipeline, ShapeService shapes, AutoDesigner designer, JsonCodec codec) {}

    /** One Sutra file to work on and where its samples are. */
    private record Unit(Path file, String text, String stem) {}

    /** One sample document: {@code file} is its file name (what {@code expect.yaml} names), {@code expect} the folder's expectations. */
    private record Doc(String file, JsonNode document) {}

    private static final String USAGE_TEXT = """
            usage: sutra <command> <path>... [options]
              lint    <pack-dir | sutra-file>      parse and check every Sutra
              test    <pack-dir | sutra-file>      render each Sutra against its samples and check expect.yaml
              preview <pack-dir | sutra-file>      render samples; --out writes HTML snapshots
              shape   <samples.json | dir>         infer the shape (JSON Schema) of the samples
              design  <samples.json | dir>         draft a Sutra from the samples (--kind names it)
            options: --junit file   JUnit XML report     --out dir   write results here
                     --samples path JSON samples to use instead of the pack's tests/ folder or the Sutra's sibling .json
            exit codes: 0 ok, 1 problems, 2 usage""";

    private final Services s;
    private final PrintStream out;
    private final PrintStream err;
    private final ObjectMapper mapper = new ObjectMapper();

    public SutraCli(Services services, PrintStream out, PrintStream err) {
        this.s = services;
        this.out = out;
        this.err = err;
    }

    /** Runs one command line (without the leading {@code sutra}) and returns the exit code. */
    public int run(List<String> args) {
        try {
            CliArgs a = CliArgs.parse(args);
            List<Case> cases = new ArrayList<>();
            int code = switch (a.command()) {
                case "lint" -> lint(a, cases);
                case "test" -> test(a, cases);
                case "preview" -> preview(a, cases);
                case "shape" -> shape(a);
                default -> design(a);
            };
            if (a.junit() != null) {
                CliReports.junit(a.junit(), cases);
            }
            return code;
        } catch (CliArgs.UsageException e) {
            err.println("sutra: " + e.getMessage());
            err.println(USAGE_TEXT);
            return USAGE;
        } catch (IOException | RuntimeException e) {
            err.println("sutra: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            return PROBLEMS;
        }
    }

    // ------------------------------------------------------------------------------------------------- lint, test, preview

    private int lint(CliArgs a, List<Case> cases) throws IOException {
        boolean bad = false;
        for (Unit u : units(a.paths())) {
            String why = problems(u);
            cases.add(why == null ? Case.pass(u.stem(), "lint") : Case.fail(u.stem(), "lint", why));
            out.println((why == null ? "ok      " : "PROBLEM ") + u.file());
            if (why != null) {
                err.println(why);
                bad = true;
            }
        }
        return bad ? PROBLEMS : OK;
    }

    private int test(CliArgs a, List<Case> cases) throws IOException {
        boolean bad = false;
        for (Unit u : units(a.paths())) {
            String why = problems(u);
            if (why != null) {
                cases.add(Case.fail(u.stem(), "lint", why));
                err.println(why);
                out.println("FAIL " + u.file() + " (does not parse)");
                bad = true;
                continue;
            }
            Sutra sutra = s.sutras().check(u.text());
            Path expectDir = sampleDir(u, sutra);
            List<Doc> docs = samplesOf(u, sutra, a.samples());
            if (docs.isEmpty()) {
                cases.add(Case.skip(u.stem(), "samples", "no samples: add packs/<pack>/tests/" + sutra.name() + "/*.json"));
                out.println("skip " + u.file() + " (no samples)");
                continue;
            }
            ExpectFile expect = expectDir != null && Files.isRegularFile(expectDir.resolve("expect.yaml"))
                    ? ExpectFile.load(expectDir.resolve("expect.yaml")) : ExpectFile.DEFAULT;
            Map<String, ViewModel> views = new LinkedHashMap<>();
            SampleChecker.Matrix m = check(sutra, docs, views);
            int failed = 0;
            for (int i = 0; i < docs.size(); i++) {
                Doc d = docs.get(i);
                List<String> failures = new ArrayList<>();
                SampleChecker.SampleRow row = m.samples().get(i);
                if (expect.noErrors() && !SampleChecker.OK.equals(row.status())) {
                    failures.add("did not render: " + row.message());
                }
                for (SampleChecker.PanelRow p : m.panels()) {
                    SampleChecker.Cell cell = p.cells().get(i);
                    if (expect.noErrors() && SampleChecker.ERROR.equals(cell.status()) && SampleChecker.OK.equals(row.status())) {
                        failures.add("panel " + p.id() + " is in error: " + cell.message());
                    }
                }
                for (String id : expect.nonEmptyFor(d.file())) {
                    SampleChecker.PanelRow p = m.panel(id);
                    if (p == null) {
                        failures.add("panel " + id + " is not in the Sutra");
                    } else if (!SampleChecker.OK.equals(p.cells().get(i).status())) {
                        failures.add("panel " + id + " should have data but is " + p.cells().get(i).status());
                    }
                }
                String name = u.stem() + " / " + d.file();
                if (failures.isEmpty()) {
                    cases.add(Case.pass(u.stem(), d.file()));
                } else {
                    failed++;
                    cases.add(Case.fail(u.stem(), d.file(), String.join("\n", failures)));
                    err.println(name + ": " + String.join("; ", failures));
                }
            }
            out.println((failed == 0 ? "ok   " : "FAIL ") + u.file() + " (" + docs.size() + " sample" + (docs.size() == 1 ? "" : "s") + ")");
            bad |= failed > 0;
        }
        return bad ? PROBLEMS : OK;
    }

    private int preview(CliArgs a, List<Case> cases) throws IOException {
        boolean bad = false;
        for (Unit u : units(a.paths())) {
            String why = problems(u);
            if (why != null) {
                err.println(why);
                cases.add(Case.fail(u.stem(), "lint", why));
                bad = true;
                continue;
            }
            Sutra sutra = s.sutras().check(u.text());
            for (Doc d : samplesOf(u, sutra, a.samples())) {
                ViewModel vm;
                try {
                    vm = render(sutra, d.document());
                } catch (RuntimeException e) {
                    err.println(u.stem() + " / " + d.file() + ": " + e.getMessage());
                    cases.add(Case.fail(u.stem(), d.file(), String.valueOf(e.getMessage())));
                    bad = true;
                    continue;
                }
                long errors = vm.panels().stream().filter(p -> p.error() != null).count();
                out.println(u.stem() + " / " + d.file() + ": " + vm.panels().size() + " panels, " + errors + " in error");
                cases.add(errors == 0 ? Case.pass(u.stem(), d.file()) : Case.fail(u.stem(), d.file(), errors + " panel(s) in error"));
                bad |= errors > 0;
                if (a.out() != null) {
                    Path f = a.out().resolve(u.stem() + "--" + d.file().replaceAll("\\.json$", "") + ".html");
                    CliReports.snapshot(f, u.stem() + " · " + d.file(), vm);
                    out.println("  wrote " + f);
                }
            }
        }
        return bad ? PROBLEMS : OK;
    }

    // ------------------------------------------------------------------------------------------------------ shape, design

    private int shape(CliArgs a) throws IOException {
        List<Sample> samples = jsonSamples(a.paths());
        Shape shape = s.shapes().infer(samples);
        String text = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(shape.schema());
        return emit(a, "shape.json", text);
    }

    private int design(CliArgs a) throws IOException {
        List<Sample> samples = jsonSamples(a.paths());
        Shape shape = s.shapes().infer(samples);
        String kind = a.kind() == null || a.kind().isBlank() ? "sample" : a.kind();
        Object[] last = new Object[2];
        Design d = s.designer().design(shape, samples, kind, (yaml, k, document) -> {
            if (!yaml.equals(last[0])) {
                last[1] = s.sutras().check(yaml);
                last[0] = yaml;
            }
            return render((Sutra) last[1], k, document);
        });
        return emit(a, kind + ".sutra.yaml", d.yaml());
    }

    private int emit(CliArgs a, String fileName, String text) throws IOException {
        if (a.out() == null) {
            out.println(text);
        } else {
            Files.createDirectories(a.out());
            Files.writeString(a.out().resolve(fileName), text + "\n");
            out.println("wrote " + a.out().resolve(fileName));
        }
        return OK;
    }

    // ----------------------------------------------------------------------------------------------------------- helpers

    private SampleChecker.Matrix check(Sutra sutra, List<Doc> docs, Map<String, ViewModel> views) {
        List<SampleChecker.Input> inputs = docs.stream().map(d -> new SampleChecker.Input(d.file(), d.document(), null)).toList();
        return new SampleChecker().check(sutra.panels().stream().map(Panel::id).toList(), inputs, in -> {
            ViewModel vm = render(sutra, in.document());
            views.put(in.name(), vm);
            return vm;
        });
    }

    private ViewModel render(Sutra sutra, JsonNode document) {
        return render(sutra, sutra.match() == null || sutra.match().kind() == null ? "sample" : sutra.match().kind(), document);
    }

    private ViewModel render(Sutra sutra, String kind, JsonNode document) {
        EntityDocument doc = new EntityDocument(EntityRef.of(kind, "SAMPLE"), s.codec().read(document.toString()),
                new Provenance("sutra cli sample JSON", 0, Instant.now(), false));
        return s.pipeline().preview(Optional.of(sutra), doc);
    }

    /** Null when the Sutra parses and checks; else the problems, one per line. */
    private String problems(Unit u) {
        try {
            s.sutras().check(u.text());
            return null;
        } catch (SutraException e) {
            StringBuilder b = new StringBuilder();
            e.problems().forEach(p -> b.append(u.file()).append(p.location() == null ? "" : ":" + p.location().line()).append(' ')
                    .append(p.code()).append(' ').append(p.message()).append('\n'));
            return b.toString().stripTrailing();
        } catch (RuntimeException e) {
            return u.file() + " " + e.getMessage();
        }
    }

    /** The Sutra files under the paths (a file, or a folder searched for {@code *.sutra.yaml}, not looking into {@code tests/}). */
    private List<Unit> units(List<Path> paths) throws IOException {
        List<Unit> found = new ArrayList<>();
        for (Path p : paths) {
            if (Files.isRegularFile(p)) {
                found.add(unit(p));
            } else if (Files.isDirectory(p)) {
                try (Stream<Path> walk = Files.walk(p)) {
                    for (Path f : walk.filter(x -> x.getFileName().toString().endsWith(".sutra.yaml"))
                            .filter(x -> !p.relativize(x).startsWith("tests")).sorted().toList()) {
                        found.add(unit(f));
                    }
                }
            } else {
                throw new CliArgs.UsageException(p + " does not exist");
            }
        }
        if (found.isEmpty()) {
            throw new CliArgs.UsageException("no *.sutra.yaml under " + paths);
        }
        return found;
    }

    private static Unit unit(Path f) throws IOException {
        String n = f.getFileName().toString().replace(".sutra.yaml", "");
        int dot = n.indexOf('.');                                    // var.v1 -> var
        return new Unit(f, Files.readString(f), dot > 0 ? n.substring(0, dot) : n);
    }

    /** The folder {@code tests/<sutra>/} above the Sutra file, if there is one. */
    private static Path sampleDir(Unit u, Sutra sutra) {
        Path dir = u.file().toAbsolutePath().getParent();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            for (String n : List.of(sutra.name(), u.stem())) {
                if (Files.isDirectory(dir.resolve("tests").resolve(n))) {
                    return dir.resolve("tests").resolve(n);
                }
            }
        }
        return null;
    }

    private List<Doc> samplesOf(Unit u, Sutra sutra, List<Path> given) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!given.isEmpty()) {
            files.addAll(jsonFiles(given));
        } else if (sampleDir(u, sutra) != null) {
            files.addAll(jsonFiles(List.of(sampleDir(u, sutra))));
        } else {
            Path sibling = u.file().resolveSibling(u.stem() + ".json");
            if (Files.isRegularFile(sibling)) {
                files.add(sibling);
            }
        }
        List<Doc> docs = new ArrayList<>();
        for (Path f : files) {
            docs.add(new Doc(f.getFileName().toString(), read(f)));
        }
        return docs;
    }

    private List<Sample> jsonSamples(List<Path> paths) throws IOException {
        List<Sample> out = new ArrayList<>();
        for (Path f : jsonFiles(paths)) {
            out.add(new Sample(f.getFileName().toString(), read(f)));
        }
        if (out.isEmpty()) {
            throw new CliArgs.UsageException("no .json samples under " + paths);
        }
        return out;
    }

    private static List<Path> jsonFiles(List<Path> paths) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path p : paths) {
            if (Files.isDirectory(p)) {
                try (Stream<Path> list = Files.list(p)) {
                    list.filter(x -> x.getFileName().toString().endsWith(".json")).sorted().forEach(files::add);
                }
            } else if (Files.isRegularFile(p)) {
                files.add(p);
            } else {
                throw new CliArgs.UsageException(p + " does not exist");
            }
        }
        return files;
    }

    private JsonNode read(Path f) throws IOException {
        try {
            return mapper.readTree(Files.readString(f));
        } catch (IOException e) {
            throw new IOException(f + " is not valid JSON: " + e.getMessage(), e);
        }
    }
}
