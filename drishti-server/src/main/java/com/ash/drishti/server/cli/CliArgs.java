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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The parsed command line of {@code sutra <command> <path>... [--junit file] [--out dir] [--kind k] [--samples path] [--strict]}. */
record CliArgs(String command, List<Path> paths, Path junit, Path out, String kind, List<Path> samples, boolean strict) {

    static final Set<String> COMMANDS = Set.of("lint", "test", "shape", "design", "preview");

    /** @throws UsageException when the line is not understood */
    static CliArgs parse(List<String> args) {
        if (args.isEmpty()) {
            throw new UsageException("missing command");
        }
        String command = args.get(0);
        if (!COMMANDS.contains(command)) {
            throw new UsageException("unknown command '" + command + "'");
        }
        List<Path> paths = new ArrayList<>();
        List<Path> samples = new ArrayList<>();
        Path junit = null;
        Path out = null;
        String kind = null;
        boolean strict = false;
        for (int i = 1; i < args.size(); i++) {
            String a = args.get(i);
            switch (a) {
                case "--junit" -> junit = Path.of(value(args, ++i, a));
                case "--out" -> out = Path.of(value(args, ++i, a));
                case "--strict" -> strict = true;
                case "--kind" -> kind = value(args, ++i, a);
                case "--samples" -> samples.add(Path.of(value(args, ++i, a)));
                default -> {
                    if (a.startsWith("--")) {
                        throw new UsageException("unknown option '" + a + "'");
                    }
                    paths.add(Path.of(a));
                }
            }
        }
        if (paths.isEmpty()) {
            throw new UsageException(command + " needs a path");
        }
        return new CliArgs(command, paths, junit, out, kind, samples, strict);
    }

    private static String value(List<String> args, int i, String option) {
        if (i >= args.size()) {
            throw new UsageException(option + " needs a value");
        }
        return args.get(i);
    }

    /** A command line or an {@code expect.yaml} that cannot be understood: exit code 2. */
    static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }
}
