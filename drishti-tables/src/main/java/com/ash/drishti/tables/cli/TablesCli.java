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
package com.ash.drishti.tables.cli;

import com.ash.drishti.tables.Problem;
import com.ash.drishti.tables.catalog.Catalogue;
import com.ash.drishti.tables.catalog.ItemStore;
import com.ash.drishti.tables.catalog.Lineage;
import com.ash.drishti.tables.def.ModelDef;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.migrate.MigrationAudit;
import com.ash.drishti.tables.migrate.TablesMigrator;
import com.ash.drishti.tables.preview.Preview;
import com.ash.drishti.tables.preview.RowEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The table layer's command line, run as {@code java -jar drishti-server-*-exec.jar tables <command>} (no engine, no Spring: definitions
 * and sample files only; the server hands it its DEL translator) and through {@code drishti.py tables} (docs/guides/TABLES_GUIDE.md, docs/guides/CLI_GUIDE.md):
 *
 * <pre>
 * tables list     [--root DIR] [--json]
 * tables check    [--root DIR] [--sample TABLE=FILE.jsonl]... [--json]          exit 1 on an error
 * tables preview  TABLE --sample FILE.jsonl [--limit N] [--root DIR] [--json]
 * tables lineage  NAME [--root DIR] [--json]
 * tables migrate  [--bi DIR] [--root DIR] [--dry-run | --check] [--report FILE] [--audit FILE] [--who NAME] [--moved-to DIR] [--json]
 * </pre>
 *
 * {@code --root} is {@code config/tables} by default and {@code --bi} {@code config/bi}, both under the working folder. Exit codes: 0 done,
 * 1 problems found (or, with {@code --check}, something would change), 2 a usage error.
 */
public final class TablesCli {

    public static final int OK = 0;
    public static final int PROBLEMS = 1;
    public static final int USAGE = 2;

    private static final String HELP = """
            usage: tables list|check|preview|lineage|offer|migrate [options]
              list     [--root DIR] [--json]                                every table and model, with its source and materialisation
              check    [--root DIR] [--sample TABLE=FILE.jsonl]... [--stored NAME,...]... [--json]
                       every definition and the catalogue checked; --sample adds the sample checks; --stored refuses a stored table's name
              preview  TABLE --sample FILE.jsonl [--limit N] [--json]        the table's first rows over sample documents (JSONL, .gz too)
              lineage  NAME [--json]                                         who uses a table or a model
              offer    NAME --sample FILE.jsonl (--kind KIND | --lake LAKE --table TABLE) [--grain '$.LIST[*]'] [--stored NAME,...] [--write]
                       the flat virtual table offered for a new table, columns from the sample; --write accepts it (a new file only)
              migrate  [--bi DIR] [--dry-run|--check] [--report FILE] [--audit FILE] [--who NAME] [--moved-to DIR] [--json]
                       BI's datasets and models into tables and models of the layer, DEL into Rachana (dry run: the diff, nothing written)""";

    private final PrintStream out;
    private final PrintStream err;
    private final Path cwd;
    private final com.ash.drishti.tables.migrate.DelTranslator translator;

    /**
     * @param translator DEL into Rachana for {@code migrate} (the server's, over part A's mapping); null where DEL cannot be read, and
     *     {@code migrate} then says so
     */
    public TablesCli(PrintStream out, PrintStream err, Path cwd, com.ash.drishti.tables.migrate.DelTranslator translator) {
        this.out = out;
        this.err = err;
        this.cwd = cwd;
        this.translator = translator;
    }

    /** Runs one command ({@code args} without the leading {@code tables}). */
    public int run(List<String> args) {
        if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
            out.println(HELP);
            return args.isEmpty() ? USAGE : OK;
        }
        Args a;
        try {
            a = Args.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("tables: " + e.getMessage());
            err.println(HELP);
            return USAGE;
        }
        try {
            return switch (a.command) {
                case "list" -> list(a);
                case "check" -> check(a);
                case "preview" -> preview(a);
                case "lineage" -> lineage(a);
                case "offer" -> offer(a);
                case "migrate" -> migrate(a);
                default -> {
                    err.println("tables: unknown command '" + a.command + "'");
                    err.println(HELP);
                    yield USAGE;
                }
            };
        } catch (IOException | RuntimeException e) {
            err.println("tables " + a.command + ": " + e.getMessage());
            return PROBLEMS;
        }
    }

    private Path root(Args a) {
        return cwd.resolve(a.value("--root", "config/tables"));
    }

    private int list(Args a) throws IOException {
        Catalogue c = Catalogue.load(root(a), ItemStore.NONE);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TableDef t : c.tables()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind", "table");
            r.put("name", t.name());
            r.put("version", t.version());
            r.put("from", t.from() == null ? null : t.from().form().name().toLowerCase(java.util.Locale.ROOT));
            r.put("grain", t.grain());
            r.put("columns", t.columns().size());
            r.put("materialize", t.materialize().mode());
            rows.add(r);
        }
        for (ModelDef m : c.models()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind", "model");
            r.put("name", m.name());
            r.put("version", m.version());
            r.put("tables", m.tables().size());
            r.put("measures", m.measures().size());
            rows.add(r);
        }
        if (a.flag("--json")) {
            json(rows);
        } else {
            rows.forEach(r -> out.println(r.get("kind") + "  " + r.get("name") + "@" + r.get("version") + "  " + describe(r)));
            out.println(c.tables().size() + " table(s), " + c.models().size() + " model(s)");
        }
        return OK;
    }

    private static String describe(Map<String, Object> r) {
        return "table".equals(r.get("kind"))
                ? "from " + r.get("from") + (r.get("grain") == null ? "" : ", grain " + r.get("grain")) + ", " + r.get("columns") + " columns, materialize " + r.get("materialize")
                : r.get("tables") + " tables, " + r.get("measures") + " measures";
    }

    private int check(Args a) throws IOException {
        Catalogue c = Catalogue.load(root(a), ItemStore.NONE);
        List<Problem> problems = new ArrayList<>(c.check());
        problems.addAll(c.clashes(stored(a)));
        for (String s : a.values("--sample")) {
            int eq = s.indexOf('=');
            if (eq < 0) {
                err.println("tables check: --sample TABLE=FILE.jsonl");
                return USAGE;
            }
            String name = s.substring(0, eq);
            Optional<TableDef> t = c.table(name);
            if (t.isEmpty()) {
                problems.add(Problem.error(com.ash.drishti.tables.TableCodes.REFERENCE, "--sample", "no table '" + name + "'"));
                continue;
            }
            List<Preview.Doc> docs = Preview.read(cwd.resolve(s.substring(eq + 1)), Preview.MAX_DOCUMENTS);
            Preview.check(t.get(), docs, RowEvaluator.rachana()).forEach(p -> problems.add(p.in(name + " (sample)")));
        }
        long errors = problems.stream().filter(Problem::isError).count();
        if (a.flag("--json")) {
            json(Map.of("tables", c.tables().size(), "models", c.models().size(), "errors", errors, "problems", problems));
        } else {
            problems.forEach(p -> out.println(p.line()));
            out.println(c.tables().size() + " table(s), " + c.models().size() + " model(s): " + errors + " error(s), "
                    + problems.stream().filter(p -> Problem.WARNING.equals(p.level())).count() + " warning(s)");
        }
        return errors > 0 ? PROBLEMS : OK;
    }

    private int preview(Args a) throws IOException {
        if (a.positional.isEmpty() || a.value("--sample", null) == null) {
            err.println("tables preview: TABLE --sample FILE.jsonl");
            return USAGE;
        }
        Catalogue c = Catalogue.load(root(a), ItemStore.NONE);
        Optional<TableDef> t = c.table(a.positional.get(0));
        if (t.isEmpty()) {
            err.println("tables preview: no table '" + a.positional.get(0) + "' under " + root(a));
            return PROBLEMS;
        }
        if (c.renamed(a.positional.get(0)) != null) {
            err.println("note: '" + a.positional.get(0) + "' is now " + t.get().name());
        }
        int limit = Integer.parseInt(a.value("--limit", "20"));
        Preview.Rows rows = Preview.rows(t.get(), Preview.read(cwd.resolve(a.value("--sample", null)), Preview.MAX_DOCUMENTS), limit, RowEvaluator.rachana());
        if (a.flag("--json")) {
            json(rows);
            return OK;
        }
        out.println(String.join("\t", rows.columns()));
        rows.rows().forEach(r -> out.println(String.join("\t", r.stream().map(v -> v == null ? "" : String.valueOf(v)).toList())));
        out.println(rows.rows().size() + " row(s) from " + rows.documents() + " document(s)");
        rows.problems().forEach(p -> out.println(p.line()));
        return OK;
    }

    private int lineage(Args a) throws IOException {
        if (a.positional.isEmpty()) {
            err.println("tables lineage: NAME");
            return USAGE;
        }
        Catalogue c = Catalogue.load(root(a), ItemStore.NONE);
        String name = a.positional.get(0);
        List<Lineage.Use> uses = new Lineage(c).of(name);
        Optional<ModelDef> m = c.model(com.ash.drishti.tables.def.TableName.Ref.parse(name));
        if (a.flag("--json")) {
            json(Map.of("name", name, "usedBy", uses, "reads", m.map(c::reads).orElse(Map.of())));
            return OK;
        }
        m.ifPresent(x -> c.reads(x).forEach((alias, t) -> out.println("reads  " + alias + "  " + t)));
        uses.forEach(u -> out.println("used by  " + u.kind() + " " + u.name() + ": " + u.how()));
        if (uses.isEmpty() && m.isEmpty()) {
            out.println("nothing in the catalogue uses " + name + " (BI's reports, alerts and Flight add theirs once the switch lands)");
        }
        return OK;
    }

    private int migrate(Args a) throws IOException {
        Path bi = cwd.resolve(a.value("--bi", "config/bi"));
        Path root = root(a);
        if (!Files.isDirectory(bi)) {
            err.println("tables migrate: no BI folder " + bi);
            return USAGE;
        }
        if (translator == null) {
            err.println("tables migrate: run it from the server's exec jar (java -jar drishti-server-*-exec.jar tables migrate), which reads DEL");
            return USAGE;
        }
        TablesMigrator.Plan plan = new TablesMigrator(translator).plan(bi, root);
        String report = TablesMigrator.report(plan);
        if (a.value("--report", null) != null) {
            Files.writeString(cwd.resolve(a.value("--report", null)), report);
        }
        boolean dry = a.flag("--dry-run") || a.flag("--check");
        if (a.flag("--json")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("items", plan.items());
            m.put("changes", plan.changes());
            m.put("writes", plan.writes().keySet());
            m.put("problems", plan.problems());
            m.put("written", dry ? List.of() : TablesMigrator.write(plan, root, bi, movedTo(a), audit(a), a.value("--who", "system")));
            json(m);
        } else if (a.flag("--check")) {
            out.println(plan.anyWrite() ? plan.writes().size() + " file(s) would change: run tables migrate --dry-run to see them" : "nothing to migrate");
        } else if (dry) {
            out.print(plan.diff());
            out.println(report);
        } else {
            List<String> written = TablesMigrator.write(plan, root, bi, movedTo(a), audit(a), a.value("--who", "system"));
            out.println(report);
            out.println(written.size() + " file(s) written under " + root);
        }
        plan.problems().stream().filter(Problem::isError).forEach(p -> err.println(p.line()));
        if (a.flag("--check")) {
            return plan.anyWrite() ? PROBLEMS : OK;
        }
        return plan.count("refused") > 0 ? PROBLEMS : OK;
    }

    private Path movedTo(Args a) {
        String m = a.value("--moved-to", null);
        return m == null ? null : cwd.resolve(m);
    }

    private MigrationAudit audit(Args a) {
        String f = a.value("--audit", null);
        return f == null ? MigrationAudit.NONE : MigrationAudit.jsonl(cwd.resolve(f), Clock.systemUTC());
    }

    private static List<String> stored(Args a) {
        List<String> out = new ArrayList<>();
        a.values("--stored").forEach(v -> java.util.Arrays.stream(v.split(",")).map(String::strip).filter(x -> !x.isEmpty()).forEach(out::add));
        return out;
    }

    /** The flat virtual table offered for a new table (VIRTUAL_TABLES.md section 10, V8): printed, or written with --write. */
    private int offer(Args a) throws IOException {
        if (a.positional.isEmpty() || a.value("--sample", null) == null || a.value("--kind", null) == null && a.value("--table", null) == null) {
            err.println("tables offer: NAME --sample FILE.jsonl (--kind KIND | --lake LAKE --table TABLE)");
            return USAGE;
        }
        String name = a.positional.get(0);
        if (!com.ash.drishti.tables.def.TableName.isTable(name)) {
            err.println("tables offer: '" + name + "' is not <domain>.<name> (lower case, underscores)");
            return USAGE;
        }
        TableDef.Source from = a.value("--kind", null) != null ? TableDef.Source.ofKind(a.value("--kind", null))
                : new TableDef.Source(null, null, null, a.value("--lake", null), a.value("--table", null), null, null, null, null, null, null);
        List<Preview.Doc> docs = Preview.read(cwd.resolve(a.value("--sample", null)), 1_000);
        com.ash.drishti.tables.read.TableOffer.Offer o = com.ash.drishti.tables.read.TableOffer.suggest(name, from, docs);
        String grain = a.value("--grain", null);
        if (grain != null) {
            if (!o.grains().containsKey(grain)) {
                err.println("tables offer: the sample has no list of objects " + grain + "; it has " + o.grains().keySet());
                return USAGE;
            }
            o = com.ash.drishti.tables.read.TableOffer.atGrain(o, grain, docs);
        }
        Catalogue c = Catalogue.load(root(a), ItemStore.NONE);
        String yaml = com.ash.drishti.tables.yaml.TableYaml.write(o.table(), List.of());
        if (a.flag("--json")) {
            json(Map.of("name", name, "documents", o.documents(), "grains", o.grains(), "yaml", yaml, "free", c.free(name, stored(a))));
        } else {
            out.print(yaml);
            out.println("# offered from " + o.documents() + " document(s); grains you may choose instead: "
                    + (o.grains().isEmpty() ? "none" : o.grains().keySet()));
        }
        if (!a.flag("--write")) {
            return OK;
        }
        if (!c.free(name, stored(a))) {
            err.println("tables offer: " + name + " is taken (a virtual table, a was: name or a stored table): choose another name");
            return PROBLEMS;
        }
        Path file = root(a).resolve(com.ash.drishti.tables.def.TableName.domain(name)).resolve(name.substring(name.indexOf('.') + 1) + ".table.yaml");
        if (java.nio.file.Files.exists(file)) {
            err.println("tables offer: " + file + " exists; nothing written");
            return PROBLEMS;
        }
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, yaml);
        out.println("written " + file);
        return OK;
    }


    private void json(Object o) throws IOException {
        out.println(new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(o));
    }

    /** The command line: the command, positional words, {@code --name value} options (repeatable) and flags. */
    static final class Args {
        String command;
        final List<String> positional = new ArrayList<>();
        final Map<String, List<String>> options = new LinkedHashMap<>();

        private static final java.util.Set<String> FLAGS = java.util.Set.of("--json", "--dry-run", "--check", "--write");
        private static final java.util.Set<String> OPTIONS = java.util.Set.of("--root", "--bi", "--sample", "--limit", "--report", "--audit", "--who", "--moved-to",
                "--stored", "--kind", "--lake", "--table", "--grain");

        static Args parse(List<String> args) {
            Args a = new Args();
            a.command = args.get(0);
            for (int i = 1; i < args.size(); i++) {
                String s = args.get(i);
                if (FLAGS.contains(s)) {
                    a.options.computeIfAbsent(s, k -> new ArrayList<>()).add("true");
                } else if (OPTIONS.contains(s)) {
                    if (i + 1 >= args.size()) {
                        throw new IllegalArgumentException(s + " needs a value");
                    }
                    a.options.computeIfAbsent(s, k -> new ArrayList<>()).add(args.get(++i));
                } else if (s.startsWith("--")) {
                    throw new IllegalArgumentException("unknown option " + s);
                } else {
                    a.positional.add(s);
                }
            }
            if (a.flag("--dry-run") && a.flag("--check")) {
                throw new IllegalArgumentException("--dry-run or --check, not both");
            }
            return a;
        }

        boolean flag(String f) {
            return options.containsKey(f);
        }

        String value(String o, String dflt) {
            List<String> v = options.get(o);
            return v == null || v.isEmpty() ? dflt : v.get(v.size() - 1);
        }

        List<String> values(String o) {
            return options.getOrDefault(o, List.of());
        }
    }
}
