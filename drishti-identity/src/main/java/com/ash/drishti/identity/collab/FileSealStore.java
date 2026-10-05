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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Seals appended to {@code <dir>/seals.log} (one {@code key TAB count TAB hash} line each, the last wins), read once at start. */
public final class FileSealStore implements Seal.Store {

    private final Path file;
    private final Map<String, Seal> seals = new ConcurrentHashMap<>();

    public FileSealStore(Path dir) {
        this.file = dir.toAbsolutePath().normalize().resolve("seals.log");
        if (Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String[] f = line.split("\t", -1);
                    if (f.length == 3) {
                        seals.put(f[0], new Seal(Long.parseLong(f[1]), f[2]));
                    }
                }
            } catch (IOException | NumberFormatException e) {
                throw new IllegalStateException("could not read " + file, e);
            }
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
            Files.writeString(file, key + '\t' + seal.count() + '\t' + seal.hash() + '\n', StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            seals.put(key, seal);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            appending.unlock();
        }
    }
}
