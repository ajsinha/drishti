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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ledger (DATA_LAKE_GUIDE.md): one JSON line per step a file takes ({@code claimed}, {@code duplicate}, {@code loaded},
 * {@code failed}, {@code returned}, {@code locked}), in {@code ledger/ledger-<yyyy-MM>.jsonl}, appended and never rewritten. A line is
 * written and forced to disk before the step it records is acted on, so a restart reads where every file stood (section 7); the SHA-256 of
 * every loaded file finds the same file dropped again (section 4.5). Kept when the outbox is cleaned.
 */
public final class Ledger {

    private static final Logger LOG = LoggerFactory.getLogger(Ledger.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);

    public static final String CLAIMED = "claimed";
    public static final String DUPLICATE = "duplicate";
    public static final String LOADED = "loaded";
    public static final String FAILED = "failed";
    public static final String RETURNED = "returned";
    public static final String LOCKED = "locked";
    /** An empty file or archive: nothing written, no business date touched (DATA_LAKE_GUIDE.md). */
    public static final String IGNORED = "ignored";
    /** An archive whose load failed part-way: the tables it had committed undone (DATA_LAKE_GUIDE.md). */
    public static final String ROLLED_BACK = "rolled back";

    private final Path dir;
    private final Clock clock;
    private final Map<String, JsonNode> loadedBySha = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, JsonNode> lastByRunFile = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Integer> lockedBySha = new java.util.concurrent.ConcurrentHashMap<>();
    /** One append at a time (a lock, not synchronized: the append blocks on the disk, which would pin a virtual thread). */
    private final java.util.concurrent.locks.ReentrantLock appends = new java.util.concurrent.locks.ReentrantLock();

    public Ledger(Path dir, Clock clock) throws IOException {
        this.dir = dir;
        this.clock = clock;
        Files.createDirectories(dir);
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "ledger-*.jsonl")) {
            ds.forEach(files::add);
        }
        files.sort(Path::compareTo);
        if (!files.isEmpty()) {
            endLine(files.get(files.size() - 1));
        }
        for (Path f : files) {
            try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        index(JSON.readTree(line));
                    } catch (IOException e) {
                        LOG.warn("data inbox: a ledger line of {} could not be read (a write cut short?): skipped", f.getFileName());
                    }
                }
            }
        }
    }

    /** A last line cut short by a stop gets its line end, so the next line is a line of its own. */
    private static void endLine(Path f) throws IOException {
        long size = Files.size(f);
        if (size == 0) {
            return;
        }
        try (var ch = java.nio.channels.FileChannel.open(f, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer last = java.nio.ByteBuffer.allocate(1);
            ch.read(last, size - 1);
            if (last.get(0) != '\n') {
                ch.write(java.nio.ByteBuffer.wrap(new byte[] {'\n'}), size);
                ch.force(true);
            }
        }
    }

    private void index(JsonNode n) {
        String outcome = n.path("outcome").asText();
        String sha = n.path("sha256").asText("");
        lastByRunFile.put(n.path("run").asText() + "/" + n.path("file").asText(), n);
        if (LOADED.equals(outcome) && !sha.isEmpty()) {
            loadedBySha.put(sha, n);
        }
        if (LOCKED.equals(outcome) && !sha.isEmpty()) {
            lockedBySha.merge(sha, 1, Integer::sum);
        } else if (LOADED.equals(outcome) || FAILED.equals(outcome)) {
            lockedBySha.remove(sha);
        }
    }

    /** Appends one line ({@code at} added) and forces it to disk before returning. */
    public ObjectNode write(String run, String file, String outcome, ObjectNode fields) throws IOException {
        appends.lock();
        try {
            return append(run, file, outcome, fields);
        } finally {
            appends.unlock();
        }
    }

    private ObjectNode append(String run, String file, String outcome, ObjectNode fields) throws IOException {
        ObjectNode n = JSON.createObjectNode().put("at", clock.instant().toString()).put("run", run).put("file", file).put("outcome", outcome);
        if (fields != null) {
            n.setAll(fields);
        }
        Path f = dir.resolve("ledger-" + MONTH.format(clock.instant()) + ".jsonl");
        try (var ch = java.nio.channels.FileChannel.open(f, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(java.nio.ByteBuffer.wrap((n + "\n").getBytes(StandardCharsets.UTF_8)));
            ch.force(true);
        }
        index(n);
        return n;
    }

    /** The {@code loaded} line of a file with this SHA-256, or null. */
    public JsonNode loaded(String sha256) {
        return loadedBySha.get(sha256);
    }

    /** The last line written for {@code file} in {@code run}, or null. */
    public JsonNode last(String run, String file) {
        return lastByRunFile.get(run + "/" + file);
    }

    /** How many cycles in a row found this file's table locked (since it was last loaded or failed). */
    public int lockedTimes(String sha256) {
        return lockedBySha.getOrDefault(sha256, 0);
    }

    /** The newest {@code limit} lines, newest first (Admin and {@code drishti.py dataload status}). */
    public List<JsonNode> recent(int limit) throws IOException {
        return tail(dir, limit);
    }

    /**
     * The newest {@code limit} lines of the ledger in {@code dir}, newest first, read without opening the ledger (no index, nothing written:
     * the status reads it while a cycle appends); none when the folder is not there yet.
     */
    static List<JsonNode> tail(Path dir, int limit) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "ledger-*.jsonl")) {
            ds.forEach(files::add);
        }
        files.sort((a, b) -> b.compareTo(a));
        List<JsonNode> out = new ArrayList<>();
        for (Path f : files) {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0 && out.size() < limit; i--) {
                if (!lines.get(i).isBlank()) {
                    try {
                        out.add(JSON.readTree(lines.get(i)));
                    } catch (IOException e) {
                        // a line cut short: skipped, as when reading
                    }
                }
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    /**
     * The {@code loaded} line of load {@code id} in the ledger in {@code dir} (DATA_LAKE_GUIDE.md section 5.5), newest first, or null: the
     * line whose {@code loads} names the id, an archive's {@code route.loadId}, or (a line without {@code loads}) the line whose
     * {@code loaderRun} is the id's run ({@code <run>.<n>}). Its {@code route.lake} is the lake the load wrote into.
     */
    public static JsonNode loadedLine(Path dir, String id) throws IOException {
        if (id == null || id.isBlank() || !Files.isDirectory(dir)) {
            return null;
        }
        int dot = id.lastIndexOf('.');
        String run = dot > 0 ? id.substring(0, dot) : id;
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "ledger-*.jsonl")) {
            ds.forEach(files::add);
        }
        files.sort((a, b) -> b.compareTo(a));
        for (Path f : files) {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0; i--) {
                if (!lines.get(i).contains(run)) {
                    continue;                                   // cheap: only lines naming the run are parsed
                }
                JsonNode n;
                try {
                    n = JSON.readTree(lines.get(i));
                } catch (IOException e) {
                    continue;                                   // a line cut short: skipped, as when reading
                }
                if (LOADED.equals(n.path("outcome").asText()) && (n.path("loads").has(id) || id.equals(n.path("route").path("loadId").asText(null))
                        || (!n.has("loads") && run.equals(n.path("loaderRun").asText(null))))) {
                    return n;
                }
            }
        }
        return null;
    }

    /** A file's SHA-256, read as a stream (any size). */
    public static String sha256(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
