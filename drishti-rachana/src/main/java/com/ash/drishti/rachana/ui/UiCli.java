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
package com.ash.drishti.rachana.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code ui check <file>...}: the board check (or, for a chip document, the publish check) on the command line, without
 * starting the server. Run as {@code java -jar drishti-server-*-exec.jar ui check ...}; {@code drishti.py ui check} wraps it.
 * Chips come from {@code config/ui/chips} (seed chips, certified) when that folder exists, and from each {@code --chips DIR}
 * (published); suppliers from {@code --suppliers FILE} (without it the supplier rules are skipped). Exit codes: 0 no
 * errors, 1 errors, 2 usage.
 */
public final class UiCli {

    public static final int OK = 0;
    public static final int PROBLEMS = 1;
    public static final int USAGE = 2;

    static final String USAGE_TEXT = """
            usage: ui check <board-or-chip file>... [options]
              checks a board document (BC-01 to BC-11, warnings BC-W1 to BC-W5) or a chip document (the publish checks CP-01 to CP-05)
            options: --chips DIR        more chips (*.chip.yaml, *.chip.json), as published versions; config/ui/chips is read when present
                     --suppliers FILE   the suppliers as the author sees them (fields, types, masks); without it BC-05's attribute check, BC-08 and BC-W5 are skipped
                     --shared           check as a board shared in a workspace (BC-11: published chips only)
                     --certified-only   the workspace demands certified chips (with --shared)
                     --json             answer as JSON
            exit codes: 0 no errors, 1 errors, 2 usage""";

    private UiCli() {}

    /** True when the command line is {@code ui ...}. */
    public static boolean isCli(String[] args) {
        return args.length > 0 && "ui".equals(args[0]);
    }

    /** Runs {@code args} (starting with {@code ui}) and returns the exit code. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        List<String> files = new ArrayList<>();
        List<Path> chipDirs = new ArrayList<>();
        Path suppliers = null;
        boolean shared = false;
        boolean certified = false;
        boolean json = false;
        if (args.length < 2 || !"check".equals(args[1])) {
            err.println("ui: " + (args.length < 2 ? "give a command" : "unknown command '" + args[1] + "'"));
            err.println(USAGE_TEXT);
            return USAGE;
        }
        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--chips", "--suppliers" -> {
                    if (i + 1 >= args.length) {
                        err.println("ui check: " + a + " needs a value");
                        return USAGE;
                    }
                    if (a.equals("--chips")) {
                        chipDirs.add(Path.of(args[++i]));
                    } else {
                        suppliers = Path.of(args[++i]);
                    }
                }
                case "--shared" -> shared = true;
                case "--certified-only" -> certified = true;
                case "--json" -> json = true;
                case "-h", "--help" -> {
                    out.println(USAGE_TEXT);
                    return OK;
                }
                default -> {
                    if (a.startsWith("-")) {
                        err.println("ui check: unknown option " + a);
                        err.println(USAGE_TEXT);
                        return USAGE;
                    }
                    files.add(a);
                }
            }
        }
        if (files.isEmpty()) {
            err.println("ui check: give the board or chip file to check");
            err.println(USAGE_TEXT);
            return USAGE;
        }
        try {
            ChipCatalog chips = ChipCatalog.empty();
            for (Path d : chipDirs) {
                chips = chips.then(folder(d, ChipState.PUBLISHED));
            }
            Path seeds = Path.of("config", "ui", "chips");
            if (Files.isDirectory(seeds)) {
                chips = chips.then(folder(seeds, ChipState.CERTIFIED));
            }
            BoardCheckContext ctx = new BoardCheckContext(chips, suppliers == null ? null
                    : SupplierCatalog.fromDocument(UiDocuments.tree(Files.readString(suppliers, StandardCharsets.UTF_8))),
                    BoardCheckContext.Limits.DEFAULTS, shared, certified, null, false);
            return check(files, ctx, chips, json, out);
        } catch (IOException e) {
            err.println("ui check: cannot read " + e.getMessage());
            return USAGE;
        } catch (UiDocumentException e) {
            err.println("ui check: " + e.getMessage());
            return USAGE;
        }
    }

    private static int check(List<String> files, BoardCheckContext ctx, ChipCatalog chips, boolean json, PrintStream out) throws IOException {
        ObjectMapper m = new ObjectMapper();
        ArrayNode all = m.createArrayNode();
        boolean failed = false;
        for (String f : files) {
            String text = Files.readString(Path.of(f), StandardCharsets.UTF_8);
            boolean chip;
            String name;
            try {
                var tree = UiDocuments.tree(text);
                chip = UiDocuments.isChip(tree);
                name = String.valueOf(tree.path(chip ? "chip" : "board").asText("?"));
            } catch (UiDocumentException e) {
                chip = false;
                name = "?";
            }
            List<UiProblem> problems = chip ? ChipPublishCheck.check(text, chips) : BoardCheck.check(text, ctx);
            long errors = problems.stream().filter(UiProblem::error).count();
            failed |= errors > 0;
            if (json) {
                ObjectNode o = all.addObject().put("file", f).put("document", chip ? "chip" : "board").put("name", name)
                        .put("ok", errors == 0).put("errors", errors).put("warnings", problems.size() - errors);
                ArrayNode ps = o.putArray("problems");
                for (UiProblem p : problems) {
                    ps.addObject().put("rule", p.rule()).put("level", p.level().name().toLowerCase(java.util.Locale.ROOT)).put("path", p.path())
                            .put("message", p.message()).put("fix", p.fix());
                }
            } else {
                out.println(f + ": " + (chip ? "chip " : "board ") + name + ": " + errors + " error(s), " + (problems.size() - errors) + " warning(s)");
                for (UiProblem p : problems) {
                    out.println("  " + p.rule() + " " + p.level().name().toLowerCase(java.util.Locale.ROOT) + " " + (p.path().isEmpty() ? "(document)" : p.path())
                            + ": " + p.message());
                    if (p.fix() != null) {
                        out.println("      fix: " + p.fix());
                    }
                }
            }
        }
        if (json) {
            out.println(m.writerWithDefaultPrettyPrinter().writeValueAsString(m.createObjectNode().put("ok", !failed).set("files", all)));
        }
        return failed ? PROBLEMS : OK;
    }

    /** The chips of a folder ({@code *.chip.yaml}, {@code *.chip.yml}, {@code *.chip.json}), each in state {@code state}. */
    public static ChipCatalog folder(Path dir, ChipState state) throws IOException {
        Map<ChipRef, ChipCatalog.Entry> m = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) {
            throw new IOException(dir + " (not a folder)");
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.sorted().toList()) {
                String n = f.getFileName().toString();
                if (n.endsWith(".chip.yaml") || n.endsWith(".chip.yml") || n.endsWith(".chip.json")) {
                    UiDocuments.Read<ChipDoc> r = UiDocuments.parseChip(Files.readString(f, StandardCharsets.UTF_8));
                    if (r.doc() != null && r.doc().chip() != null) {
                        m.put(r.doc().ref(), new ChipCatalog.Entry(r.doc(), state, null));
                    }
                }
            }
        }
        return ChipCatalog.of(m);
    }
}
