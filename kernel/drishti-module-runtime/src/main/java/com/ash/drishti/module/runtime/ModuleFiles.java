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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * The file work of {@link ModuleBoot}, kept apart so the boot reads as the rules it applies: the SHA-256 of a {@code .dmod}, its
 * manifest read straight from the zip, the unpack into the hash-addressed cache folder ({@code <cache>/<id>/<version>-<hash16>/}) through
 * a {@code .part-<pid>} folder and one atomic move, and the sweep of every cache folder no present package owns.
 *
 * <p>Every zip is closed before the method returns, so an operator can replace a {@code .dmod} while the server runs (Windows holds an open
 * file). A folder that cannot be deleted (Windows: a loaded module's jar is held open) is noted in words and skipped; it never fails a start.
 * The {@code .part} folder is made inside the cache root, so the atomic move never crosses volumes.
 */
final class ModuleFiles {

    /** A {@code .part-<pid>} folder younger than this may belong to another process unpacking now (two nodes, one data folder): kept. */
    static final Duration PART_GRACE = Duration.ofHours(1);

    /** The infix of a folder being unpacked: {@code <version>-<hash16>.part-<pid>}. */
    static final String PART = ".part-";

    private ModuleFiles() {
    }

    /** The SHA-256 of a file, lower-case hex. */
    static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return Dmod.sha256(in);
        } catch (IOException e) {
            throw new UncheckedIOException(file + " does not read", e);
        }
    }

    /** The manifest of a package, read from its {@code module.yaml} entry without unpacking; refuses (V1) a package without one. */
    static DmodManifest manifest(Path dmod) {
        try (ZipFile zip = new ZipFile(dmod.toFile())) {
            var entry = zip.getEntry(DmodManifest.FILE);
            if (entry == null) {
                throw new ModuleRefusedException("V1", dmod.getFileName() + ": holds no " + DmodManifest.FILE);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return DmodManifest.read(in, dmod.getFileName().toString());
            }
        } catch (IOException e) {
            throw new ModuleRefusedException("V1", dmod.getFileName() + ": does not read as a package (" + e.getMessage() + ")", e);
        }
    }

    /**
     * Unpacks a package into its cache folder: into {@code <target>.part-<pid>} beside it, then one atomic move. When another process won
     * the race the winner's folder is used and this process's copy deleted. Returns the notes (a {@code .part} left behind).
     */
    static List<String> unpack(Path dmod, Path target) {
        List<String> notes = new ArrayList<>();
        Path part = target.resolveSibling(target.getFileName() + PART + ProcessHandle.current().pid());
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(part)) {
                delete(part, notes);
            }
            Files.createDirectories(part);
            Dmod.unpack(dmod, part);
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException | DirectoryNotEmptyException | java.nio.file.AccessDeniedException lost) {
                if (!Files.isDirectory(target)) {
                    throw lost;
                }
                delete(part, notes);                                   // another process put the same bytes there first: use its folder
            } catch (AtomicMoveNotSupportedException e) {
                throw new UncheckedIOException(target.getParent() + ": the file system cannot move a folder in one step; the module cache "
                        + "must be on a local disk or a share that supports renames", e);
            }
            return notes;
        } catch (IOException e) {
            delete(part, notes);
            throw new UncheckedIOException(dmod.getFileName() + ": cannot unpack into " + target + " (" + e.getMessage() + ")", e);
        } catch (RuntimeException e) {
            delete(part, notes);
            throw e;
        }
    }

    /**
     * Deletes every folder under the cache root that no present package owns ({@code owned}: absolute {@code <id>/<version>-<hash16>}
     * folders), an id folder left empty, and a {@code .part} folder older than {@link #PART_GRACE}. Returns the notes: what was deleted, and
     * each folder that could not be (held open), in words.
     */
    static List<String> sweep(Path cacheRoot, Set<Path> owned) {
        List<String> notes = new ArrayList<>();
        if (!Files.isDirectory(cacheRoot)) {
            return notes;
        }
        Set<Path> keep = owned.stream().map(p -> p.toAbsolutePath().normalize()).collect(java.util.stream.Collectors.toSet());
        Instant oldPart = Instant.now().minus(PART_GRACE);
        for (Path idDir : list(cacheRoot)) {
            if (!Files.isDirectory(idDir)) {
                delete(idDir, notes);
                continue;
            }
            for (Path v : list(idDir)) {
                Path abs = v.toAbsolutePath().normalize();
                if (keep.contains(abs)) {
                    continue;
                }
                if (v.getFileName().toString().contains(PART) && modified(v).isAfter(oldPart)) {
                    continue;                                          // maybe another process unpacking now
                }
                if (delete(v, notes)) {
                    notes.add("cache: removed " + abs + " (no .dmod in the modules folder owns it)");
                }
            }
            if (list(idDir).isEmpty()) {
                delete(idDir, notes);
            }
        }
        return notes;
    }

    /** Deletes a file or a folder tree; false with a note when something in it could not be deleted (never thrown). */
    static boolean delete(Path p, List<String> notes) {
        if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        try {
            Files.walkFileTree(p, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                    if (e != null) {
                        throw e;
                    }
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        } catch (IOException e) {
            notes.add("cache: could not delete " + p.toAbsolutePath() + " (" + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "); a process may still hold a file in it open. It is tried again at the next start");
            return false;
        }
    }

    /** The entries of a folder, sorted; empty when it does not list. */
    static List<Path> list(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Instant modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }
}
