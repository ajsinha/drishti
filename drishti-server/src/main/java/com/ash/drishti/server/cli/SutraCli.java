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
 * pipeline) without a web server. Exit codes: 0 ok, 1 problems found, 2 usage. See docs/guides/SUTRA_DEVELOPER_GUIDE.md#testing.
 */
public final class SutraCli {

    public static final int OK = 0;
    public static final int PROBLEMS = 1;
    public static final int USAGE = 2;

    /** The engine services the commands need. */
    public record Services(SutraRegistry sutras, ViewPipeline pipeline, ShapeService shapes, AutoDesigner designer, JsonCodec codec,
            HelpChecks help) {

        /** Services without the help checks (no about catalogue): lint and test then skip the help warnings and assertions. */
        public Services(SutraRegistry sutras, ViewPipeline pipeline, ShapeService shapes, AutoDesigner designer, JsonCodec codec) {
            this(sutras, pipeline, shapes, designer, codec, null);
        }
    }

    /** One Sutra file to work on and where its samples are. */
    private record Unit(Path file, String text, String stem) {}

    /** One sample document: {@code file} is its file name (what {@code expect.yaml} names), {@code expect} the folder's expectations. */
    /** One sample document; {@code error} is set (and {@code document} null) when the file could not be read as JSON. */
    private record Doc(String file, JsonNode document, String error, String source) {}

    private static final String USAGE_TEXT = """
            usage: sutra <command> <path>... [options]
              lint    <pack-dir | sutra-file>      parse and check every Sutra
              test    <pack-dir | sutra-file>      render each Sutra against its samples and check expect.yaml
              preview <pack-dir | sutra-file>      render samples; --out writes HTML snapshots
              shape   <samples.json | dir>         infer the shape (JSON Schema) of the samples
              design  <samples.json | dir>         draft a Sutra from the samples (--kind names it)
              design --each <dir>                  one Sutra per subfolder of .json samples, in one run: <out>/<subfolder>.sutra.yaml;
                                                   the kind is --kind, else the subfolder's one-line 'kind' file, else the folder name
            options: --junit file   JUnit XML report     --out dir   write results here
                     --strict       lint: help warnings (DRS-2045 to 2047) fail the run
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

    /** The paths the command line named, absolute: sample folders are looked for inside them only. */
    private List<Path> roots = List.of();

    /** Runs one command line (without the leading {@code sutra}) and returns the exit code. */
    public int run(List<String> args) {
        List<Case> cases = new ArrayList<>();
        int code;
        try {
            CliArgs a = CliArgs.parse(args);
            roots = a.paths().stream().map(p -> p.toAbsolutePath().normalize()).toList();
            code = switch (a.command()) {
                case "lint" -> lint(a, cases);
                case "test" -> test(a, cases);
                case "preview" -> preview(a, cases);
                case "shape" -> shape(a);
                default -> design(a);
            };
        } catch (CliArgs.UsageException e) {
            err.println("sutra: " + e.getMessage());
            err.println(USAGE_TEXT);
            cases.add(Case.fail("sutra", "usage", e.getMessage()));
            code = USAGE;
        } catch (IOException | RuntimeException e) {
            String why = describe(e);
            err.println("sutra: " + why);
            cases.add(Case.fail("sutra", "run", why));
            code = PROBLEMS;
        } catch (OutOfMemoryError | StackOverflowError e) {
            String why = "the input is too large or too deeply nested to process (" + e.getClass().getSimpleName() + "); split it, or use smaller samples";
            err.println("sutra: " + why);
            cases.add(Case.fail("sutra", "run", why));
            code = PROBLEMS;
        }
        // The report is written on every path, so a CI "publish test report" step finds a file after a failure too.
        Path junit = junitPath(args);
        if (junit != null) {
            try {
                CliReports.junit(junit, cases);
            } catch (IOException | RuntimeException e) {
                err.println("sutra: cannot write " + junit + ": " + e.getMessage());
                return code == OK ? PROBLEMS : code;
            }
        }
        return code;
    }

    /** The value of {@code --junit}, read from the raw line so it is found even when the rest of the line is refused. */
    private static Path junitPath(List<String> args) {
        for (int i = 0; i + 1 < args.size(); i++) {
            if ("--junit".equals(args.get(i))) {
                return Path.of(args.get(i + 1));
            }
        }
        return null;
    }

    /** One line that says what failed, never a stack trace: a missing or unreadable file by name, text that is not UTF-8 as such. */
    static String describe(Exception e) {
        if (e instanceof java.nio.file.NoSuchFileException n) {
            return "no such file: " + n.getFile();
        }
        if (e instanceof java.nio.file.AccessDeniedException n) {
            return "not allowed to use " + n.getFile();
        }
        if (e instanceof java.nio.charset.CharacterCodingException) {
            return "a file is not valid UTF-8 text";
        }
        if (e instanceof java.nio.file.FileSystemException f) {
            return "cannot use " + f.getFile() + (f.getReason() == null ? "" : ": " + f.getReason());
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    // ------------------------------------------------------------------------------------------------- lint, test, preview

    private int lint(CliArgs a, List<Case> cases) throws IOException {
        boolean bad = false;
        List<Unit> units = units(a.paths());
        List<Sutra> parsed = new ArrayList<>();
        for (Unit u : units) {
            if (problems(u) == null) {
                parsed.add(s.sutras().check(u.text()));
            }
        }
        for (Unit u : units) {
            String why = problems(u);
            cases.add(why == null ? Case.pass(u.stem(), "lint") : Case.fail(u.stem(), "lint", why));
            out.println((why == null ? "ok      " : "PROBLEM ") + u.file());
            if (why != null) {
                err.println(why);
                bad = true;
                continue;
            }
            bad |= helpWarnings(u, s.sutras().check(u.text()), parsed, a, cases);
        }
        return bad ? PROBLEMS : OK;
    }

    /** The help warnings (DRS-2045 to 2047) of one Sutra; true when they fail the run (only with {@code --strict}). */
    private boolean helpWarnings(Unit u, Sutra sutra, List<Sutra> run, CliArgs a, List<Case> cases) throws IOException {
        if (s.help() == null) {
            return false;
        }
        String kind = sutra.match() == null ? null : sutra.match().kind();
        List<com.ash.drishti.rachana.SutraProblem> warnings = new ArrayList<>(
                s.help().warnings(sutra, run, kind == null ? List.of() : s.sutras().forKind(kind)));
        List<JsonNode> docs = samplesOf(u, sutra, a.samples()).stream().filter(d -> d.error() == null).map(Doc::document).toList();
        warnings.addAll(s.help().uncovered(s.help().coverage(sutra, docs), sutra));
        for (com.ash.drishti.rachana.SutraProblem w : warnings) {
            String line = u.file() + (w.location() == null ? "" : ":" + w.location().line()) + " warning " + w.code() + " " + w.message();
            err.println(line);
            if (a.strict()) {
                cases.add(Case.fail(u.stem(), "help " + w.code(), line));
            }
        }
        return a.strict() && !warnings.isEmpty();
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
                cases.add(Case.skip(u.stem(), "samples", "no samples: add config/packs/<pack>/tests/" + sutra.name() + "/*.json"));
                out.println("skip " + u.file() + " (no samples)");
                continue;
            }
            ExpectFile expect = expectDir != null && Files.isRegularFile(expectDir.resolve("expect.yaml"))
                    ? ExpectFile.load(expectDir.resolve("expect.yaml")) : ExpectFile.DEFAULT;
            int failed = 0;
            for (String named : expect.perSample().keySet()) {
                if (docs.stream().noneMatch(d -> named.equals(d.source()) || named.equals(d.file()))) {
                    failed++;
                    String missing = "expect.yaml names " + named + ", which is not a sample file of this Sutra";
                    cases.add(Case.fail(u.stem(), "expect.yaml", missing));
                    err.println(u.stem() + ": " + missing);
                }
            }
            for (Doc d : docs) {
                if (d.error() != null) {
                    failed++;
                    cases.add(Case.fail(u.stem(), d.file(), d.error()));
                    err.println(u.stem() + " / " + d.file() + ": " + d.error());
                }
            }
            List<Doc> readable = docs.stream().filter(d -> d.error() == null).toList();
            Map<String, ViewModel> views = new LinkedHashMap<>();
            SampleChecker.Matrix m = readable.isEmpty() ? null : check(sutra, readable, views);
            for (int i = 0; i < readable.size(); i++) {
                Doc d = readable.get(i);
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
                for (String id : expect.nonEmptyFor(d.source())) {
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
            failed += helpExpectations(u, sutra, expect, expectDir, readable, cases);
            out.println((failed == 0 ? "ok   " : "FAIL ") + u.file() + " (" + docs.size() + " sample" + (docs.size() == 1 ? "" : "s") + ")");
            bad |= failed > 0;
        }
        return bad ? PROBLEMS : OK;
    }

    /** The {@code help:} assertions of expect.yaml; always reports the coverage line when there is an about catalogue. Returns the failures. */
    private int helpExpectations(Unit u, Sutra sutra, ExpectFile expect, Path expectDir, List<Doc> readable, List<Case> cases) {
        if (s.help() == null) {
            return 0;
        }
        int failed = 0;
        com.ash.drishti.rachana.about.HelpLint.Coverage c = s.help().coverage(sutra, readable.stream().map(Doc::document).toList());
        out.println("     " + u.stem() + ": " + c.describe());
        Double floor = expect.helpCoverage() != null ? expect.helpCoverage() : ratchet(expectDir, u.stem(), sutra.name());
        if (floor != null) {
            if (c.ratio() + 1e-9 < floor) {
                failed++;
                String why = c.describe() + " is under the floor of " + Math.round(floor * 100) + "% (no glossary entry for: "
                        + String.join(", ", c.missing()) + ")";
                cases.add(Case.fail(u.stem(), "help coverage", why));
                err.println(u.stem() + ": " + why);
            } else {
                cases.add(Case.pass(u.stem(), "help coverage"));
            }
        }
        if (expect.helpAbout()) {
            List<String> errors = new ArrayList<>();
            for (Doc d : readable) {
                String e = s.help().aboutError(sutra, d.document());
                if (e != null) {
                    errors.add(d.file() + ": " + e);
                }
            }
            if (errors.isEmpty()) {
                cases.add(Case.pass(u.stem(), "help about"));
            } else {
                failed++;
                cases.add(Case.fail(u.stem(), "help about", String.join("\n", errors)));
                err.println(u.stem() + ": help about: " + String.join("; ", errors));
            }
        }
        return failed;
    }

    /**
     * The recorded floor of the pack ({@code tests/help-coverage.txt}, lines {@code <sutra>=<0..1>}), which a Sutra's coverage may
     * not fall under: a ratchet, so help text is never lost quietly. Null when there is no file or no line for the Sutra.
     */
    private static Double ratchet(Path expectDir, String stem, String name) {
        Path file = expectDir == null ? null : expectDir.resolveSibling("help-coverage.txt");
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                String[] kv = line.strip().split("=", 2);
                if (kv.length == 2 && !line.startsWith("#") && (kv[0].strip().equals(stem) || kv[0].strip().equals(name))) {
                    return Double.parseDouble(kv[1].strip());
                }
            }
        } catch (IOException | NumberFormatException e) {
            throw new CliArgs.UsageException(file + ": cannot read (" + e.getMessage() + "); lines are <sutra>=<0..1>");
        }
        return null;
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
                if (d.error() != null) {
                    err.println(u.stem() + " / " + d.file() + ": " + d.error());
                    cases.add(Case.fail(u.stem(), d.file(), d.error()));
                    bad = true;
                    continue;
                }
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
        if (a.each()) {
            return designEach(a);
        }
        List<Sample> samples = jsonSamples(a.paths());
        String kind = a.kind() == null || a.kind().isBlank() ? "sample" : a.kind();
        return emit(a, kind + ".sutra.yaml", draft(samples, kind));
    }

    /** {@code design --each <dir>}: every immediate subfolder holding .json samples gets its own draft, in this one JVM. */
    private int designEach(CliArgs a) throws IOException {
        int drafted = 0;
        for (Path root : a.paths()) {
            if (!Files.isDirectory(root)) {
                throw new CliArgs.UsageException(root + " is not a directory");
            }
            List<Path> subs;
            try (Stream<Path> list = Files.list(root)) {
                subs = list.filter(Files::isDirectory).sorted().toList();
            }
            for (Path sub : subs) {
                if (jsonFiles(List.of(sub), false).isEmpty()) {
                    continue;
                }
                String name = sub.getFileName().toString();
                String kind = a.kind() != null && !a.kind().isBlank() ? a.kind() : name;
                Path kindFile = sub.resolve("kind");
                if ((a.kind() == null || a.kind().isBlank()) && Files.isRegularFile(kindFile)) {
                    kind = Files.readString(kindFile).strip();
                }
                emit(a, name + ".sutra.yaml", draft(jsonSamples(List.of(sub)), kind));
                drafted++;
            }
        }
        if (drafted == 0) {
            throw new CliArgs.UsageException("no subfolder with .json samples under " + a.paths());
        }
        return OK;
    }

    private String draft(List<Sample> samples, String kind) {
        Shape shape = s.shapes().infer(samples);
        Object[] last = new Object[2];
        Design d = s.designer().design(shape, samples, kind, (yaml, k, document) -> {
            if (!yaml.equals(last[0])) {
                last[1] = s.sutras().check(yaml);
                last[0] = yaml;
            }
            return render((Sutra) last[1], k, document);
        });
        return d.yaml();
    }

    private int emit(CliArgs a, String fileName, String text) throws IOException {
        String body = text.endsWith("\n") ? text : text + "\n";
        if (a.out() == null) {
            out.print(body);
        } else {
            Files.createDirectories(a.out());
            Files.writeString(a.out().resolve(fileName), body);
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

    private Unit unit(Path f) throws IOException {
        String n = f.getFileName().toString().replace(".sutra.yaml", "");
        int dot = n.indexOf('.');                                    // var.v1 -> var
        return new Unit(f, text(f), dot > 0 ? n.substring(0, dot) : n);
    }

    /** A file's text, refused past the builder's single-document limit (drishti.builder.max-file-mb): the CLI is no way round the server's limits. */
    private String text(Path f) throws IOException {
        long max = s.shapes().limits().maxFileBytes();
        if (Files.size(f) > max) {
            throw new IOException(f.getFileName() + " is over " + s.shapes().limits().maxFileMb() + " MB (drishti.builder.max-file-mb)");
        }
        try {
            return Files.readString(f);
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IOException(f.getFileName() + " is not valid UTF-8 text", e);
        }
    }

    /** The folder {@code tests/<sutra>/} above the Sutra file, if there is one inside what the command line named (never above it). */
    private Path sampleDir(Unit u, Sutra sutra) {
        Path dir = u.file().toAbsolutePath().normalize().getParent();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            for (String n : List.of(sutra.name(), u.stem())) {
                Path found = dir.resolve("tests").resolve(n);
                if (Files.isDirectory(found) && roots.stream().anyMatch(found::startsWith)) {
                    return found;
                }
            }
        }
        return null;
    }

    private List<Doc> samplesOf(Unit u, Sutra sutra, List<Path> given) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!given.isEmpty()) {
            files.addAll(jsonFiles(given, true));
        } else if (sampleDir(u, sutra) != null) {
            files.addAll(jsonFiles(List.of(sampleDir(u, sutra)), true));
        } else {
            Path sibling = u.file().resolveSibling(u.stem() + ".json");
            if (Files.isRegularFile(sibling)) {
                files.add(sibling);
            }
        }
        List<Doc> docs = new ArrayList<>();
        for (Path f : files) {
            docs.addAll(readDocs(f));
        }
        return docs;
    }

    /** The documents of one sample file: one for {@code .json}, one per non-blank line for {@code .jsonl}; unreadable ones carry the error. */
    private List<Doc> readDocs(Path f) {
        String name = f.getFileName().toString();
        String text;
        try {
            text = Files.readString(f);
        } catch (IOException e) {
            return List.of(new Doc(name, null, name + " cannot be read as text: " + e.getMessage(), name));
        }
        text = stripBom(text);
        boolean lines = name.toLowerCase(java.util.Locale.ROOT).endsWith(".jsonl");
        List<Doc> out = new ArrayList<>();
        if (!lines) {
            out.add(parse(name, name, text));
            return out;
        }
        int n = 0;
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                out.add(parse(name + ":" + (++n), name, line));
            }
        }
        if (out.isEmpty()) {
            out.add(new Doc(name, null, name + " is empty: a sample must hold a JSON document", name));
        }
        return out;
    }

    private Doc parse(String name, String source, String text) {
        if (text.isBlank()) {
            return new Doc(name, null, name + " is empty: a sample must hold a JSON document", source);
        }
        try {
            JsonNode n = mapper.readTree(text);
            if (n == null || n.isMissingNode()) {
                return new Doc(name, null, name + " is empty: a sample must hold a JSON document", source);
            }
            return new Doc(name, n, null, source);
        } catch (IOException e) {
            return new Doc(name, null, name + " is not valid JSON: " + e.getMessage(), source);
        }
    }

    private static String stripBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private List<Sample> jsonSamples(List<Path> paths) throws IOException {
        List<Sample> out = new ArrayList<>();
        for (Path f : jsonFiles(paths, false)) {
            out.add(new Sample(f.getFileName().toString(), read(f)));
        }
        if (out.isEmpty()) {
            throw new CliArgs.UsageException("no .json samples under " + paths);
        }
        return out;
    }

    private static List<Path> jsonFiles(List<Path> paths, boolean lines) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path p : paths) {
            if (Files.isDirectory(p)) {
                try (Stream<Path> list = Files.list(p)) {
                    list.filter(x -> x.getFileName().toString().endsWith(".json") || lines && x.getFileName().toString().endsWith(".jsonl")).sorted().forEach(files::add);
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
            return mapper.readTree(stripBom(text(f)));
        } catch (IOException e) {
            throw new IOException(f + " is not valid JSON: " + e.getMessage(), e);
        }
    }
}
