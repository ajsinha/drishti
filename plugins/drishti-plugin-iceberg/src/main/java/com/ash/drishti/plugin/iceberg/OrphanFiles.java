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
package com.ash.drishti.plugin.iceberg;

import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.manifest.ManifestEntry;
import com.ash.drishti.iceberg.manifest.ManifestFile;
import com.ash.drishti.iceberg.meta.Snapshot;
import com.ash.drishti.iceberg.meta.TableMetadata;
import com.ash.drishti.iceberg.scan.Planner;
import com.ash.drishti.lakeio.FileEntry;
import com.ash.drishti.lakeio.Storage;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Files under an Iceberg table's location that no snapshot of its metadata references, older than a minimum age: what a writer
 * that died before its commit left (a load's data files, a manifest), or what a deleted snapshot's clean-up missed. Iceberg's own
 * expiry removes only the files of the snapshots it expires, so these stay for ever without this (DRISHTI_ICEBERG.md section
 * "Orphan files"; LAKE_MAINTENANCE.md).
 *
 * <p>Referenced: the current metadata file and every one its {@code metadata-log} lists, {@code version-hint.text}, each snapshot's
 * manifest list, every manifest those list and every data and delete file those manifests name (any status, so a file a kept snapshot
 * may still read is never taken), and the statistics files. The table's files are listed <em>before</em> the references are read,
 * and a file younger than {@code minAgeHours} (at least {@link #MIN_AGE_HOURS}) is never taken: a load still committing writes its
 * files before the commit that references them, so its files are young. Engine-neutral: the table is read by drishti-iceberg,
 * whichever engine wrote it. Stateless.
 */
final class OrphanFiles {

    /** The youngest an orphan may be: longer than the longest load (a nightly backfill of a large table runs for hours). */
    static final long MIN_AGE_HOURS = 72;

    /** One file no snapshot references. */
    record Orphan(String path, long size, long modified) {}

    private OrphanFiles() {
    }

    /** The table's orphans older than {@code minAgeHours} at {@code nowMs}, in path order; refused under {@link #MIN_AGE_HOURS}. */
    static List<Orphan> find(Table t, long minAgeHours, long nowMs) {
        if (minAgeHours < MIN_AGE_HOURS) {
            throw new IllegalArgumentException("--older-than-hours " + minAgeHours + " is under " + MIN_AGE_HOURS
                    + ": a file younger may belong to a load that has not committed yet");
        }
        FileAccess io = t.io();
        String location = io.resolve(t.metadata().location().replaceAll("/+$", ""));
        Storage storage = io.storage(location);
        List<FileEntry> listed = new ArrayList<>();
        try {
            walk(storage, storage.qualify(location), listed);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        t.refresh();                                               // the references as of now: after the listing
        Set<String> referenced = referenced(t, storage);
        long cutoff = nowMs - minAgeHours * 3_600_000L;
        List<Orphan> out = new ArrayList<>();
        for (FileEntry f : listed) {
            String q = qualify(storage, f.path());
            if (!referenced.contains(q) && f.modified() < cutoff && !q.endsWith("/version-hint.text") && !checksumOfKept(q, referenced)) {
                out.add(new Orphan(f.path(), f.size(), f.modified()));
            }
        }
        out.sort(java.util.Comparator.comparing(Orphan::path));
        return out;
    }

    /**
     * Whether {@code path} is the checksum Hadoop's local file system writes beside a file ({@code .v3.metadata.json.crc} beside
     * {@code v3.metadata.json}, the library engine's tables on local disk) of a file still referenced: it goes with its file, never alone.
     */
    static boolean checksumOfKept(String path, Set<String> referenced) {
        int slash = path.lastIndexOf('/');
        String name = path.substring(slash + 1);
        if (!name.startsWith(".") || !name.endsWith(".crc") || name.length() <= 5) {
            return false;
        }
        String file = path.substring(0, slash + 1) + name.substring(1, name.length() - 4);
        return referenced.contains(file) || file.endsWith("/version-hint.text");
    }

    /** Deletes {@code orphans}; returns how many went (one that cannot be deleted is left for the next run). */
    static int remove(Table t, List<Orphan> orphans) {
        int n = 0;
        for (Orphan o : orphans) {
            try {
                if (t.io().storage(o.path()).delete(o.path())) {
                    n++;
                }
            } catch (IOException | RuntimeException e) {
                // left for the next run
            }
        }
        return n;
    }

    /** Every file of the folder and its sub-folders. */
    private static void walk(Storage s, String dir, List<FileEntry> out) throws IOException {
        String base = dir.replaceAll("/+$", "");
        try {
            out.addAll(s.listFrom(base + "/ "));                  // every name from the space on: all a writer makes
        } catch (FileNotFoundException e) {
            return;
        }
        for (String sub : s.directories(base)) {
            walk(s, base + "/" + sub, out);
        }
    }

    private static String qualify(Storage s, String path) {
        try {
            return s.qualify(path);
        } catch (IOException e) {
            return path;
        }
    }

    /** Every file the metadata references, in the storage's canonical form. */
    static Set<String> referenced(Table t, Storage storage) {
        TableMetadata m = t.metadata();
        FileAccess io = t.io();
        Set<String> out = new HashSet<>();
        java.util.function.Consumer<String> add = p -> {
            if (p != null && !p.isBlank()) {
                out.add(qualify(storage, io.resolve(p)));
            }
        };
        add.accept(t.metadataLocation());
        m.metadataLog().forEach(e -> add.accept(e.metadataFile()));
        for (String key : List.of("statistics", "partition-statistics")) {
            for (JsonNode s : m.json().path(key)) {
                add.accept(s.path("statistics-path").asText(null));
            }
        }
        for (Snapshot s : m.snapshots()) {
            add.accept(s.manifestList());
            for (ManifestFile mf : Planner.manifests(m, s, io)) {
                add.accept(mf.path());
                for (ManifestEntry e : Planner.entries(mf, m, io)) {
                    add.accept(e.file().path());
                }
            }
        }
        return out;
    }
}
