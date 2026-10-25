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
package com.ash.drishti.compute.env;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The record of a built environment ({@code drishti-environment.json} in its folder): what it was built from and the sha256 of every file
 * in it. A worker is started only after {@link #verify} finds every file as it was built: a changed, added or removed file stops the worker
 * and names the file (docs/architecture/PYTHON_COMPUTE.md section 5).
 */
public final class Manifest {

    public static final String FILE = "drishti-environment.json";
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private Manifest() {}

    /** Hashes every file under {@code dir} (symbolic links by their target's name) and writes the manifest with {@code info}. */
    public static Map<String, Object> write(Path dir, Map<String, Object> info) throws IOException {
        Map<String, Object> doc = new LinkedHashMap<>(info);
        doc.put("files", files(dir));
        Files.writeString(dir.resolve(FILE), JSON.writeValueAsString(doc));
        return doc;
    }

    /** The manifest of a built environment, or empty when there is none (not built, or a build that did not finish). */
    public static Optional<Map<String, Object>> read(Path dir) {
        Path f = dir.resolve(FILE);
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(f.toFile(), new TypeReference<Map<String, Object>>() {}));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Empty when every file is as built; otherwise what differs, in words, naming the first file found. */
    @SuppressWarnings("unchecked")
    public static Optional<String> verify(Path dir) {
        Optional<Map<String, Object>> m = read(dir);
        if (m.isEmpty()) {
            return Optional.of("the environment " + dir.getFileName() + " has no manifest: it was not built, or its build did not finish");
        }
        Map<String, Object> want = (Map<String, Object>) m.get().getOrDefault("files", Map.of());
        Map<String, String> have;
        try {
            have = files(dir);
        } catch (IOException e) {
            return Optional.of("the environment " + dir.getFileName() + " cannot be read: " + e.getMessage());
        }
        for (Map.Entry<String, Object> e : want.entrySet()) {
            String h = have.get(e.getKey());
            if (h == null) {
                return Optional.of("a file of the environment is missing: " + e.getKey());
            }
            if (!h.equals(e.getValue())) {
                return Optional.of("a file of the environment was changed after it was built: " + e.getKey());
            }
        }
        for (String f : have.keySet()) {
            if (!want.containsKey(f)) {
                return Optional.of("a file was added to the environment after it was built: " + f);
            }
        }
        return Optional.empty();
    }

    private static Map<String, String> files(Path dir) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                if (rel.isEmpty() || rel.equals(FILE)) {
                    continue;
                }
                if (Files.isSymbolicLink(p)) {
                    out.put(rel, "link:" + Files.readSymbolicLink(p));
                } else if (Files.isRegularFile(p)) {
                    out.put(rel, hash(p));
                }
            }
        }
        return out;
    }

    private static String hash(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(p), md)) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
