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
package com.ash.drishti.compute.worker;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The worker SDK ({@code drishti_compute}, pure Python) shipped inside this jar and extracted to {@code <data dir>/compute/sdk/<hash>/},
 * once per content: the same SDK is reused, a new one gets a new folder, and the folder is made read-only. Workers find it on their
 * module path. Nothing is written to the machine's temporary folder.
 */
public final class Sdk {

    /** The SDK's files, relative to the resource folder {@code drishti-compute-sdk/} (a test checks the list against the sources). */
    public static final List<String> FILES = List.of(
            "drishti_compute/__init__.py",
            "drishti_compute/arrow.py",
            "drishti_compute/engine.py",
            "drishti_compute/guard.py",
            "drishti_compute/worker.py",
            "drishti_compute/samples/__init__.py",
            "drishti_compute/samples/vectors.py");

    private static final String RESOURCES = "drishti-compute-sdk/";

    private Sdk() {}

    /** Extracts the SDK under {@code sdkRoot} (if not there yet) and returns the folder to put on the module path. */
    public static Path install(Path sdkRoot) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (String f : FILES) {
            try (InputStream in = Sdk.class.getClassLoader().getResourceAsStream(RESOURCES + f)) {
                if (in == null) {
                    throw new IllegalStateException("the compute SDK file " + f + " is missing from the drishti-compute jar");
                }
                byte[] b = in.readAllBytes();
                md.update(f.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                md.update(b);
                files.put(f, b);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        String hash = HexFormat.of().formatHex(md.digest(), 0, 8);
        Path dir = sdkRoot.resolve(hash);
        if (Files.isDirectory(dir.resolve("drishti_compute"))) {
            return dir;
        }
        try {
            Files.createDirectories(sdkRoot);
            Path tmp = Files.createTempDirectory(sdkRoot, "extracting-");
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                Path p = tmp.resolve(e.getKey());
                Files.createDirectories(p.getParent());
                Files.write(p, e.getValue());
            }
            try {
                Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException raced) {
                ReadOnly.delete(tmp);                                      // another node or thread extracted it first
                if (!Files.isDirectory(dir)) {
                    throw raced;
                }
            }
            ReadOnly.apply(dir);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("could not extract the compute SDK under " + sdkRoot, e);
        }
    }
}
