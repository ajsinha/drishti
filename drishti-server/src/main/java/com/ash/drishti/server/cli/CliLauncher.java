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
                : router.connectorOf(kind, null).flatMap(d -> d.describeField(kind, key))
                        .map(n -> com.ash.drishti.rachana.about.GlossaryEntry.derived(key, n.means(), n.formula(), n.origin()))).isPresent();
    }
}
