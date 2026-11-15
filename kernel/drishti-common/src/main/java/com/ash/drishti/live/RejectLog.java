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
package com.ash.drishti.live;

import com.ash.drishti.api.live.LiveMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The reject log of one live connector (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 3.4): one JSONL line per rejected message, with
 * the reason, its code, the transport position and the payload (cut at 64 KB), at {@code <dir>/<connector>/<date>.jsonl}, files older than
 * {@code keepDays} removed. The newest {@value #NEWEST} are also kept in memory for the Admin panel. A reject is never an error on a
 * page; the log failing to write is said once and never stops the feed.
 */
public final class RejectLog {

    /** The rejects kept in memory for Admin. */
    public static final int NEWEST = 20;
    private static final int MAX_PAYLOAD = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final System.Logger LOG = System.getLogger(RejectLog.class.getName());

    private final Path dir;
    private final int keepDays;
    private final Clock clock;
    private final Deque<ObjectNode> newest = new ArrayDeque<>();
    private volatile LocalDate swept;
    private volatile boolean failedOnce;

    /**
     * @param dir      this connector's folder, absolute ({@code ${drishti.data.dir}/live/rejects/<connector>}); null: memory only
     * @param keepDays how many days of files are kept
     */
    public RejectLog(Path dir, int keepDays, Clock clock) {
        if (dir != null && !dir.isAbsolute()) {
            throw new IllegalArgumentException("the reject log's folder must be absolute: " + dir);
        }
        this.dir = dir;
        this.keepDays = Math.max(1, keepDays);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Writes one reject. */
    public void write(String connector, String kind, LiveReject reason, String detail, LiveMessage m) {
        write(connector, kind, reason.reason(), reason.code().code(), detail, m);
    }

    /** Writes one reject an engine made of a landed row (its own code: {@code DRS-5201} ...): reason {@code landing}. */
    public void write(String connector, String kind, String code, String detail) {
        write(connector, kind, "landing", code, detail, null);
    }

    private void write(String connector, String kind, String reason, String code, String detail, LiveMessage m) {
        ObjectNode line = JSON.createObjectNode();
        line.put("at", clock.instant().toString());
        line.put("connector", connector);
        if (kind != null) {
            line.put("kind", kind);
        }
        line.put("reason", reason);
        line.put("code", code);
        line.put("detail", detail);
        if (m != null) {
            line.put("position", String.valueOf(m.position()));
            if (m.key() != null) {
                line.put("key", m.key());
            }
            String v = m.value();
            line.put("payload", v == null ? null : v.length() > MAX_PAYLOAD ? v.substring(0, MAX_PAYLOAD) : v);
        }
        synchronized (newest) {
            newest.addFirst(line);
            while (newest.size() > NEWEST) {
                newest.removeLast();
            }
        }
        if (dir == null) {
            return;
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(today + ".jsonl"), JSON.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (!today.equals(swept)) {
                sweep(today);
                swept = today;
            }
        } catch (IOException e) {
            if (!failedOnce) {
                failedOnce = true;
                LOG.log(System.Logger.Level.WARNING, "live rejects of " + connector + " cannot be written to " + dir + ": " + e.getMessage()
                        + " (the newest are still in Admin)");
            }
        }
    }

    /** The newest rejects, newest first (payloads as written: Admin shows them to data administrators only). */
    public List<ObjectNode> newest() {
        synchronized (newest) {
            return List.copyOf(newest);
        }
    }

    private void sweep(LocalDate today) throws IOException {
        LocalDate oldest = today.minusDays(keepDays - 1L);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.jsonl")) {
            for (Path f : files) {
                String stem = f.getFileName().toString().replace(".jsonl", "");
                try {
                    if (LocalDate.parse(stem).isBefore(oldest)) {
                        Files.deleteIfExists(f);
                    }
                } catch (java.time.format.DateTimeParseException ignored) {
                    // not one of ours
                }
            }
        }
    }
}
