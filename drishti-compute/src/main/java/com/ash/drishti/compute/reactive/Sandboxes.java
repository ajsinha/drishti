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
package com.ash.drishti.compute.reactive;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Private what-if sandboxes (REACTIVE_CONNECTORS.md section 9.1, rule 3): each owned by one user, who alone can read, change or compute
 * it. A request naming another user's sandbox is answered <em>not found</em> (it does not even confirm the sandbox exists). A sandbox is
 * shared as a frozen copy owned by the recipient, never as a live link. A trigger never recomputes a sandbox: it changes only when its
 * owner acts. Kept under {@code <data dir>/compute/sandboxes/<owner hash>/<id>.json}, so they survive a restart.
 */
public final class Sandboxes {

    /**
     * One sandbox.
     *
     * @param id {@code sbx-...}
     * @param owner the user who owns it
     * @param connector the reactive connector it is for
     * @param title the owner's name for it
     * @param items the hypothetical changes (an amended trade, a trade not booked, a position unwound), as the engine reads them
     * @param version raised by every change; part of the key of every result computed on it
     * @param frozen a copy shared by someone else: read-only
     * @param from the sandbox it was copied from, or null
     * @param created when it was made
     * @param updated when it last changed
     */
    public record Sandbox(String id, String owner, String connector, String title, List<Map<String, Object>> items, long version, boolean frozen,
            String from, Instant created, Instant updated) {

        public Sandbox {
            items = items == null ? List.of() : List.copyOf(items);
        }

        Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("connector", connector);
            m.put("title", title);
            m.put("items", items);
            m.put("version", version);
            m.put("frozen", frozen);
            m.put("from", from);
            m.put("created", created.toString());
            m.put("updated", updated.toString());
            return m;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_ITEMS = 10_000;

    private final Path root;
    private final Clock clock;
    private final Map<String, Sandbox> byId = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean loaded;

    public Sandboxes(Path root, Clock clock) {
        this.root = root;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    private void load() {
        if (loaded || root == null) {
            loaded = true;
            return;
        }
        lock.lock();
        try {
            if (loaded) {
                return;
            }
            if (Files.isDirectory(root)) {
                try (Stream<Path> files = Files.walk(root, 2)) {
                    for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                        try {
                            Map<String, Object> m = JSON.readValue(Files.readString(f, StandardCharsets.UTF_8), new TypeReference<Map<String, Object>>() {});
                            Sandbox s = fromMap(m);
                            byId.put(s.id(), s);
                        } catch (IOException | RuntimeException e) {
                            org.slf4j.LoggerFactory.getLogger(Sandboxes.class).warn("reactive: sandbox file {} is not readable: {}", f, e.getMessage());
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            loaded = true;
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private static Sandbox fromMap(Map<String, Object> m) {
        return new Sandbox(String.valueOf(m.get("id")), String.valueOf(m.get("owner")), String.valueOf(m.get("connector")), String.valueOf(m.getOrDefault("title", "")),
                (List<Map<String, Object>>) m.getOrDefault("items", List.of()), ((Number) m.getOrDefault("version", 1)).longValue(),
                Boolean.TRUE.equals(m.get("frozen")), m.get("from") == null ? null : String.valueOf(m.get("from")), Instant.parse(String.valueOf(m.get("created"))),
                Instant.parse(String.valueOf(m.get("updated"))));
    }

    /** A new sandbox of {@code owner} for a connector. */
    public Sandbox create(String owner, String connector, String title, List<Map<String, Object>> items) {
        load();
        check(items);
        byte[] r = new byte[8];
        RANDOM.nextBytes(r);
        Instant now = clock.instant();
        Sandbox s = new Sandbox("sbx-" + Long.toString(now.toEpochMilli(), 36) + "-" + HexFormat.of().formatHex(r), owner, connector,
                title == null ? "" : title, items, 1, false, null, now, now);
        save(s);
        return s;
    }

    /** The owner's sandbox; empty for an id that is not the owner's (whether or not it exists). */
    public Optional<Sandbox> get(String owner, String id) {
        load();
        Sandbox s = id == null ? null : byId.get(id);
        return s != null && s.owner().equals(owner) ? Optional.of(s) : Optional.empty();
    }

    /** The owner's sandboxes, newest first. */
    public List<Map<String, Object>> list(String owner) {
        load();
        List<Sandbox> mine = new ArrayList<>(byId.values().stream().filter(s -> s.owner().equals(owner)).toList());
        mine.sort((a, b) -> b.updated().compareTo(a.updated()));
        return mine.stream().map(Sandbox::describe).toList();
    }

    /** Replaces the items of the owner's sandbox; refused for a frozen copy or when {@code basedOn} is not its version. */
    public Sandbox update(String owner, String id, List<Map<String, Object>> items, Long basedOn) {
        lock.lock();
        try {
            Sandbox s = get(owner, id).orElseThrow(() -> new IllegalArgumentException("no sandbox " + id));
            if (s.frozen()) {
                throw new IllegalStateException("sandbox " + id + " is a frozen copy: copy it to change it");
            }
            if (basedOn != null && basedOn != s.version()) {
                throw new IllegalStateException("sandbox " + id + " is at version " + s.version() + ", not " + basedOn + ": it changed meanwhile");
            }
            check(items);
            Sandbox n = new Sandbox(s.id(), owner, s.connector(), s.title(), items, s.version() + 1, false, s.from(), s.created(), clock.instant());
            save(n);
            return n;
        } finally {
            lock.unlock();
        }
    }

    /** Gives {@code recipient} a frozen copy of the owner's sandbox (never a live link). */
    public Sandbox copyFor(String owner, String id, String recipient) {
        Sandbox s = get(owner, id).orElseThrow(() -> new IllegalArgumentException("no sandbox " + id));
        Sandbox c = create(recipient, s.connector(), s.title(), s.items());
        Sandbox frozen = new Sandbox(c.id(), recipient, c.connector(), c.title(), c.items(), 1, true, s.id(), c.created(), c.updated());
        save(frozen);
        return frozen;
    }

    public boolean delete(String owner, String id) {
        Optional<Sandbox> s = get(owner, id);
        if (s.isEmpty()) {
            return false;
        }
        byId.remove(id);
        if (root != null) {
            try {
                Files.deleteIfExists(file(s.get()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return true;
    }

    private static void check(List<Map<String, Object>> items) {
        if (items != null && items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("a sandbox holds at most " + MAX_ITEMS + " items");
        }
    }

    private Path file(Sandbox s) {
        return root.resolve(ownerFolder(s.owner())).resolve(s.id() + ".json");
    }

    static String ownerFolder(String owner) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(owner.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void save(Sandbox s) {
        byId.put(s.id(), s);
        if (root == null) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>(s.describe());
        m.put("owner", s.owner());
        try {
            Path f = file(s);
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(s.id() + ".json.part");
            Files.writeString(tmp, JSON.writeValueAsString(m), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("could not keep sandbox " + s.id(), e);
        }
    }
}
