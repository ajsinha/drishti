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
package com.ash.drishti.module.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Two small reads of a module's folder (or of an unpacked module) the verifier and the checks share: its {@code module.yaml}, and the
 * manifest-only verification (the manifest reads, its id and version are a project's, its jar names are jar names). Packing is not here:
 * the one packager is {@link ModulePack}.
 */
public final class ModuleFolder {

    private ModuleFolder() {
    }

    /** Reads {@code module.yaml} of a module's folder (or of an unpacked module). */
    public static DmodManifest manifest(Path folder) {
        Path file = folder.resolve(DmodManifest.FILE);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("there is no " + DmodManifest.FILE + " in " + folder + ": a module's files start with it");
        }
        try (InputStream in = Files.newInputStream(file)) {
            return DmodManifest.read(in, file.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The manifest against a project: it reads, its id and version are the project's ({@code id} and {@code version} may be null), and
     * the jar names of its java block are jar names. The problems in plain words (empty: valid).
     */
    public static List<String> validate(Path source, String id, String version) {
        List<String> out = new ArrayList<>();
        DmodManifest m;
        try {
            m = manifest(source);
        } catch (IllegalArgumentException | ModuleRefusedException e) {
            out.add(e.getMessage());
            return out;
        }
        if (id != null && !id.equals(m.id())) {
            out.add("module.yaml says id " + m.id() + " but the project is " + id + ": make them the same");
        }
        if (version != null && !version.equals(m.version())) {
            out.add("module.yaml says version " + m.version() + " but the project is " + version + ": make them the same");
        }
        if (m.java() != null && m.java().jars().isEmpty()) {
            out.add("java.jars must name at least one jar, the project's own first (third-party jars go in java.lib)");
        }
        if (m.java() != null) {
            for (String j : m.java().classPath()) {
                if (j.contains("..") || j.contains("\\") || !j.endsWith(".jar") || j.indexOf('/') != j.lastIndexOf('/')) {
                    out.add("java: " + j + " is not a jar name (java.jars: file names in java/, java.lib: file names in lib/)");
                }
            }
        }
        return out;
    }

    /** Removes a folder and everything in it (nothing when it is not there). */
    public static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
