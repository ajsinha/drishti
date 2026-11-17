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

import com.ash.drishti.common.TempFiles;
import com.ash.drishti.module.conformance.ModuleConformance;
import com.ash.drishti.module.conformance.VisualTemplate;
import com.ash.drishti.module.runtime.Dmod;
import com.ash.drishti.module.runtime.DmodManifest;
import com.ash.drishti.module.runtime.FirstParty;
import com.ash.drishti.module.runtime.KernelFacts;
import com.ash.drishti.module.runtime.KernelLibraries;
import com.ash.drishti.module.runtime.ModuleCandidate;
import com.ash.drishti.module.runtime.ModuleFolder;
import com.ash.drishti.module.runtime.ModuleRefusedException;
import com.ash.drishti.module.runtime.ModuleVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one Java verifier of a module package, inside the server jar (no Spring, no web server):
 * {@code java -jar drishti-server-...-exec.jar module verify|inspect|visual ...}. The packager is Python ({@code tools/modulepack.py});
 * what only Java can answer is here, because it is the kernel's own code that decides: the package rules and the API ranges (V1, V2, V3, V5,
 * V7, V8: {@link ModuleVerifier}), the origin and the kernel-internals allowance ({@link FirstParty}) and, with {@code --conformance}, the
 * fourteen conformance checks C1-C14 ({@link ModuleConformance}).
 *
 * <pre>
 * verify  FILE.dmod [--conformance] [--with FILE.dmod]... [--project DIR] [--first-party FILE] [--kernel-build ID] [--json]
 * verify  FOLDER --manifest-only [--id ID] [--version V]          the manifest of a folder against a project's id and version
 * inspect FILE.dmod|FOLDER [--first-party FILE] [--kernel-build ID]   what the module is and holds, its origin and its hash list's verdict
 * visual  FOLDER --id ID --name NAME [--title T]                   adds a visual kind (an Elements extension) to a module folder
 * </pre>
 *
 * {@code --with} names a module this one requires (repeat it, or comma-separate): installed beside it for C11, C13 and C14.
 * {@code --first-party} names the first-party list to verify against and {@code --kernel-build} the kernel build to check against (both
 * default to this jar's own; a build that makes the jar passes the list it has just made). Exit codes: 0 verified, 1 refused or a check
 * failed, 2 usage.
 */
public final class ModuleCli {

    private static final String USAGE = """
            usage: java -jar drishti-server-exec.jar module COMMAND PATH [options]
              verify  FILE.dmod [--conformance] [--with FILE.dmod]... [--project DIR] [--json]  the package rules and API ranges; --conformance adds C1-C14
              verify  FOLDER --manifest-only [--id ID] [--version V]                              the manifest of a folder
              inspect FILE.dmod|FOLDER                                                            what the module is and holds, its origin, its hash list
              visual  FOLDER --id ID --name NAME [--title T]                                      add a visual kind to a module folder
            --first-party FILE  the first-party list to verify against; --kernel-build ID  the kernel build to check against
            exit: 0 verified, 1 refused or a check failed, 2 usage""";

    private static final ObjectMapper JSON = new ObjectMapper();

    private ModuleCli() {
    }

    /** For a build that has the jar's classes on its class path and no jar yet (packaging/dist): {@code module verify ...}. */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** True when the command line is {@code module ...}. */
    public static boolean isCli(String[] args) {
        return args.length > 0 && "module".equals(args[0]);
    }

    /** Runs {@code module COMMAND PATH [options]}; returns the exit code. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 3) {
            err.println(USAGE);
            return 2;
        }
        Map<String, List<String>> opts;
        try {
            opts = options(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(USAGE);
            return 2;
        }
        Path path = Path.of(args[2]).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            err.println(path + ": no such file or folder");
            return 2;
        }
        try {
            return switch (args[1]) {
                case "verify" -> verify(path, opts, out, err);
                case "inspect" -> inspect(path, facts(opts), out);
                case "visual" -> visual(path, opts, out, err);
                default -> {
                    err.println("unknown command " + args[1]);
                    err.println(USAGE);
                    yield 2;
                }
            };
        } catch (ModuleRefusedException | IllegalArgumentException | IllegalStateException | UncheckedIOException e) {
            err.println("refused: " + e.getMessage());
            return 1;
        }
    }

    private static final List<String> FLAGS = List.of("--conformance", "--json", "--manifest-only");
    private static final List<String> VALUED = List.of("--with", "--project", "--first-party", "--kernel-build", "--id", "--version", "--name", "--title");

    private static Map<String, List<String>> options(String[] args) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (int i = 3; i < args.length; i++) {
            if (FLAGS.contains(args[i])) {
                out.computeIfAbsent(args[i], k -> new ArrayList<>());
            } else if (VALUED.contains(args[i]) && i + 1 < args.length) {
                out.computeIfAbsent(args[i], k -> new ArrayList<>()).add(args[++i]);
            } else {
                throw new IllegalArgumentException("unknown or incomplete option " + args[i]);
            }
        }
        return out;
    }

    private static String one(Map<String, List<String>> opts, String key) {
        List<String> v = opts.get(key);
        return v == null || v.isEmpty() ? null : v.get(v.size() - 1);
    }

    /** The kernel the checks are for: this jar's own, with the options' build and first-party list. */
    static KernelFacts facts(Map<String, List<String>> opts) {
        KernelFacts k = KernelFacts.ofKernel();
        String list = one(opts, "--first-party");
        return k.withBuild(one(opts, "--kernel-build") != null ? one(opts, "--kernel-build") : k.kernelBuild(),
                list != null ? FirstParty.read(Path.of(list)) : k.firstParty());
    }

    private static List<Path> with(Map<String, List<String>> opts) {
        List<Path> out = new ArrayList<>();
        for (String v : opts.getOrDefault("--with", List.of())) {
            for (String s : v.split(",")) {
                if (!s.isBlank()) {
                    out.add(Path.of(s.strip()).toAbsolutePath());
                }
            }
        }
        return out;
    }

    private static int verify(Path path, Map<String, List<String>> opts, PrintStream out, PrintStream err) {
        boolean json = opts.containsKey("--json");
        if (Files.isDirectory(path)) {
            if (!opts.containsKey("--manifest-only") || opts.containsKey("--conformance")) {
                err.println(path + " is a folder: pack it first (drishti.py module pack) and verify the .dmod, or give --manifest-only");
                return 2;
            }
            List<String> problems = ModuleFolder.validate(path, one(opts, "--id"), one(opts, "--version"));
            if (json) {
                out.println(toJson(Map.of("module", path.toString(), "verified", problems.isEmpty(), "problems", problems)));
            } else {
                problems.forEach(p -> err.println("refused: " + p));
                if (problems.isEmpty()) {
                    out.println("manifest ok: " + ModuleFolder.manifest(path).label());
                }
            }
            return problems.isEmpty() ? 0 : 1;
        }
        Path work = temp();
        try {
            KernelFacts facts = facts(opts);
            Path peek = work.resolve("peek");
            Dmod.unpack(path, peek);
            DmodManifest m = ModuleFolder.manifest(peek);
            Path home = work.resolve("verify").resolve(m.id()).resolve(m.version());      // V1: the folders name the module
            Dmod.unpack(path, home);
            List<String> notes;
            try {
                notes = new ModuleVerifier(facts).verify(new ModuleCandidate(ModuleFolder.manifest(home), home, path));
            } catch (ModuleRefusedException e) {
                if (json) {
                    out.println(toJson(Map.of("module", m.id(), "version", m.version(), "verified", false, "check", e.check(), "reason", e.getMessage())));
                } else {
                    err.println("refused (" + e.check() + "): " + e.getMessage());
                }
                return 1;
            }
            List<ModuleConformance.Check> checks = List.of();
            if (opts.containsKey("--conformance")) {
                Path project = one(opts, "--project") == null ? null : Path.of(one(opts, "--project")).toAbsolutePath();
                checks = ModuleConformance.run(path, project, work.resolve("checks"), facts, with(opts));
            }
            long failed = checks.stream().filter(c -> c.status() == ModuleConformance.Status.FAIL).count();
            // the libraries the module carries that the kernel ships (section 4.4): never used, so named
            List<String> libraryWarnings = notes.stream().filter(n -> n.startsWith(ModuleVerifier.LIBRARY_WARNING))
                    .map(n -> n.substring(ModuleVerifier.LIBRARY_WARNING.length())).toList();
            String librariesLine = facts.libraries().known() ? libraryWarnings.size() + " library warnings (" + facts.libraries().all().size()
                    + " kernel libraries compared)" : "library warnings: not checked (this kernel has no " + KernelLibraries.RESOURCE + ")";
            if (json) {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("module", m.id());
                doc.put("version", m.version());
                doc.put("verified", failed == 0);
                doc.put("notes", notes);
                doc.put("libraryWarnings", facts.libraries().known() ? libraryWarnings : null);
                doc.put("checks", checks.stream().map(c -> Map.of("id", c.id(), "title", c.title(), "passed", c.status() == ModuleConformance.Status.PASS,
                        "problems", c.problems())).toList());
                out.println(toJson(doc));
            } else {
                out.println("verified " + m.label() + " (the package rules and API ranges)");
                notes.forEach(n -> out.println("  " + n));
                out.println("  " + librariesLine);
                checks.forEach(c -> out.println(c.line()));
                if (opts.containsKey("--conformance")) {
                    out.println(failed == 0 ? "all " + checks.size() + " conformance checks passed" : failed + " of " + checks.size() + " checks failed");
                }
            }
            return failed == 0 ? 0 : 1;
        } finally {
            quietDelete(work);
        }
    }

    private static int visual(Path folder, Map<String, List<String>> opts, PrintStream out, PrintStream err) {
        String id = one(opts, "--id");
        String name = one(opts, "--name");
        if (id == null || name == null) {
            err.println("visual needs --id (the module's id) and --name (the kind's name)");
            return 2;
        }
        for (Path p : VisualTemplate.add(folder, id, name, one(opts, "--title"))) {
            out.println("wrote " + p);
        }
        return 0;
    }

    private static int inspect(Path path, KernelFacts facts, PrintStream out) {
        Path work = temp();
        try {
            Path dir = path;
            String hashes = "a folder (no hash list until it is packed)";
            if (!Files.isDirectory(path)) {
                dir = work.resolve("unpacked");
                Dmod.unpack(path, dir);
                List<String> notes = Dmod.verify(dir, path, path.getFileName().toString());
                hashes = "every file matches META-INF/DMOD.MF" + (notes.isEmpty() ? "" : " (" + String.join("; ", notes) + ")");
            }
            DmodManifest m = ModuleFolder.manifest(dir);
            out.println(m.label() + ": " + m.title() + " (" + m.domain() + ")");
            out.println("  about:     " + m.about());
            out.println("  publisher: " + m.publisher());
            out.println("  ranges:    " + m.ranges());
            out.println("  kinds:     " + m.kinds());
            out.println("  java:      " + (m.java() == null ? "none" : m.java().jars() + (m.java().lib().isEmpty() ? "" : " lib " + m.java().lib())
                    + " entry " + m.java().entry() + " spring " + m.java().spring()));
            out.println("  console:   " + (m.console() == null || m.console().isEmpty() ? "none" : m.console()));
            out.println("  functions: " + m.section("functions"));
            List<String> files = new ArrayList<>(Dmod.files(dir));
            FirstParty.Origin origin = facts.firstParty().origin(m, Files.isDirectory(path) ? null : dir);
            out.println("  " + origin.line());
            if (!m.kernelInternals().isEmpty()) {
                String verdict;
                try {
                    FirstParty.allowance(m, origin, facts.kernelBuild());
                    verdict = "honoured on this kernel";
                } catch (ModuleRefusedException e) {
                    verdict = "refused: " + e.getMessage();
                }
                out.println("  allowance: " + m.kernelInternals().size() + " kernel packages, built for kernel " + m.kernelBuild() + " (" + verdict + ")");
            }
            out.println("  files:     " + files.size());
            out.println("  hashes:    " + hashes);
            return 0;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            quietDelete(work);
        }
    }

    private static String toJson(Object o) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(o);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path temp() {
        try {
            return TempFiles.createDir("module-cli");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void quietDelete(Path p) {
        try {
            ModuleFolder.delete(p);
        } catch (IOException e) {
            System.err.println("could not remove " + p + ": " + e.getMessage());
        }
    }
}
