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
package com.ash.drishti.identity.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code java -jar drishti-server-*-exec.jar db copy --from A.yaml --to B.yaml [--check] [--drop-columns] [--data-dir DIR] [--json]} (run by
 * {@code drishti.py db copy}): copies the Drishti database described by one settings file (the layout of config/db/database.yaml) into a
 * fresh database described by another, built from the complete schema, with every table's row count verified; {@code --check} reports
 * what would be copied and writes nothing; {@code --drop-columns} lets a copy leave out columns the new schema no longer has. No web
 * server starts.
 * Exit 0 when copied and verified, 1 when the copy failed, 2 for a usage error.
 */
public final class DatabaseCli {

    private final PrintStream out;
    private final PrintStream err;

    public DatabaseCli(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /** True for the arguments this command takes ({@code db ...}). */
    public static boolean isCli(String[] args) {
        return args.length > 0 && "db".equals(args[0]);
    }

    /** Runs {@code args} (after {@code db}). */
    public int run(List<String> args) {
        if (args.isEmpty() || !args.get(0).equals("copy")) {
            err.println("usage: db copy --from SETTINGS.yaml --to SETTINGS.yaml [--check] [--drop-columns] [--data-dir DIR] [--json]");
            return 2;
        }
        Path from = null;
        Path to = null;
        Path data = Path.of("data");
        boolean json = false;
        boolean check = false;
        boolean drop = false;
        for (int i = 1; i < args.size(); i++) {
            String a = args.get(i);
            switch (a) {
                case "--from", "--to", "--data-dir" -> {
                    if (i + 1 >= args.size()) {
                        err.println(a + " needs a value");
                        return 2;
                    }
                    Path p = Path.of(args.get(++i));
                    if (a.equals("--from")) {
                        from = p;
                    } else if (a.equals("--to")) {
                        to = p;
                    } else {
                        data = p;
                    }
                }
                case "--json" -> json = true;
                case "--check" -> check = true;
                case "--drop-columns" -> drop = true;
                default -> {
                    err.println("unknown argument " + a);
                    return 2;
                }
            }
        }
        if (from == null || to == null) {
            err.println("db copy needs --from and --to (each a settings file in the layout of config/db/database.yaml)");
            return 2;
        }
        Path dataDir = data.toAbsolutePath().normalize();
        try {
            DatabaseSettings a = DatabaseSettings.load(from, dataDir);
            DatabaseSettings b = DatabaseSettings.load(to, dataDir);
            DatabaseCopy.Report r = new DatabaseCopy(k -> null).copy(a, b, check, drop);
            if (json) {
                out.println(new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(DatabaseCopy.json(r)));
            } else {
                DatabaseCopy.lines(r).forEach(out::println);
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("db copy: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            err.println("db copy failed: " + e.getMessage());
            return 1;
        }
    }
}
