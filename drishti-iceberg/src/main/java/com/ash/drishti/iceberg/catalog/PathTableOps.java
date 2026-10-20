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
package com.ash.drishti.iceberg.catalog;

import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.meta.TableMetadata;
import com.ash.drishti.iceberg.meta.TableUpdate;
import com.ash.drishti.lakeio.FileEntry;
import com.ash.drishti.lakeio.S3Storage;
import com.ash.drishti.lakeio.Storage;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A path-based table (Iceberg's {@code HadoopTables} convention, so tables move between this engine and the library
 * unchanged): a folder whose {@code metadata/version-hint.text} names the current version N and whose
 * {@code metadata/vN.metadata.json} (or {@code vN.gz.metadata.json}) is it. With no hint, the highest version is
 * found by listing; a hint that lags (a writer stopped between its two steps) is caught up by looking for N+1.
 *
 * <p>A commit writes {@code vN+1.metadata.json} as one exclusive create ({@link Storage#createIfAbsent}: a hard link
 * on local disk, a conditional PUT on S3), so of two writers exactly one wins and the other gets
 * {@link CommitConflict}; then it replaces the hint. Metadata files beyond {@code write.metadata.previous-versions-max}
 * are removed after a commit when {@code write.metadata.delete-after-commit.enabled}. An S3-compatible store that does
 * not honour conditional writes is detected by a probe at the first commit and the table is then
 * <em>single-writer</em> ({@link #exclusiveCommits()} false): commits still work, but two writers at once must not
 * happen.
 */
public final class PathTableOps implements TableOps {

    private static final Pattern VERSION = Pattern.compile("v(\\d+)(\\.gz)?\\.metadata\\.json(\\.gz)?$");

    private final String location;
    private final FileAccess io;
    private volatile Boolean exclusive;

    /** @param location the table's folder (canonical: a {@code file:} or {@code s3a://} URI string, no trailing slash) */
    public PathTableOps(String location, FileAccess io) {
        this.location = location.replaceAll("/+$", "");
        this.io = io;
    }

    public String location() {
        return location;
    }

    private String metadataDir() {
        return location + "/metadata";
    }

    private Storage storage() {
        return io.storage(io.resolve(location));
    }

    /** Whether a table is here: a hint or a metadata file. */
    public boolean exists() throws IOException {
        return currentVersion() > 0;
    }

    /** The newest version: the hint (caught up when it lags), else the highest listed; 0 when there is none. */
    int currentVersion() throws IOException {
        Storage s = storage();
        int v = 0;
        try {
            String hint = new String(s.readAll(io.resolve(metadataDir() + "/version-hint.text")), StandardCharsets.UTF_8).trim();
            v = Integer.parseInt(hint);
        } catch (FileNotFoundException | NumberFormatException e) {
            v = listedVersion();
        }
        while (v > 0 && file(v + 1) != null) {
            v++;                                              // the hint lags a commit that finished its first step
        }
        return v;
    }

    private int listedVersion() throws IOException {
        int max = 0;
        try {
            for (FileEntry f : storage().listFrom(io.resolve(metadataDir() + "/v"))) {
                Matcher m = VERSION.matcher(f.path().substring(f.path().lastIndexOf('/') + 1));
                if (m.matches()) {
                    max = Math.max(max, Integer.parseInt(m.group(1)));
                }
            }
        } catch (FileNotFoundException e) {
            return 0;
        }
        return max;
    }

    /** The metadata file of version {@code v}, or null. */
    private String file(int v) throws IOException {
        for (String name : List.of("v" + v + ".metadata.json", "v" + v + ".gz.metadata.json", "v" + v + ".metadata.json.gz")) {
            String p = metadataDir() + "/" + name;
            try {
                storage().status(io.resolve(p));
                return p;
            } catch (FileNotFoundException e) {
                // not this name
            }
        }
        return null;
    }

    private static int versionOf(String file) {
        Matcher m = VERSION.matcher(file.substring(file.lastIndexOf('/') + 1));
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    @Override
    public Loaded load() throws IOException {
        int v = currentVersion();
        if (v == 0) {
            throw new NoSuchTable("no Iceberg table at " + location + " (no metadata/version-hint.text and no metadata/v*.metadata.json)");
        }
        String f = file(v);
        if (f == null) {
            throw new NoSuchTable("metadata/version-hint.text of " + location + " names version " + v + ", which is not there");
        }
        return new Loaded(TableMetadata.read(io.readAll(f)), f, null);
    }

    @Override
    public Optional<Loaded> refresh(Loaded current) throws IOException {
        int v = currentVersion();
        if (v == versionOf(current.location())) {
            return Optional.empty();
        }
        return Optional.of(load());
    }

    @Override
    public Loaded commit(Loaded base, TableUpdate update) throws IOException {
        long now = System.currentTimeMillis();
        TableMetadata next = update.apply(base.metadata(), base.location(), Math.max(now, base.metadata().lastUpdatedMs() + 1));
        int v = versionOf(base.location()) + 1;
        return write(next, v);
    }

    /** Writes {@code md} as version {@code v} (1: a new table); {@link CommitConflict} when the version exists. */
    public Loaded write(TableMetadata md, int v) throws IOException {
        boolean gzip = "gzip".equalsIgnoreCase(md.property("write.metadata.compression-codec", "none"));
        String f = metadataDir() + "/v" + v + (gzip ? ".gz" : "") + ".metadata.json";
        Storage s = storage();
        probe(s);
        if (!s.createIfAbsent(io.resolve(f), md.toBytes(gzip))) {
            throw new CommitConflict("version " + v + " of " + location + " was committed by another writer");
        }
        s.put(io.resolve(metadataDir() + "/version-hint.text"), String.valueOf(v).getBytes(StandardCharsets.UTF_8));
        if (Boolean.parseBoolean(md.property("write.metadata.delete-after-commit.enabled", "false"))) {
            removeOldVersions(md, s);
        }
        return new Loaded(md, f, null);
    }

    /** Old metadata files no longer in the metadata log go (the log keeps previous-versions-max of them). */
    private void removeOldVersions(TableMetadata md, Storage s) {
        List<String> kept = new ArrayList<>();
        md.metadataLog().forEach(e -> kept.add(e.metadataFile()));
        int oldest = kept.stream().mapToInt(PathTableOps::versionOf).filter(x -> x > 0).min().orElse(0);
        for (int v = oldest - 1; v >= 1 && oldest > 0; v--) {
            try {
                String f = file(v);
                if (f == null) {
                    break;                                     // removed by an earlier commit
                }
                s.delete(io.resolve(f));
            } catch (IOException | RuntimeException e) {
                break;                                         // best effort: the next commit tries again
            }
        }
    }

    private void probe(Storage s) throws IOException {
        if (exclusive == null) {
            exclusive = s instanceof S3Storage s3 ? s3.exclusiveCreates(io.resolve(metadataDir())) : s.exclusiveCreates();
            if (!exclusive) {
                System.getLogger("com.ash.drishti.iceberg").log(System.Logger.Level.WARNING,
                        "drishti-iceberg: the object store of {0} does not honour conditional writes (If-None-Match): the table is single-writer;"
                                + " two writers committing at once could lose a commit", location);
            }
        }
    }

    @Override
    public boolean exclusiveCommits() {
        Boolean e = exclusive;
        return e == null || e;
    }

    @Override
    public FileAccess io(Loaded current) {
        return io;
    }

    @Override
    public String describe() {
        return location;
    }
}
