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

import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.design.AutoDesigner;
import com.ash.drishti.engine.shape.ShapeService;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.server.DrishtiApplication;
import java.io.PrintStream;
import java.util.List;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Starts the engine for one {@code sutra} command: the application context without a web server (no port is opened, hot
 * reload and the demo ticker are off, logging is quiet), then the {@link SutraCli}, then closes it. Usage errors are decided
 * before anything starts.
 */
public final class CliLauncher {

    private CliLauncher() {}

    /** True when the command line is the CLI's: {@code sutra ...}. */
    public static boolean isCli(String[] args) {
        return args.length > 0 && "sutra".equals(args[0]);
    }

    /** Runs {@code args} (starting with {@code sutra}) and returns the exit code. */
    public static int launch(String[] args, PrintStream out, PrintStream err) {
        List<String> rest = List.of(args).subList(1, args.length);
        try {
            CliArgs.parse(rest);                              // fail fast on a bad command line, without starting the engine
        } catch (CliArgs.UsageException e) {
            return new SutraCli(null, out, err).run(rest);
        }
        java.nio.file.Path scratch;
        try {
            scratch = java.nio.file.Files.createTempDirectory("sutra-cli");   // the CLI keeps nothing: identity, governance and designs go here
        } catch (java.io.IOException e) {
            err.println("sutra: cannot make a scratch directory: " + e.getMessage());
            return SutraCli.PROBLEMS;
        }
        // the packs the command line names are loaded, whatever DRISHTI_PACKS says: a pack is checked with its own About text
        java.util.Map<String, String> was = new java.util.HashMap<>();
        packProperties(rest).forEach((k, v) -> { was.put(k, System.getProperty(k)); System.setProperty(k, v); });
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(DrishtiApplication.class)
                .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false)
                .properties("logging.level.root=WARN", "drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
                        "spring.main.lazy-initialization=true", "drishti.identity.database-url=jdbc:sqlite:" + scratch.resolve("identity.db"),
                        "drishti.governance.dir=" + scratch.resolve("governance"), "drishti.builder.designs.dir=" + scratch.resolve("designs"))
                .run()) {
            SutraCli.Services s = new SutraCli.Services(ctx.getBean(SutraRegistry.class), ctx.getBean(ViewPipeline.class),
                    ctx.getBean(ShapeService.class), ctx.getBean(AutoDesigner.class), ctx.getBean(JsonCodec.class),
                    new HelpChecks(ctx.getBean(com.ash.drishti.rachana.about.AboutCatalog.class), glossaryLookup(ctx),
                            ctx.getBean(com.ash.drishti.rachana.format.Formats.class), ctx.getBean(JsonCodec.class)));
            return new SutraCli(s, out, err).run(rest);
        } catch (RuntimeException | OutOfMemoryError e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            err.println("sutra: the engine could not start or finish: " + (root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage())
                    + (root instanceof OutOfMemoryError ? " (the input is too large)" : "; check DRISHTI_PACKS_DIR and DRISHTI_PACKS"));
            return SutraCli.PROBLEMS;
        } finally {
            was.forEach((k, v) -> { if (v == null) { System.clearProperty(k); } else { System.setProperty(k, v); } });
            try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(scratch)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (java.io.IOException ignored) {
                // a scratch directory left in the temp folder is harmless
            }
        }
    }

    /**
     * The lint's "does this field have an explanation" question, answered by the same resolver as the About drawer: the kind's
     * glossary, the pack's vocabulary, a derived kind's formula, then the core vocabulary.
     */
    private static com.ash.drishti.rachana.about.GlossaryLookup glossaryLookup(ConfigurableApplicationContext ctx) {
        var resolver = new com.ash.drishti.rachana.about.GlossaryResolver(ctx.getBean(com.ash.drishti.rachana.about.AboutCatalog.class));
        var router = ctx.getBean(com.ash.drishti.engine.source.SourceRouter.class);
        return (kind, field) -> resolver.resolve(kind, field, key -> key.indexOf('.') >= 0 ? java.util.Optional.empty()
                : router.describeField(kind, key)
                        .map(n -> com.ash.drishti.rachana.about.GlossaryEntry.derived(key, n.means(), n.formula(), n.origin()))).isPresent();
    }

    /**
     * The packs behind the paths of a command line (a pack folder, or a Sutra file inside one: the nearest folder holding a
     * {@code pack.yaml}), added to the enabled packs, so {@code sutra lint|test config/packs/market-risk} checks market-risk with its
     * About text and glossary even when DRISHTI_PACKS names other packs. A pack outside the packs folder is read from its
     * parent folder (as an installed pack). Empty when no path is in a pack.
     */
    static java.util.Map<String, String> packProperties(List<String> rest) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        java.nio.file.Path outside = null;
        java.nio.file.Path packsDir = java.nio.file.Path.of(System.getProperty("drishti.packs.dir",
                java.util.Objects.requireNonNullElse(System.getenv("DRISHTI_PACKS_DIR"), "./config/packs"))).toAbsolutePath().normalize();
        for (String arg : rest) {
            if (arg.startsWith("-")) {
                continue;
            }
            java.nio.file.Path p = java.nio.file.Path.of(arg).toAbsolutePath().normalize();
            for (java.nio.file.Path d = java.nio.file.Files.isDirectory(p) ? p : p.getParent(); d != null; d = d.getParent()) {
                java.nio.file.Path yaml = d.resolve("pack.yaml");
                if (java.nio.file.Files.isRegularFile(yaml)) {
                    names.add(packName(yaml, d.getFileName().toString()));
                    if (!packsDir.equals(d.getParent()) && outside == null) {
                        outside = d.getParent();
                    }
                    break;
                }
            }
        }
        if (names.isEmpty()) {
            return java.util.Map.of();
        }
        String already = System.getProperty("drishti.packs.enabled", java.util.Objects.requireNonNullElse(System.getenv("DRISHTI_PACKS"), ""));
        java.util.Set<String> all = new java.util.LinkedHashSet<>();
        for (String n : already.split(",")) {
            if (!n.isBlank()) {
                all.add(n.strip());
            }
        }
        all.addAll(names);
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        out.put("drishti.packs.enabled", String.join(",", all));
        // a command-line check never writes the site's connector files (config/connectors): it gets a private folder
        // unless one is named (drishti.sources.connectors-dir or DRISHTI_CONNECTORS_DIR)
        if (System.getProperty("drishti.sources.connectors-dir") == null && System.getenv("DRISHTI_CONNECTORS_DIR") == null) {
            out.put("drishti.sources.connectors-dir", java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                    "drishti-cli-connectors-" + ProcessHandle.current().pid()).toString());
        }
        if (outside != null) {
            out.put("drishti.packs.installed-dir", outside.toString());
        }
        return out;
    }

    private static String packName(java.nio.file.Path yaml, String folder) {
        try {
            for (String line : java.nio.file.Files.readAllLines(yaml)) {
                if (line.startsWith("name:")) {
                    String n = line.substring(5).strip().replace("\"", "").replace("'", "");
                    return n.isEmpty() ? folder : n;
                }
            }
        } catch (java.io.IOException ignored) {
            // the folder's name stands in
        }
        return folder;
    }
}
