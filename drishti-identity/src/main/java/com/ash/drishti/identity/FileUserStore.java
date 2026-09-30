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
package com.ash.drishti.identity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Users in one JSON file. Reads come from memory under a read lock; every write rewrites the file to a
 * temporary sibling and atomically moves it into place, so a crash never leaves a half-written file. The
 * file is created owner-read/write only where the file system supports it.
 */
public final class FileUserStore implements UserStore {

    private final Path file;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, User> users = new TreeMap<>();

    public FileUserStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
        if (Files.exists(this.file)) {
            try {
                List<User> loaded = json.readValue(this.file.toFile(), new TypeReference<List<User>>() {});
                loaded.forEach(u -> users.put(u.username(), u));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read users from " + this.file, e);
            }
        }
    }

    @Override
    public Optional<User> find(String username) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(users.get(username));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<User> all() {
        lock.readLock().lock();
        try {
            List<User> out = new ArrayList<>(users.values());
            out.sort(Comparator.comparing(User::username));
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void put(User user) {
        lock.writeLock().lock();
        try {
            User previous = users.put(user.username(), user);
            try {
                flush();
            } catch (IOException e) {
                if (previous == null) {
                    users.remove(user.username());
                } else {
                    users.put(user.username(), previous);
                }
                throw new UncheckedIOException("cannot write users to " + file, e);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean delete(String username) {
        lock.writeLock().lock();
        try {
            User removed = users.remove(username);
            if (removed == null) {
                return false;
            }
            try {
                flush();
            } catch (IOException e) {
                users.put(username, removed);
                throw new UncheckedIOException("cannot write users to " + file, e);
            }
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void flush() throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        json.writeValue(tmp.toFile(), new ArrayList<>(users.values()));
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX file system
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
