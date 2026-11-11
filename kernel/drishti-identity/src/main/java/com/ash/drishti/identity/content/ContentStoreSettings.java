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
package com.ash.drishti.identity.content;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The content store's settings, read from their own file {@code config/store/store.yaml} (docs/architecture/DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 4;
 * never {@code application.yaml}). A missing file or key means the default: the filesystem backend at {@code <data folder>/fstore}.
 *
 * @param backend {@code filesystem}, {@code sqlite}, {@code postgres} or {@code s3}
 * @param origin the file read, or {@code default}
 */
public record ContentStoreSettings(String backend, Path fsRoot, String jdbcUrl, String user, String password, int pool,
        S3ContentStore.Settings s3, String origin) {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public static ContentStoreSettings load(Path file, Path dataDir, UnaryOperator<String> resolve) {
        if (file == null || !Files.isRegularFile(file)) {
            return from(Map.of(), dataDir, resolve, "default");
        }
        try {
            Map<String, Object> m = YAML.readValue(file.toFile(), new TypeReference<Map<String, Object>>() {});
            return from(m == null ? Map.of() : m, dataDir, resolve, file.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the content store settings " + file + ": " + e.getMessage(), e);
        }
    }

    static ContentStoreSettings from(Map<String, Object> m, Path dataDir, UnaryOperator<String> resolve, String origin) {
        String backend = str(m, "backend", "filesystem", resolve).toLowerCase(Locale.ROOT);
        if (!backend.matches("filesystem|sqlite|postgres|s3")) {
            throw new IllegalArgumentException("store.yaml backend is filesystem, sqlite, postgres or s3, not '" + backend + "'");
        }
        Map<String, Object> fs = map(m, "filesystem");
        String root = str(fs, "root", "", resolve);
        Path fsRoot = (root.isBlank() ? dataDir.resolve("fstore") : Path.of(root)).toAbsolutePath().normalize();
        Map<String, Object> db = map(m, backend.equals("postgres") ? "postgres" : "sqlite");
        String url = str(db, "url", backend.equals("postgres") ? "" : "jdbc:sqlite:" + dataDir.resolve("identity").resolve("drishti.db").toAbsolutePath().normalize(), resolve);
        Map<String, Object> s3 = map(m, "s3");
        S3ContentStore.Settings s = new S3ContentStore.Settings(str(s3, "bucket", "", resolve), str(s3, "prefix", "", resolve),
                str(s3, "region", "", resolve), str(s3, "endpoint", "", resolve), Boolean.parseBoolean(str(s3, "path-style", "false", resolve)),
                str(s3, "access-key", "", resolve), str(s3, "secret-key", "", resolve));
        if (backend.equals("postgres") && url.isBlank()) {
            throw new IllegalArgumentException("store.yaml backend postgres needs postgres.url");
        }
        if (backend.equals("s3") && s.bucket().isBlank()) {
            throw new IllegalArgumentException("store.yaml backend s3 needs s3.bucket");
        }
        return new ContentStoreSettings(backend, fsRoot, url, str(db, "user", "", resolve), str(db, "password", "", resolve),
                Integer.parseInt(str(db, "pool", "4", resolve)), s, origin);
    }

    /** The same settings with another filesystem root ({@code drishti.store.root}, {@code DRISHTI_STORE_ROOT}: overrides the file's). */
    public ContentStoreSettings withFsRoot(Path root) {
        return new ContentStoreSettings(backend, root.toAbsolutePath().normalize(), jdbcUrl, user, password, pool, s3, origin);
    }

    /** Opens the configured store. */
    public ContentStore open() {
        return switch (backend) {
            case "sqlite", "postgres" -> new JdbcContentStore(jdbcUrl, user, password, pool);
            case "s3" -> new S3ContentStore(s3);
            default -> new FileContentStore(fsRoot);
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Map<?, ?> x ? (Map<String, Object>) x : Map.of();
    }

    private static String str(Map<String, Object> m, String k, String fallback, UnaryOperator<String> resolve) {
        Object v = m.get(k);
        String s = v == null ? fallback : resolve.apply(String.valueOf(v)).strip();
        return s.isEmpty() ? fallback : s;
    }
}
