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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The custom-visual SDK's template (docs/guides/DEVELOPER_GUIDE.md, "A module's own visual"; {@code mvn drishti:visual -Dname=<name>}):
 * adds to a module project's {@code src/dmod} a visual type as a Drishti Elements extension. It writes the module's library
 * {@code elements/drishti-<module>/drishti_<module>.js} (once; a second visual is added to it by hand) with a kind {@code <module>.<name>}
 * registered in drishti-chart's registry and built from drishti-chart's own primitives, and declares it under {@code visuals:} of
 * {@code console/plugin.yaml}, so the Elements gallery shows it while the module is loaded. Nothing is drawn by hand and no colour named.
 */
public final class VisualTemplate {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{1,40}");

    private VisualTemplate() {
    }

    /** Adds the visual {@code <module>.<name>}; returns the files written or changed. Refuses a bad name or a kind declared already. */
    public static java.util.List<Path> add(Path dmod, String module, String name, String title) {
        String n = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(n).matches()) {
            throw new IllegalArgumentException("a visual's name is lower-case letters, digits and dashes (lollipop, gene-track): " + name);
        }
        String kind = module + "." + n;
        String lib = "drishti-" + module;
        String file = "drishti_" + module + ".js";
        try {
            Path plugin = dmod.resolve("console/plugin.yaml");
            String yaml = Files.isRegularFile(plugin) ? Files.readString(plugin, StandardCharsets.UTF_8) : "format: 2\n";
            if (yaml.contains("kind: " + kind)) {
                throw new IllegalArgumentException("the visual " + kind + " is declared already in console/plugin.yaml");
            }
            Path js = dmod.resolve("elements").resolve(lib).resolve(file);
            java.util.List<Path> out = new java.util.ArrayList<>();
            if (!Files.exists(js)) {
                Files.createDirectories(js.getParent());
                Files.writeString(js, library(module, kind, title == null || title.isBlank() ? n : title), StandardCharsets.UTF_8);
                out.add(js);
            }
            String entry = "  - kind: " + kind + "\n    library: " + lib + "\n    file: " + file + "\n    title: \"" + (title == null || title.isBlank() ? n : title)
                    + "\"\n    summary: \"What the visual shows (the gallery's text)\"\n";
            yaml = yaml.endsWith("\n") ? yaml : yaml + "\n";
            yaml = yaml.contains("\nvisuals:\n") ? yaml.replace("\nvisuals:\n", "\nvisuals:\n" + entry)
                    : yaml + "visuals:                     # the module's own visual types (kinds of drishti-chart's registry, from its library)\n" + entry;
            Files.createDirectories(plugin.getParent());
            Files.writeString(plugin, yaml, StandardCharsets.UTF_8);
            out.add(plugin);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String library(String module, String kind, String title) {
        return """
                /*
                 * Copyright (c) the publisher of %1$s. All rights reserved.
                 */
                /* drishti-%1$s: the %1$s module's library on Drishti Elements (made by mvn drishti:visual). Served by the console at
                   /m/%1$s/elements/drishti-%1$s/<module version>/ while the module is on, and gone with it. Each kind is registered in
                   drishti-chart's registry and built from drishti-chart's primitives (buildSeriesChart, buildScatter, annotations ...),
                   so it has the card, theme, tooltips, Explain, the data table and export of every chart. No canvas, svg or table of its
                   own, and no colour: tones come from the theme (good, bad, warn, accent, muted). */
                (function (root) {
                  'use strict';
                  const NS = (root.Drishti = root.Drishti || {});
                  const E = (NS.elements = NS.elements || {});
                  const KIND = '%2$s';

                  /** A small spec with data: the gallery and the studio draw it. */
                  function sample() {
                    return { kind: KIND, title: '%3$s (sample)', x: 'label', y: 'value',
                      data: { rows: [{ label: 'A', value: 3 }, { label: 'B', value: 5 }, { label: 'C', value: 2 }] } };
                  }

                  /** spec and rows to what the engine draws: start from a drishti-chart builder and change what the visual needs. */
                  function build(C, spec, rows, env) {
                    const built = C.buildSeriesChart(Object.assign({}, spec, { kind: 'bar' }), rows, env);
                    built.summary = '%3$s: ' + rows.length + ' rows';
                    return built;
                  }

                  function install(C) {
                    if (C.kindOf(KIND)) { return []; }
                    C.registerKind(KIND, { build: (spec, rows, env) => build(C, spec, rows, env), label: '%3$s', family: '%1$s',
                      cartesian: true, sample: sample });
                    return [KIND];
                  }

                  const ready = E.load ? E.load('drishti-chart').then((C) => { install(C); return C; }) : Promise.reject(new Error('drishti-core is not loaded'));
                  E.visuals = E.visuals || {};
                  E.visuals['%1$s'] = { module: '%1$s', library: 'drishti-%1$s', kinds: [KIND], sample: sample, ready: ready };
                }(typeof window !== 'undefined' ? window : globalThis));
                """.formatted(module, kind, title.replace("'", "\\'"));
    }
}
