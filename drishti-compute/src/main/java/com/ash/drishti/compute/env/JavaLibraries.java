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

import com.ash.drishti.compute.ComputeException;
import com.ash.drishti.compute.ComputeSettings;
import com.ash.drishti.compute.worker.ReadOnly;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Java libraries a connector requires (docs/architecture/PYTHON_COMPUTE.md sections 4 and 10): found by Maven coordinates in the shipped
 * folder or the bank's Maven repository (a folder or an HTTP URL in Maven layout, {@code compute.java-repository}), checked against their
 * sha256, scanned for native code, and staged read-only under {@code <data dir>/compute/jars/<sha256>/}. A jar that carries a native library
 * ({@code .so}, {@code .dll}, {@code .dylib}, {@code .jnilib}) without saying {@code native: true} is refused; one that says it may only run in
 * a Java worker (step C6), never in the server.
 */
public final class JavaLibraries {

    /** A staged library: where it is now and the native files found in it. */
    public record Staged(Requires.JavaLib lib, Path jar, List<String> nativeFiles) {}

    private final ComputeSettings settings;

    public JavaLibraries(ComputeSettings settings) {
        this.settings = settings;
    }

    public Staged stage(Requires.JavaLib lib) {
        Path target = settings.jars().resolve(lib.sha256()).resolve(lib.fileName());
        if (Files.isRegularFile(target) && LockFile.sha256(read(target)).equals(lib.sha256())) {
            return new Staged(lib, target, nativeFiles(target));
        }
        byte[] bytes = fetch(lib);
        String sha = LockFile.sha256(bytes);
        if (!sha.equals(lib.sha256())) {
            throw new ComputeException(ComputeException.Code.REFUSED, lib.coords() + ": sha256 is " + sha + ", the requirement says " + lib.sha256());
        }
        try {
            Path dir = target.getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "staging-", ".jar");
            Files.write(tmp, bytes);
            List<String> natives = nativeFiles(tmp);
            if (!natives.isEmpty() && !lib.nativeCode()) {
                Files.deleteIfExists(tmp);
                throw new ComputeException(ComputeException.Code.REFUSED, lib.coords() + " carries a native library (" + natives.get(0)
                        + ") but does not say native: true; it could only run in a Java worker");
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            ReadOnly.apply(dir);
            return new Staged(lib, target, natives);
        } catch (IOException e) {
            throw new ComputeException(ComputeException.Code.REFUSED, lib.coords() + " could not be staged: " + e.getMessage());
        }
    }

    private byte[] fetch(Requires.JavaLib lib) {
        if ("shipped".equals(lib.source())) {
            Path p = settings.shipped().resolve(lib.fileName());
            if (!Files.isRegularFile(p)) {
                throw new ComputeException(ComputeException.Code.REFUSED, lib.coords() + ": " + lib.fileName() + " is not in the shipped folder " + settings.shipped());
            }
            return read(p);
        }
        String repo = settings.javaRepository();
        if (repo == null || repo.isBlank()) {
            throw new ComputeException(ComputeException.Code.REFUSED, lib.coords() + " comes from the bank's Maven repository, and compute.java-repository is not set");
        }
        String rel = lib.group().replace('.', '/') + "/" + lib.artifact() + "/" + lib.version() + "/" + lib.fileName();
        if (repo.startsWith("http://") || repo.startsWith("https://")) {
            try {
                HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
                HttpResponse<byte[]> r = c.send(HttpRequest.newBuilder(URI.create(repo.replaceAll("/+$", "") + "/" + rel)).timeout(Duration.ofMinutes(5)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() != 200) {
                    throw new ComputeException(ComputeException.Code.REFUSED, lib.coords() + ": the repository answered " + r.statusCode() + " for " + rel);
                }
                return r.body();
            } catch (IOException e) {
                throw new ComputeException(ComputeException.Code.REFUSED, lib.coords() + ": the repository cannot be reached: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ComputeException(ComputeException.Code.CANCELLED, "interrupted");
            }
        }
        Path p = Path.of(repo).resolve(rel);
        if (!Files.isRegularFile(p)) {
            throw new ComputeException(ComputeException.Code.REFUSED, lib.coords() + ": " + rel + " is not in the repository " + repo);
        }
        return read(p);
    }

    /** The native libraries inside a jar (entry names). */
    public static List<String> nativeFiles(Path jar) {
        List<String> out = new ArrayList<>();
        try (InputStream in = Files.newInputStream(jar); ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName().toLowerCase(Locale.ROOT);
                if (!e.isDirectory() && (n.endsWith(".so") || n.matches(".*\\.so\\.\\d+.*") || n.endsWith(".dll") || n.endsWith(".dylib")
                        || n.endsWith(".jnilib"))) {
                    out.add(e.getName());
                }
            }
        } catch (IOException e) {
            throw new ComputeException(ComputeException.Code.REFUSED, jar.getFileName() + " is not a readable jar: " + e.getMessage());
        }
        return out;
    }

    private static byte[] read(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new ComputeException(ComputeException.Code.REFUSED, p + " cannot be read: " + e.getMessage());
        }
    }
}
