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
package com.ash.drishti.identity.collab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Seals appended to {@code <dir>/seals.jsonl}, one JSON object a line, {@code {"key", "count", "hash"}} (2.9.4, docs/guides/DATA_FORMATS.md);
 * the last line of a key wins. Read once at start, after the {@code seals.log} of earlier releases (one {@code key TAB count TAB hash} line
 * each), which is kept as it is and never written again.
 */
public final class FileSealStore implements Seal.Store {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private final Map<String, Seal> seals = new ConcurrentHashMap<>();

    public FileSealStore(Path dir) {
        Path base = dir.toAbsolutePath().normalize();
        this.file = base.resolve("seals.jsonl");
        Path old = base.resolve("seals.log");
        try {
            if (Files.isRegularFile(old)) {                         // the earlier tab-separated form: read, never rewritten
                for (String line : Files.readAllLines(old, StandardCharsets.UTF_8)) {
                    String[] f = line.split("\t", -1);
                    if (f.length == 3) {
                        seals.put(f[0], new Seal(Long.parseLong(f[1]), f[2]));
                    }
                }
            }
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        JsonNode n = JSON.readTree(line);
                        seals.put(n.get("key").asText(), new Seal(n.get("count").asLong(), n.get("hash").asText()));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("could not read the seals in " + base, e);
        }
    }

    @Override
    public Optional<Seal> get(String key) {
        return Optional.ofNullable(seals.get(key));
    }

    /** Serialises appends: a ReentrantLock, not synchronized, since the append is file I/O (Java 21 would pin a virtual thread). */
    private final ReentrantLock appending = new ReentrantLock();

    @Override
    public void put(String key, Seal seal) {
        appending.lock();
        try {
            Files.createDirectories(file.getParent());
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("key", key);
            line.put("count", seal.count());
            line.put("hash", seal.hash());
            Files.writeString(file, JSON.writeValueAsString(line) + '\n', StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            seals.put(key, seal);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            appending.unlock();
        }
    }

    @Override
    public Map<String, Seal> all() {
        return Map.copyOf(seals);
    }
}
