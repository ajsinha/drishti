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
 * The {@code expect.yaml} beside a Sutra's test samples ({@code packs/<p>/tests/<sutra>/expect.yaml}):
 * <pre>
 * noErrors: true                 # no panel in an error state on any sample (default true)
 * nonEmpty: [pnl, limits]        # these panels render with data on every sample
 * samples:                       # optional, per sample file
 *   VAR-COMM.json: { nonEmpty: [pnl] }
 * </pre>
 * Unknown keys are refused so a typo cannot silently pass.
 */
record ExpectFile(boolean noErrors, List<String> nonEmpty, Map<String, List<String>> perSample) {

    static final ExpectFile DEFAULT = new ExpectFile(true, List.of(), Map.of());

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
                default -> throw new CliArgs.UsageException(file + ": unknown key '" + key + "' (noErrors, nonEmpty, samples)");
            }
        }
        return new ExpectFile(noErrors, nonEmpty, per);
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
