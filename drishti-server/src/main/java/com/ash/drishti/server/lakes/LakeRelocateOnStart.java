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
package com.ash.drishti.server.lakes;

import com.ash.drishti.lakeconfig.Lakes;
import com.ash.drishti.lakeload.LakeRelocate;
import java.nio.file.Path;
import java.util.function.Function;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * The one-time move of a demo lake built in the older layout ({@code <data>/delta/<domain>/<table>}) into its own folder
 * {@code <data>/delta/demo/} (docs/admin/OPERATIONS.md, "The lakes' folders"), before any connector opens a table: {@link LakeRelocate},
 * safe by construction (only the lake's known folders, by a rename, every table measured before and after, moved back on any error, never a
 * delete, recorded once). {@code drishti.lakes.relocate-on-start: false} leaves it to {@code drishti.py lake relocate demo}. A refusal is
 * logged with its reason and the server starts as it is. Once the demo lake is in its own folder, the connector files that still read it
 * by its old default root ({@code root: ${DRISHTI_DELTA_ROOT:./data/delta}}, generated before the lake form) are moved to {@code lake: demo}
 * ({@link com.ash.drishti.lakeconfig.LakeFiles#adoptDemo}, each file kept beside it first), so they follow the lake. Both run after the
 * configuration is read and before the packs' connector files are ({@code PackEnvironmentPostProcessor}).
 */
public final class LakeRelocateOnStart implements EnvironmentPostProcessor, Ordered {

    /** The setting that turns the move at start off. */
    public static final String SWITCH = "drishti.lakes.relocate-on-start";
    /** The lake moved at start. */
    static final String LAKE = "demo";
    private static final System.Logger LOG = System.getLogger(LakeRelocateOnStart.class.getName());

    @Override
    public int getOrder() {
        return org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor.ORDER + 1;   // before the connector files are read
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        if (!Boolean.parseBoolean(env.getProperty(SWITCH, "true").trim())) {
            return;
        }
        Function<String, String> lookup = k -> {
            try {
                return env.getProperty(k);
            } catch (IllegalArgumentException e) {
                return null;
            }
        };
        String dir = lookup.apply(Lakes.PROPERTY);
        if (dir == null || dir.isBlank()) {
            dir = lookup.apply(Lakes.ENV);
        }
        try {
            Lakes lakes = Lakes.read(Path.of(dir == null || dir.isBlank() ? Lakes.DEFAULT_DIR : dir.strip()), lookup);
            if (lakes.find(LAKE).isEmpty()) {
                return;
            }
            LakeRelocate.Outcome o = LakeRelocate.relocate(lakes, LAKE, false);
            log(o);
            adopt(env, lakes, o);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "lake " + LAKE + ": the one-time move into its own folder was not tried (" + e.getMessage()
                    + "); drishti.py lake relocate " + LAKE + " says more");
        }
    }

    /** The connector files that read the demo lake by its old default root, moved to {@code lake: demo} once the lake was moved into its own folder. */
    static void adopt(ConfigurableEnvironment env, Lakes lakes, LakeRelocate.Outcome o) {
        if (!o.state().equals("moved") && !o.state().equals("done-before")) {
            return;                                     // not moved (refused, a root of its own, nothing there): the old roots still read it
        }
        String dir = env.getProperty("drishti.sources.connectors-dir", System.getenv().getOrDefault("DRISHTI_CONNECTORS_DIR", "./config/connectors"));
        try {
            com.ash.drishti.lakeconfig.LakeFiles.Migration m = com.ash.drishti.lakeconfig.LakeFiles.adoptDemo(Path.of(dir), lakes.folder() == null
                    ? Path.of(Lakes.DEFAULT_DIR) : lakes.folder(), true);
            m.notes().forEach(n -> LOG.log(System.Logger.Level.INFO, "connector " + n + " (the file before: <name>.yaml.before-lake-<time>.bak)"));
            m.lakeFiles().keySet().forEach(f -> LOG.log(System.Logger.Level.INFO, "lake file " + f + ": the tables those connectors read that it "
                    + "lacked were added at the end of its tables (every other line kept as it was)"));
        } catch (java.io.IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "the connector files that read the demo lake by its old root were not moved to lake: demo ("
                    + e.getMessage() + "); they read " + o.from() + " until they are");
        }
    }

    static void log(LakeRelocate.Outcome o) {
        switch (o.state()) {
            case "moved" -> LOG.log(System.Logger.Level.INFO, "lake " + o.lake() + ": the one-time move into its own folder is done: " + o.message());
            case "nothing", "done-before" -> LOG.log(System.Logger.Level.DEBUG, "lake " + o.lake() + ": " + o.message());
            default -> LOG.log(System.Logger.Level.WARNING, "lake " + o.lake() + ": the one-time move into its own folder " + o.state() + ": " + o.message()
                    + " (the demo connectors read " + o.to() + "; docs/admin/OPERATIONS.md)");
        }
    }
}
