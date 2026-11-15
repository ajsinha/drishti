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
 * {@code <data>/delta/demo/} (docs/guides/OPERATIONS_GUIDE.md, "The lakes' folders"), before any connector opens a table: {@link LakeRelocate},
 * safe by construction (only the lake's known folders, by a rename, every table measured before and after, moved back on any error, never a
 * delete, recorded once). {@code drishti.lakes.relocate-on-start: false} leaves it to {@code drishti.py lake relocate demo}. A refusal is
 * logged with its reason and the server starts as it is. It runs after the configuration is read and before the connectors
 * are ({@code PackEnvironmentPostProcessor}); it never writes a connector file (docs/guides/DATA_GUIDE.md, "Connector layers").
 */
public final class LakeRelocateOnStart implements EnvironmentPostProcessor, Ordered {

    /** The setting that turns the move at start off. */
    public static final String SWITCH = "drishti.lakes.relocate-on-start";
    /** The lake moved at start. */
    static final String LAKE = "demo";
    private static final System.Logger LOG = System.getLogger(LakeRelocateOnStart.class.getName());

    @Override
    public int getOrder() {
        return org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor.ORDER + 2;   // after the launch roots (+ 1), before the connector files are read
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
            Lakes lakes = Lakes.read(com.ash.drishti.api.Roots.anchored(dir == null || dir.isBlank() ? com.ash.drishti.api.Roots.config("lakes") : dir.strip()), lookup);
            if (lakes.find(LAKE).isEmpty()) {
                return;
            }
            LakeRelocate.Outcome o = LakeRelocate.relocate(lakes, LAKE, false);
            log(o);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "lake " + LAKE + ": the one-time move into its own folder was not tried (" + e.getMessage()
                    + "); drishti.py lake relocate " + LAKE + " says more");
        }
    }

    static void log(LakeRelocate.Outcome o) {
        switch (o.state()) {
            case "moved" -> LOG.log(System.Logger.Level.INFO, "lake " + o.lake() + ": the one-time move into its own folder is done: " + o.message());
            case "nothing", "done-before" -> LOG.log(System.Logger.Level.DEBUG, "lake " + o.lake() + ": " + o.message());
            default -> LOG.log(System.Logger.Level.WARNING, "lake " + o.lake() + ": the one-time move into its own folder " + o.state() + ": " + o.message()
                    + " (the demo connectors read " + o.to() + "; docs/guides/OPERATIONS_GUIDE.md)");
        }
    }
}
