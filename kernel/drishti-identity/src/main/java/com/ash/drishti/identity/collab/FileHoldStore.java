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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/** Legal holds as one file, {@code <dir>/holds.json}, written whole to a sibling and moved into place under a lock. */
public final class FileHoldStore implements HoldStore {

    private final Path file;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final ReentrantLock lock = new ReentrantLock();

    public FileHoldStore(Path dir) {
        this.file = dir.toAbsolutePath().normalize().resolve("holds.json");
    }

    private List<Hold> read() {
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(json.readValue(file.toFile(), new TypeReference<List<Hold>>() {}));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(List<Hold> all) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("holds.json.tmp");
            json.writeValue(tmp.toFile(), all);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Hold place(Hold h) {
        lock.lock();
        try {
            List<Hold> all = read();
            long next = all.stream().mapToLong(Hold::id).max().orElse(0) + 1;
            Hold saved = h.withId(next);
            all.add(saved);
            write(all);
            return saved;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Hold> find(long id) {
        lock.lock();
        try {
            return read().stream().filter(h -> h.id() == id).findFirst();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Hold> list(boolean activeOnly) {
        lock.lock();
        try {
            return read().stream().filter(h -> !activeOnly || h.active()).sorted(Comparator.comparingLong(Hold::id).reversed()).toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean release(long id, String by, Instant at) {
        lock.lock();
        try {
            List<Hold> all = read();
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id() == id) {
                    if (!all.get(i).active()) {
                        return false;
                    }
                    all.set(i, all.get(i).released(by, at));
                    write(all);
                    return true;
                }
            }
            return false;
        } finally {
            lock.unlock();
        }
    }
}
