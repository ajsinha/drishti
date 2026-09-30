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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Per-user documents grouped by namespace ({@code workspaces}, {@code settings}, ...): one JSON file per
 * user, written atomically, with bounded sizes so a user cannot fill the disk. Writes for one user are
 * serialised; different users never contend.
 */
public final class PreferenceStore {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}");
    private final Path dir;
    private final int maxDocBytes;
    private final int maxPerNamespace;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public PreferenceStore(Path dir, int maxDocBytes, int maxPerNamespace) {
        this.dir = dir.toAbsolutePath().normalize();
        this.maxDocBytes = maxDocBytes;
        this.maxPerNamespace = maxPerNamespace;
    }

    private Path file(String user) {
        Path p = dir.resolve(user.replaceAll("[^a-z0-9._-]", "_") + ".json").normalize();
        if (!p.startsWith(dir)) {
            throw new DrishtiException(ErrorCode.INVALID_USER, "bad user name");
        }
        return p;
    }

    private ObjectNode load(String user) {
        Path f = file(user);
        try {
            return Files.exists(f) ? (ObjectNode) json.readTree(f.toFile()) : json.createObjectNode();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<String> keys(String user, String namespace) {
        List<String> out = new ArrayList<>();
        JsonNode ns = load(user).path(namespace);
        ns.fieldNames().forEachRemaining(out::add);
        out.sort(String::compareToIgnoreCase);
        return out;
    }

    public Optional<JsonNode> get(String user, String namespace, String key) {
        JsonNode n = load(user).path(namespace).get(key);
        return Optional.ofNullable(n);
    }

    public void put(String user, String namespace, String key, JsonNode value) {
        if (!KEY.matcher(key).matches()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "names are 1-64 of letters, digits, space . _ -");
        }
        try {
            if (json.writeValueAsBytes(value).length > maxDocBytes) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "document larger than " + maxDocBytes + " bytes");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ReentrantLock lock = lockOf(user);
        lock.lock();
        try {
            ObjectNode all = load(user);
            ObjectNode ns = all.has(namespace) ? (ObjectNode) all.get(namespace) : all.putObject(namespace);
            if (!ns.has(key) && ns.size() >= maxPerNamespace) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "at most " + maxPerNamespace + " " + namespace);
            }
            ns.set(key, value);
            write(user, all);
        } finally {
            lock.unlock();
        }
    }

    public boolean delete(String user, String namespace, String key) {
        ReentrantLock lock = lockOf(user);
        lock.lock();
        try {
            ObjectNode all = load(user);
            JsonNode ns = all.get(namespace);
            if (ns == null || ns.get(key) == null) {
                return false;
            }
            ((ObjectNode) ns).remove(key);
            write(user, all);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Users who have stored anything (one file each). */
    public List<String> users() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (var s = Files.list(dir)) {
            s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).forEach(n -> out.add(n.substring(0, n.length() - 5)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** Removes every document of a user (called when the user is deleted). */
    public void forget(String user) {
        ReentrantLock lock = lockOf(user);    // the same lock as put, so a concurrent put cannot bring the file back
        lock.lock();
        try {
            Files.deleteIfExists(file(user));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.unlock();
        }
    }

    /** Per-user lock; a ReentrantLock because the guarded work is file I/O, which would pin a virtual thread under synchronized. */
    private ReentrantLock lockOf(String user) {
        return locks.computeIfAbsent(user, u -> new ReentrantLock());
    }

    private void write(String user, ObjectNode all) {
        Path f = file(user);
        try {
            Files.createDirectories(dir);
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), all);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Iterator<String> names(JsonNode n) {
        return n.fieldNames();
    }
}
