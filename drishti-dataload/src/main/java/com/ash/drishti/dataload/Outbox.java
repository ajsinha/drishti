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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where a handled file ends (DATALOAD_INBOX.md section 2): {@code outbox/<day>/<run>/} with its {@code .ready} and its report
 * {@code <file>.loaded.json}, or {@code failed/<day>/<run>/} with {@code <file>.failed.json} (the reason in words and the loader's
 * report). The report is written first and the data file moved last, so a file still in {@code work/} is a file not yet moved. Also: a
 * file put back in the inbox, Retry (a failed file and its marker back), and the retention ({@code keep-outbox-days}; the ledger is kept).
 */
public final class Outbox {

    private static final Logger LOG = LoggerFactory.getLogger(Outbox.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path root;
    private final Clock clock;
    private final ZoneId zone;

    public Outbox(Path root, Clock clock, ZoneId zone) {
        this.root = root;
        this.clock = clock;
        this.zone = zone;
    }

    public Path inbox() {
        return root.resolve("inbox");
    }

    public Path outbox() {
        return root.resolve("outbox");
    }

    public Path failed() {
        return root.resolve("failed");
    }

    /** Today's folder of a run: {@code outbox/<day>/<run>} or {@code failed/<day>/<run>}. */
    public Path folder(boolean ok, String run) {
        return (ok ? outbox() : failed()).resolve(LocalDate.now(clock.withZone(zone)).toString()).resolve(run);
    }

    /** A loaded (or already loaded) file, its marker and its report into the outbox; returns the file's new place. */
    public Path done(String run, Path file, Path marker, ObjectNode report) throws IOException {
        return put(folder(true, run), file, marker, report, ".loaded.json");
    }

    /** A file that could not be loaded, its marker and the reason into {@code failed/}; returns the file's new place. */
    public Path failed(String run, Path file, Path marker, ObjectNode report) throws IOException {
        return put(folder(false, run), file, marker, report, ".failed.json");
    }

    private static Path put(Path to, Path file, Path marker, ObjectNode report, String suffix) throws IOException {
        Files.createDirectories(to);
        String name = file.getFileName().toString();
        Files.writeString(to.resolve(name + suffix), report.toPrettyString());
        if (marker != null && Files.exists(marker)) {
            Claims.move(marker, to.resolve(marker.getFileName()));
        }
        return Claims.move(file, to.resolve(name));
    }

    /** A claimed file back in the inbox (the data file first, then its marker: never a marker before its file). */
    public void back(Path file, Path marker) throws IOException {
        Files.createDirectories(inbox());
        Claims.move(file, inbox().resolve(file.getFileName()));
        if (marker != null && Files.exists(marker)) {
            Claims.move(marker, inbox().resolve(marker.getFileName()));
        }
    }

    /** {@code ready.all} into the outbox of the run that used it, so it applies once. */
    public void readyAll(String run, Path readyAll) throws IOException {
        if (readyAll != null && Files.exists(readyAll)) {
            Path to = folder(true, run);
            Files.createDirectories(to);
            Claims.move(readyAll, to.resolve(Inbox.READY_ALL));
        }
    }

    /**
     * Retry: the newest failed copy of {@code name} and its marker back into the inbox (an empty marker when it had none: it was ready by
     * {@code ready.all}). Returns where it came from; refuses a name that is in the inbox already.
     */
    public Path retry(String name) throws IOException {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.startsWith(".") || name.endsWith(Inbox.READY)) {
            throw new IllegalArgumentException("give the data file's name as it was dropped (trades-2026-11-03.jsonl.gz)");
        }
        Path found = failedCopies().stream().filter(p -> p.getFileName().toString().equals(name)).reduce((a, b) -> b).orElse(null);
        if (found == null) {
            throw new NoSuchFileException("no failed file is named " + name);
        }
        Path target = inbox().resolve(name);
        if (Files.exists(target)) {
            throw new IllegalArgumentException(name + " is in the inbox already");
        }
        Files.createDirectories(inbox());
        Claims.move(found, target);
        Path marker = found.resolveSibling(name + Inbox.READY);
        if (Files.exists(marker)) {
            Claims.move(marker, inbox().resolve(name + Inbox.READY));
        } else {
            Files.writeString(inbox().resolve(name + Inbox.READY), "");
        }
        return found;
    }

    /** Every failed data file still in {@code failed/}, oldest day and run first. */
    public List<Path> failedCopies() throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(failed())) {
            return out;
        }
        try (Stream<Path> s = Files.walk(failed(), 3)) {
            s.filter(p -> failed().relativize(p).getNameCount() == 3 && Files.isRegularFile(p)).filter(p -> {
                String n = p.getFileName().toString();
                return !n.endsWith(Inbox.READY) && !n.endsWith(".failed.json");
            }).sorted(Comparator.comparing(p -> failed().relativize(p).toString())).forEach(out::add);
        }
        return out;
    }

    /** A failed file's reason, from its report (status, Admin). */
    public Map<String, Object> describeFailed(Path file) {
        Map<String, Object> m = new LinkedHashMap<>();
        Path rel = failed().relativize(file);
        m.put("file", file.getFileName().toString());
        m.put("day", rel.getName(0).toString());
        m.put("run", rel.getName(1).toString());
        try {
            JsonNode r = JSON.readTree(file.resolveSibling(file.getFileName() + ".failed.json").toFile());
            m.put("reason", r.path("reason").asText(""));
        } catch (IOException e) {
            m.put("reason", "(no report)");
        }
        return m;
    }

    /** Removes the outbox's and failed's day folders older than {@code keepDays} (0: kept for ever); returns how many went. */
    public int purge(int keepDays) {
        if (keepDays <= 0) {
            return 0;
        }
        LocalDate oldest = LocalDate.now(clock.withZone(zone)).minusDays(keepDays);
        int gone = 0;
        for (Path base : List.of(outbox(), failed())) {
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(base)) {
                for (Path day : ds) {
                    LocalDate d;
                    try {
                        d = LocalDate.parse(day.getFileName().toString());
                    } catch (DateTimeParseException e) {
                        continue;                                              // not a day folder: left alone
                    }
                    if (d.isBefore(oldest)) {
                        deleteTree(day);
                        gone++;
                    }
                }
            } catch (IOException e) {
                LOG.warn("data inbox: {} could not be cleaned: {}", base, e.getMessage());
            }
        }
        return gone;
    }

    static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
