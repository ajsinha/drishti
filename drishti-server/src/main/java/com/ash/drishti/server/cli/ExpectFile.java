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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * The {@code expect.yaml} beside a Sutra's test samples ({@code config/packs/<p>/tests/<sutra>/expect.yaml}):
 * <pre>
 * noErrors: true                 # no panel in an error state on any sample (default true)
 * nonEmpty: [pnl, limits]        # these panels render with data on every sample
 * samples:                       # optional, per sample file
 *   VAR-COMM.json: { nonEmpty: [pnl] }
 * help: { coverage: 0.9, about: true }   # share of shown fields with a glossary entry; the page text renders
 * </pre>
 * Unknown keys are refused so a typo cannot silently pass.
 */
record ExpectFile(boolean noErrors, List<String> nonEmpty, Map<String, List<String>> perSample, Double helpCoverage, boolean helpAbout) {

    static final ExpectFile DEFAULT = new ExpectFile(true, List.of(), Map.of(), null, false);

    /** The non-empty panels expected of one sample file (the whole-folder list plus its own). */
    List<String> nonEmptyFor(String sampleFile) {
        List<String> all = new ArrayList<>(nonEmpty);
        all.addAll(perSample.getOrDefault(sampleFile, List.of()));
        return all;
    }

    static ExpectFile load(Path file) {
        Object root;
        try {
            root = new Yaml().load(Files.readString(file));
        } catch (IOException | RuntimeException e) {
            throw new CliArgs.UsageException(file + ": cannot read (" + e.getMessage() + ")");
        }
        if (root == null) {
            return DEFAULT;
        }
        if (!(root instanceof Map<?, ?> m)) {
            throw new CliArgs.UsageException(file + ": expect.yaml must be a mapping");
        }
        boolean noErrors = true;
        List<String> nonEmpty = List.of();
        Map<String, List<String>> per = new LinkedHashMap<>();
        Double coverage = null;
        boolean about = false;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String key = String.valueOf(e.getKey());
            switch (key) {
                case "noErrors" -> {
                    if (!(e.getValue() instanceof Boolean b)) {
                        throw new CliArgs.UsageException(file + ": noErrors must be true or false");
                    }
                    noErrors = b;
                }
                case "nonEmpty" -> nonEmpty = ids(file, "nonEmpty", e.getValue());
                case "help" -> {
                    if (!(e.getValue() instanceof Map<?, ?> h)) {
                        throw new CliArgs.UsageException(file + ": help must be a mapping (coverage, about)");
                    }
                    for (Map.Entry<?, ?> k : h.entrySet()) {
                        switch (String.valueOf(k.getKey())) {
                            case "coverage" -> {
                                if (!(k.getValue() instanceof Number n) || n.doubleValue() < 0 || n.doubleValue() > 1) {
                                    throw new CliArgs.UsageException(file + ": help.coverage must be a number from 0 to 1");
                                }
                                coverage = n.doubleValue();
                            }
                            case "about" -> {
                                if (!(k.getValue() instanceof Boolean b)) {
                                    throw new CliArgs.UsageException(file + ": help.about must be true or false");
                                }
                                about = b;
                            }
                            default -> throw new CliArgs.UsageException(file + ": unknown key '" + k.getKey() + "' under help (coverage, about)");
                        }
                    }
                }
                case "samples" -> {
                    if (!(e.getValue() instanceof Map<?, ?> sm)) {
                        throw new CliArgs.UsageException(file + ": samples must map a file name to its expectations");
                    }
                    for (Map.Entry<?, ?> s : sm.entrySet()) {
                        if (!(s.getValue() instanceof Map<?, ?> one)) {
                            throw new CliArgs.UsageException(file + ": samples." + s.getKey() + " must be a mapping");
                        }
                        for (Object k : one.keySet()) {
                            if (!"nonEmpty".equals(k)) {
                                throw new CliArgs.UsageException(file + ": unknown key '" + k + "' under samples." + s.getKey() + " (only nonEmpty)");
                            }
                        }
                        per.put(String.valueOf(s.getKey()), ids(file, "samples." + s.getKey() + ".nonEmpty", one.get("nonEmpty")));
                    }
                }
                default -> throw new CliArgs.UsageException(file + ": unknown key '" + key + "' (noErrors, nonEmpty, samples, help)");
            }
        }
        return new ExpectFile(noErrors, nonEmpty, per, coverage, about);
    }

    private static List<String> ids(Path file, String where, Object v) {
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> l)) {
            throw new CliArgs.UsageException(file + ": " + where + " must be a list of panel ids");
        }
        return l.stream().map(String::valueOf).toList();
    }
}
