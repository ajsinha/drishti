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

import com.ash.drishti.iceberg.io.DrishtiFileIO;
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.lakeio.FileEntry;
import com.ash.drishti.lakeio.S3Storage;
import com.ash.drishti.lakeio.Storage;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import org.apache.iceberg.LocationProviders;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.LocationProvider;

/**
 * A path-based table for Apache Iceberg (the {@code HadoopTables} layout, so tables move between engines unchanged, with no Hadoop): a
 * folder whose {@code metadata/version-hint.text} names the current version N and whose {@code metadata/vN.metadata.json} (or
 * {@code vN.gz.metadata.json}) is it. With no hint, the highest version is found by listing; a hint that lags is caught up by looking
 * for N+1.
 *
 * <p>A table a catalog wrote (pyiceberg, a JDBC or Glue catalog) names its metadata {@code NNNNN-<uuid>.metadata.json} and writes no hint:
 * with neither a hint nor a {@code vN} file, the highest NNNNN is current. Such a table is read; commits use only the {@code vN} convention.
 *
 * <p>A commit writes {@code vN+1.metadata.json} as one exclusive create ({@link Storage#createIfAbsent}: a hard link on local disk, a
 * conditional PUT on S3), so of two writers exactly one wins and the other's commit is retried by Iceberg; then it replaces the hint. An
 * S3-compatible store that does not honour conditional writes is detected by a probe at the first commit and the table is then
 * single-writer ({@link #exclusiveCommits()} false).
 */
public final class PathTableOps implements TableOperations {

    private static final Pattern VERSION = Pattern.compile("v(\\d+)(\\.gz)?\\.metadata\\.json(\\.gz)?$");
    private static final Pattern CATALOG_NAMED = Pattern.compile("(\\d+)-[0-9A-Za-z-]+(\\.gz)?\\.metadata\\.json(\\.gz)?$");

    private final String location;
    private final FileAccess io;
    private final DrishtiFileIO fileIO;
    private volatile Boolean exclusive;
    private volatile TableMetadata current;

    /** @param location the table's folder (canonical: a {@code file:} or {@code s3a://} URI string, no trailing slash) */
    public PathTableOps(String location, FileAccess io) {
        this.location = location.replaceAll("/+$", "");
        this.io = io;
        this.fileIO = new DrishtiFileIO(io);
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

    /** Whether a table is here: a hint or a metadata file (ours, or a catalog's). */
    public boolean exists() throws IOException {
        return currentVersion() > 0 || catalogNamed() != null;
    }

    String catalogNamed() throws IOException {
        String best = null;
        long seq = -1;
        try {
            for (FileEntry f : storage().listFrom(io.resolve(metadataDir() + "/0"))) {
                String name = f.path().substring(f.path().lastIndexOf('/') + 1);
                Matcher m = CATALOG_NAMED.matcher(name);
                if (m.matches() && Long.parseLong(m.group(1)) > seq) {
                    seq = Long.parseLong(m.group(1));
                    best = metadataDir() + "/" + name;
                }
            }
        } catch (FileNotFoundException e) {
            return null;
        }
        return best;
    }

    int currentVersion() throws IOException {
        Storage s = storage();
        int v;
        try {
            v = Integer.parseInt(new String(s.readAll(io.resolve(metadataDir() + "/version-hint.text")), StandardCharsets.UTF_8).trim());
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
        if (file == null) {
            return 0;
        }
        Matcher m = VERSION.matcher(file.substring(file.lastIndexOf('/') + 1));
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private String newestFile() throws IOException {
        int v = currentVersion();
        if (v > 0) {
            String f = file(v);
            if (f == null) {
                throw new NoSuchTableException("metadata/version-hint.text of %s names version %s, which is not there", location, v);
            }
            return f;
        }
        return catalogNamed();
    }

    @Override
    public TableMetadata current() {
        TableMetadata c = current;
        return c != null ? c : refresh();
    }

    @Override
    public TableMetadata refresh() {
        try {
            String f = newestFile();
            if (f == null) {
                current = null;
                throw new NoSuchTableException("no Iceberg table at %s (no metadata/version-hint.text, no metadata/v*.metadata.json and no catalog's"
                        + " metadata/NNNNN-<uuid>.metadata.json)", location);
            }
            TableMetadata c = current;
            if (c == null || !f.equals(c.metadataFileLocation())) {
                current = TableMetadataParser.read(fileIO, f);
            }
            return current;
        } catch (IOException e) {
            throw new UncheckedIOException("reading the metadata of " + location, e);
        }
    }

    @Override
    public void commit(TableMetadata base, TableMetadata metadata) {
        if (base != null && !base.metadataFileLocation().equals(current().metadataFileLocation())) {
            throw new CommitFailedException("%s changed since the commit began", location);
        }
        try {
            int v = base == null ? 1 : versionOf(base.metadataFileLocation()) + 1;
            write(metadata, v);
            refresh();
        } catch (IOException e) {
            throw new UncheckedIOException("committing to " + location, e);
        }
    }

    private void write(TableMetadata md, int v) throws IOException {
        boolean gzip = "gzip".equalsIgnoreCase(md.property("write.metadata.compression-codec", "none"));
        String f = metadataDir() + "/v" + v + (gzip ? ".gz" : "") + ".metadata.json";
        Storage s = storage();
        probe(s);
        byte[] json = TableMetadataParser.toJson(md).getBytes(StandardCharsets.UTF_8);
        if (gzip) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (GZIPOutputStream g = new GZIPOutputStream(b)) {
                g.write(json);
            }
            json = b.toByteArray();
        }
        if (!s.createIfAbsent(io.resolve(f), json)) {
            throw new CommitFailedException("version %s of %s was committed by another writer", v, location);
        }
        s.put(io.resolve(metadataDir() + "/version-hint.text"), String.valueOf(v).getBytes(StandardCharsets.UTF_8));
        // Hadoop's local file system keeps a checksum beside the hint it wrote; a stale one makes a Hadoop reader fail its check, so it goes
        if ("file".equals(com.ash.drishti.lakeio.LocalPaths.scheme(location))) {
            s.delete(io.resolve(metadataDir() + "/.version-hint.text.crc"));
        }
        if (Boolean.parseBoolean(md.property("write.metadata.delete-after-commit.enabled", "false"))) {
            removeOldVersions(md, s);
        }
    }

    private void removeOldVersions(TableMetadata md, Storage s) {
        List<String> kept = new ArrayList<>();
        md.previousFiles().forEach(e -> kept.add(e.file()));
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

    /** False on a store that does not honour conditional writes. */
    public boolean exclusiveCommits() {
        Boolean e = exclusive;
        return e == null || e;
    }

    @Override
    public FileIO io() {
        return fileIO;
    }

    @Override
    public String metadataFileLocation(String fileName) {
        return metadataDir() + "/" + fileName;
    }

    @Override
    public LocationProvider locationProvider() {
        TableMetadata c = current();
        return LocationProviders.locationsFor(c.location(), c.properties());
    }
}
