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
package com.ash.drishti.server.deploy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Every deployment, rollback and reverted attempt of a pack, one JSON object per line in
 * {@code drishti.packs.deploy.history-file}. Appends are serialised; a line that cannot be read is skipped.
 */
public final class DeployHistory {

    private final Path file;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ReentrantLock lock = new ReentrantLock();

    public DeployHistory(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public void append(String action, String pack, String version, String previous, String by, String detail, Map<String, Object> extra) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("at", java.time.Instant.now().toString());
        row.put("action", action);
        row.put("pack", pack);
        row.put("version", version);
        row.put("previous", previous);
        row.put("by", by);
        row.put("detail", detail);
        if (extra != null) {
            row.putAll(extra);
        }
        lock.lock();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json.writeValueAsString(row) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.unlock();
        }
    }

    /** Newest first, optionally only one pack's. */
    public List<Map<String, Object>> all(String pack, int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        lock.lock();
        try {
            if (!Files.isRegularFile(file)) {
                return rows;
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                try {
                    Map<String, Object> m = json.readValue(line, new TypeReference<LinkedHashMap<String, Object>>() {});
                    if (pack == null || pack.equals(m.get("pack"))) {
                        rows.add(m);
                    }
                } catch (IOException bad) {
                    // a damaged line is skipped, the rest of the history stays readable
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.unlock();
        }
        Collections.reverse(rows);
        return rows.size() > limit ? rows.subList(0, limit) : rows;
    }
}
