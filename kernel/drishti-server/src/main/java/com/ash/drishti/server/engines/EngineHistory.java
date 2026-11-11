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
package com.ash.drishti.server.engines;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The engines' History (Admin, Engines; {@code drishti.py server engines history}): every change, newest first, kept as JSON Lines in this
 * node's data folder ({@code <data dir>/engines/history.jsonl}). The audit log holds the same changes with the files' checksums.
 *
 * @see Change
 */
public final class EngineHistory {

    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    /** Most changes kept in memory and returned. */
    static final int KEEP = 1000;
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One change.
     *
     * @param at when (ISO instant)
     * @param engine which
     * @param change on, off, or "edited in the file"
     * @param who the administrator (blank for an edit by hand)
     * @param why the reason (blank for an edit by hand)
     * @param node the node it was made on
     * @param outcome live, restart needed, refused, or written (overridden by the environment or the local file)
     * @param detail the refusal's words, or what else happened
     */
    public record Change(String at, String engine, String change, String who, String why, String node, String outcome, String detail) {}

    private final Path file;
    private final List<Change> changes = new ArrayList<>();

    public EngineHistory(Path file) {
        this.file = file;
        if (file != null && Files.isRegularFile(file)) {
            try {
                for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!l.isBlank()) {
                        changes.add(JSON.readValue(l, Change.class));
                    }
                }
            } catch (IOException e) {
                org.slf4j.LoggerFactory.getLogger(EngineHistory.class).warn("engines: history {} not read: {}", file, e.toString());
            }
        }
        trim();
    }

    public void add(Change c) {
        lock.lock();                    // a ReentrantLock, not synchronized: the file write blocks (Java 21 pins virtual threads)
        try {
            changes.add(c);
            trim();
            if (file != null) {
                try {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, JSON.writeValueAsString(c) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException e) {
                    org.slf4j.LoggerFactory.getLogger(EngineHistory.class).warn("engines: history {} not written: {}", file, e.toString());
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** Newest first, at most {@code limit}. */
    public List<Change> newestFirst(int limit) {
        List<Change> out;
        lock.lock();
        try {
            out = new ArrayList<>(changes);
        } finally {
            lock.unlock();
        }
        Collections.reverse(out);
        return out.subList(0, Math.min(Math.max(1, limit), out.size()));
    }

    private void trim() {
        while (changes.size() > KEEP) {
            changes.remove(0);
        }
    }
}
