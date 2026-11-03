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
package com.ash.drishti.dataload;

import com.ash.drishti.lakeload.LakeLoad;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The inbox folder and its hand-shake (DATALOAD_INBOX.md section 3): a data file is ready when its {@code <file>.ready} marker sits beside
 * it, or when {@code ready.all} is in the folder; and it is taken only once its size and time have not changed for the stable time.
 * Hidden names (a leading dot) are never looked at; a link, a folder or a name the loader does not read, once marked ready, is refused
 * (it goes to {@code failed/} with the reason). A file without a marker is never touched.
 */
public final class Inbox {

    /** The marker's suffix: {@code trades.jsonl.gz.ready}. */
    public static final String READY = ".ready";
    /** The marker that makes every data file of the inbox ready for one cycle. */
    public static final String READY_ALL = "ready.all";

    /** A data file ready to be claimed; {@code marker} is null when {@code ready.all} made it ready. */
    public record Ready(Path file, Path marker, long size, Instant modified) {

        public String name() {
            return file.getFileName().toString();
        }
    }

    /** A file marked ready that cannot be loaded, and why. */
    public record Refused(Path file, Path marker, String reason) {}

    /** A file that waits: no marker yet, or not stable yet. */
    public record Waiting(String name, long size, Instant modified, String why) {}

    /**
     * What one look at the inbox found: the files to take now (oldest first, within the cycle's caps), the refused, the waiting, the
     * markers without a file, and {@code ready.all} when it is there.
     */
    public record Found(List<Ready> ready, List<Refused> refused, List<Waiting> waiting, List<String> orphanMarkers, Path readyAll) {}

    private final Path dir;

    public Inbox(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    /** The inbox as it is at {@code now}: files whose time is at least {@code stable} old are stable. */
    public Found look(Instant now, Duration stable, int maxFiles, long maxBytes) throws IOException {
        List<Ready> ready = new ArrayList<>();
        List<Refused> refused = new ArrayList<>();
        List<Waiting> waiting = new ArrayList<>();
        List<String> orphans = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return new Found(ready, refused, waiting, orphans, null);
        }
        Path all = dir.resolve(READY_ALL);
        boolean readyAll = Files.isRegularFile(all, LinkOption.NOFOLLOW_LINKS);
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            ds.forEach(entries::add);
        }
        for (Path p : entries) {
            String name = p.getFileName().toString();
            if (name.startsWith(".") || name.equals(READY_ALL)) {
                continue;
            }
            if (name.endsWith(READY)) {
                Path data = p.resolveSibling(name.substring(0, name.length() - READY.length()));
                if (!Files.exists(data, LinkOption.NOFOLLOW_LINKS)) {
                    orphans.add(name);
                }
                continue;
            }
            Path marker = p.resolveSibling(name + READY);
            boolean marked = Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Instant modified = a.lastModifiedTime().toInstant();
            if (!marked && !readyAll) {
                waiting.add(new Waiting(name, a.size(), modified, "no " + name + READY + " yet"));
                continue;
            }
            Path m = marked ? marker : null;
            if (a.isSymbolicLink() || a.isOther()) {
                refused.add(new Refused(p, m, "a link or a special file is never loaded: put the file itself in the inbox"));
                continue;
            }
            if (a.isDirectory()) {
                refused.add(new Refused(p, m, "a folder is not loaded: put its files in the inbox, or an archive of them (.zip, .tar.gz)"));
                continue;
            }
            if (!LakeLoad.reads(name)) {
                refused.add(new Refused(p, m, "the loader does not read " + name + ": JSON Lines (.jsonl, .ndjson, .json; plain, .gz or .zst) "
                        + "or an archive of them (.zip, .tar, .tar.gz, .tgz)"));
                continue;
            }
            Instant markerTime = m == null ? modified : Files.getLastModifiedTime(m, LinkOption.NOFOLLOW_LINKS).toInstant();
            Instant newest = markerTime.isAfter(modified) ? markerTime : modified;
            if (Duration.between(newest, now).compareTo(stable) < 0) {
                waiting.add(new Waiting(name, a.size(), modified, "not stable yet: changed less than " + stable.toSeconds() + " s ago"));
                continue;
            }
            ready.add(new Ready(p, m, a.size(), modified));
        }
        ready.sort(Comparator.comparing(Ready::modified).thenComparing(Ready::name));
        List<Ready> taken = new ArrayList<>();
        long bytes = 0;
        for (Ready r : ready) {
            if (taken.size() >= maxFiles || (!taken.isEmpty() && bytes + r.size() > maxBytes)) {
                waiting.add(new Waiting(r.name(), r.size(), r.modified(), "the next cycle: this one took its most files or bytes"));
                continue;
            }
            taken.add(r);
            bytes += r.size();
        }
        refused.sort(Comparator.comparing(r -> r.file().getFileName().toString()));
        waiting.sort(Comparator.comparing(Waiting::name));
        orphans.sort(String::compareTo);
        return new Found(taken, refused, waiting, orphans, readyAll ? all : null);
    }
}
