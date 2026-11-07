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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The Drishti database's settings, {@code drishti.database.*}, kept in their own file {@code config/db/database.yaml} (never
 * application.yaml; docs/architecture/DATA_ARCHITECTURE.md, "The one Drishti database"). Every Drishti-owned relational table lives in
 * this one database: SQLite (the default, one file under the data folder) or PostgreSQL. The server reads the keys through Spring (the
 * file is imported by application.yaml); {@code drishti.py db copy} reads the same file with {@link #load}.
 *
 * @param kind {@code sqlite} or {@code postgres}
 * @param sqliteFile the SQLite file (kind sqlite)
 * @param url the PostgreSQL JDBC URL (kind postgres)
 * @param user the PostgreSQL user
 * @param password the PostgreSQL password (a {@code ${ENV}} placeholder in the file)
 * @param pool the pool keys set for this database (see {@link #POOL_KEYS}); a key not set is the site's {@code drishti.db.pool.*}
 * @param origin where the settings came from, for messages
 */
public record DatabaseSettings(String kind, Path sqliteFile, String url, String user, String password, Map<String, String> pool, String origin) {

    /** The keys' prefix. */
    public static final String PREFIX = "drishti.database.";

    /** The pool keys the database may set for itself, under {@code drishti.database.pool}. */
    public static final List<String> POOL_KEYS = List.of("size", "min-idle", "idle-timeout", "max-lifetime", "connection-timeout",
            "leak-threshold", "statement-timeout");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public DatabaseSettings {
        kind = kind == null || kind.isBlank() ? "sqlite" : kind.strip().toLowerCase(Locale.ROOT);
        if (!kind.equals("sqlite") && !kind.equals("postgres")) {
            throw new IllegalArgumentException(origin + ": drishti.database.kind is sqlite or postgres, not '" + kind + "'");
        }
        if (kind.equals("postgres") && (url == null || !url.startsWith("jdbc:postgresql:"))) {
            throw new IllegalArgumentException(origin + ": drishti.database.kind postgres needs drishti.database.postgres.url "
                    + "(jdbc:postgresql://host:5432/db)");
        }
        user = user == null ? "" : user;
        password = password == null ? "" : password;
        pool = pool == null ? Map.of() : Map.copyOf(pool);
    }

    /** SQLite at {@code file}, the site's pool. */
    public static DatabaseSettings sqlite(Path file) {
        return new DatabaseSettings("sqlite", file.toAbsolutePath().normalize(), null, null, null, null, "sqlite " + file);
    }

    /** PostgreSQL at {@code url}, the site's pool. */
    public static DatabaseSettings postgres(String url, String user, String password) {
        return new DatabaseSettings("postgres", null, url, user, password, null, "postgres");
    }

    /**
     * The settings from keys: {@code lookup} answers a full key ({@code drishti.database.kind}) with its resolved value, or null. A missing
     * key means its default: SQLite at {@code <dataDir>/identity/drishti.db}.
     */
    public static DatabaseSettings from(UnaryOperator<String> lookup, Path dataDir, String origin) {
        String file = blankToNull(lookup.apply(PREFIX + "sqlite.file"));
        Path sqlite = (file == null ? dataDir.resolve("identity").resolve("drishti.db") : Path.of(file)).toAbsolutePath().normalize();
        Map<String, String> pool = new LinkedHashMap<>();
        for (String k : POOL_KEYS) {
            String v = blankToNull(lookup.apply(PREFIX + "pool." + k));
            if (v != null) {
                pool.put(k, v);
            }
        }
        return new DatabaseSettings(lookup.apply(PREFIX + "kind"), sqlite, blankToNull(lookup.apply(PREFIX + "postgres.url")),
                lookup.apply(PREFIX + "postgres.user"), lookup.apply(PREFIX + "postgres.password"), pool, origin);
    }

    /**
     * Reads a database file outside the server ({@code drishti.py db copy}): the layout of {@code config/db/database.yaml}, its
     * {@code ${NAME:default}} placeholders resolved from the environment, and {@code ${drishti.data.dir}} as {@code dataDir}.
     */
    public static DatabaseSettings load(Path file, Path dataDir) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("no database settings file " + file);
        }
        Map<String, Object> root;
        try {
            root = YAML.readValue(file.toFile(), new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the database settings " + file + ": " + e.getMessage(), e);
        }
        Map<String, Object> flat = new HashMap<>();
        flatten("", root == null ? Map.of() : root, flat);
        Placeholders p = new Placeholders(dataDir);
        return from(k -> {
            Object v = flat.get(k);
            return v == null ? null : p.resolve(String.valueOf(v));
        }, dataDir, file.toString());
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> m, Map<String, Object> out) {
        for (Map.Entry<String, Object> e : m.entrySet()) {
            String k = prefix + e.getKey();
            if (e.getValue() instanceof Map<?, ?> sub) {
                flatten(k + ".", (Map<String, Object>) sub, out);
            } else if (e.getValue() != null) {
                out.put(k, e.getValue());
            }
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** True for SQLite, false for PostgreSQL. */
    public boolean sqlite() {
        return kind.equals("sqlite");
    }

    /** The JDBC URL. */
    public String jdbcUrl() {
        return sqlite() ? "jdbc:sqlite:" + sqliteFile : url;
    }

    /** The kind and URL without a password, for logs and messages. */
    public String describe() {
        return kind + " " + jdbcUrl().replaceAll("(?i)(password=)[^&;]*", "$1***");
    }

    /** {@code ${NAME:default}} placeholders, nested defaults too: the environment, then system properties; drishti.data.dir is the data folder. */
    static final class Placeholders {
        private final Path dataDir;

        Placeholders(Path dataDir) {
            this.dataDir = dataDir;
        }

        String resolve(String s) {
            StringBuilder out = new StringBuilder();
            int i = 0;
            while (i < s.length()) {
                int start = s.indexOf("${", i);
                if (start < 0) {
                    out.append(s, i, s.length());
                    break;
                }
                out.append(s, i, start);
                int end = closing(s, start);
                if (end < 0) {
                    out.append(s, start, s.length());
                    break;
                }
                String body = s.substring(start + 2, end);
                int colon = topLevelColon(body);
                String value = lookup(colon < 0 ? body : body.substring(0, colon));
                out.append(value != null ? value : colon < 0 ? "" : resolve(body.substring(colon + 1)));
                i = end + 1;
            }
            return out.toString();
        }

        private static int closing(String s, int start) {
            int depth = 0;
            for (int j = start; j < s.length(); j++) {
                if (s.startsWith("${", j)) {
                    depth++;
                    j++;
                } else if (s.charAt(j) == '}' && --depth == 0) {
                    return j;
                }
            }
            return -1;
        }

        private static int topLevelColon(String body) {
            int depth = 0;
            for (int j = 0; j < body.length(); j++) {
                if (body.startsWith("${", j)) {
                    depth++;
                    j++;
                } else if (body.charAt(j) == '}') {
                    depth--;
                } else if (body.charAt(j) == ':' && depth == 0) {
                    return j;
                }
            }
            return -1;
        }

        private String lookup(String name) {
            if (name.equals("drishti.data.dir")) {
                return dataDir.toString();
            }
            String v = System.getenv(name);
            return v != null ? v : System.getProperty(name);
        }
    }
}
