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
package com.ash.drishti.module.tool;

import com.ash.drishti.module.runtime.Dmod;
import com.ash.drishti.module.runtime.DmodManifest;
import com.ash.drishti.module.runtime.ModuleRefusedException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The module tool, {@code java -jar drishti-module-tool.jar pack|check|inspect <folder or .dmod>}: the packer and the conformance checks
 * without Maven, for any build (Gradle, a script, javac and a folder). The Maven plugin calls the same classes: one implementation.
 *
 * <pre>
 * pack    FOLDER [--jar JAR] [--more JAR,JAR] [--out DIR]   FOLDER is the module in the .dmod layout (module.yaml at its root); writes
 *         DIR/ID-VERSION.dmod; --more puts further jars where module.yaml names them (java.jars under java/, java.lib under lib/)
 * check   FOLDER|DMOD [--jar JAR] [--project DIR] [--cycles N]   the fifteen checks C1-C15 (a folder is packed first, into a temporary folder)
 * inspect FOLDER|DMOD                              the manifest, the parts, the routes, the files and the hash list's verdict
 * </pre>
 *
 * Exit codes: 0 done (every check passed), 1 a check failed or the module was refused, 2 usage.
 */
public final class ModuleTool {

    private static final String USAGE = """
            usage: java -jar drishti-module-tool.jar COMMAND PATH [options]
              pack    FOLDER [--jar JAR] [--more JAR,JAR] [--out DIR]  pack a module folder (module.yaml at its root) into DIR/<id>-<version>.dmod
              check   FOLDER|DMOD [--jar JAR] [--project DIR] [--cycles N]  the conformance checks C1-C15 (default 50 cycles for C15)
              inspect FOLDER|DMOD                                      what the module is and holds
            exit: 0 done, 1 a check failed or the module was refused, 2 usage""";

    private ModuleTool() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Runs one command; returns the exit code. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 2) {
            err.println(USAGE);
            return 2;
        }
        Map<String, String> opts;
        try {
            opts = options(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(USAGE);
            return 2;
        }
        Path path = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            err.println(path + ": no such file or folder");
            return 2;
        }
        Path jar = opts.containsKey("--jar") ? Path.of(opts.get("--jar")).toAbsolutePath() : null;
        try {
            return switch (args[0]) {
                case "pack" -> pack(path, jar, more(opts.get("--more")), Path.of(opts.getOrDefault("--out", ".")).toAbsolutePath(), out, err);
                case "check" -> check(path, jar, opts, out, err);
                case "inspect" -> inspect(path, out);
                default -> {
                    err.println("unknown command " + args[0]);
                    err.println(USAGE);
                    yield 2;
                }
            };
        } catch (ModuleRefusedException | IllegalArgumentException | IllegalStateException | UncheckedIOException e) {
            err.println("refused: " + e.getMessage());
            return 1;
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (int i = 2; i < args.length; i++) {
            if (!List.of("--jar", "--more", "--out", "--project", "--cycles").contains(args[i]) || i + 1 >= args.length) {
                throw new IllegalArgumentException("unknown or incomplete option " + args[i]);
            }
            out.put(args[i], args[++i]);
        }
        return out;
    }

    /** The further jars of {@code --more}: comma-separated paths. */
    private static List<Path> more(String list) {
        if (list == null || list.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(list.split(",")).filter(s -> !s.isBlank()).map(s -> Path.of(s.strip()).toAbsolutePath()).toList();
    }

    private static int pack(Path folder, Path jar, List<Path> more, Path outDir, PrintStream out, PrintStream err) {
        if (!Files.isDirectory(folder)) {
            err.println(folder + " is not a folder: pack takes the module's folder (module.yaml at its root)");
            return 2;
        }
        Path work = temp();
        try {
            Path dmod = ModuleAssembler.assemble(folder, jar, more, work, outDir);
            out.println("packed " + dmod + " (" + size(dmod) + " bytes; the hash of every file in META-INF/DMOD.MF)");
            return 0;
        } finally {
            quietDelete(work);
        }
    }

    private static int check(Path path, Path jar, Map<String, String> opts, PrintStream out, PrintStream err) {
        int cycles;
        try {
            cycles = Integer.parseInt(opts.getOrDefault("--cycles", "50"));
        } catch (NumberFormatException e) {
            err.println("--cycles takes a number");
            return 2;
        }
        Path work = temp();
        try {
            Path dmod = Files.isDirectory(path) ? ModuleAssembler.assemble(path, jar, work.resolve("stage"), work.resolve("dmod")) : path;
            Path project = opts.containsKey("--project") ? Path.of(opts.get("--project")).toAbsolutePath() : null;
            List<ModuleConformance.Check> checks = ModuleConformance.run(dmod, project, work.resolve("checks"), cycles);
            checks.forEach(c -> out.println(c.line()));
            long failed = checks.stream().filter(c -> c.status() == ModuleConformance.Status.FAIL).count();
            out.println(failed == 0 ? "all " + checks.size() + " conformance checks passed" : failed + " of " + checks.size() + " checks failed");
            return failed == 0 ? 0 : 1;
        } finally {
            quietDelete(work);
        }
    }

    private static int inspect(Path path, PrintStream out) {
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
            DmodManifest m = ModuleAssembler.manifest(dir);
            out.println(m.label() + ": " + m.title() + " (" + m.domain() + ")");
            out.println("  about:     " + m.about());
            out.println("  publisher: " + m.publisher());
            out.println("  ranges:    " + m.ranges());
            out.println("  kinds:     " + m.kinds());
            out.println("  java:      " + (m.java() == null ? "none" : m.java().jars() + (m.java().lib().isEmpty() ? "" : " lib " + m.java().lib()) + " entry " + m.java().entry() + " spring " + m.java().spring()));
            out.println("  console:   " + (m.console() == null || m.console().isEmpty() ? "none" : m.console()));
            out.println("  functions: " + m.section("functions"));
            List<String> files = new ArrayList<>(Dmod.files(dir));
            out.println("  files:     " + files.size());
            out.println("  hashes:    " + hashes);
            return 0;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            quietDelete(work);
        }
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1;
        }
    }

    private static Path temp() {
        try {
            // under java.io.tmpdir (the launcher's -Djava.io.tmpdir), a folder of its own, removed when the command ends
            return Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "drishti-module-tool-" + java.util.UUID.randomUUID()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void quietDelete(Path p) {
        try {
            ModuleAssembler.delete(p);
        } catch (IOException e) {
            System.err.println("could not remove " + p + ": " + e.getMessage());
        }
    }
}
