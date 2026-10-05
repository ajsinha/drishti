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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Shares as files: {@code <dir>/shares/<yyyy-MM>/<id>.json}, each holding the share and its recipients, written whole to a
 * sibling and moved into place. The month is the one the id was made in (an id carries its time), so a share is found without a
 * scan; the lists are served from an index read once at first use. One server only (the server refuses a file store beside a
 * shared database). Writes are serialised per share with a {@link ReentrantLock}.
 */
public final class FileShareStore implements ShareStore {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);

    /** What one file holds. */
    public record Entry(Share share, List<Recipient> recipients) {}

    private final Path root;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Map<String, Entry> index = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private volatile boolean loaded;
    private final ReentrantLock loading = new ReentrantLock();

    public FileShareStore(Path dir) {
        this.root = dir.toAbsolutePath().normalize().resolve("shares");
    }

    private void load() {
        if (loaded) {
            return;
        }
        loading.lock();
        try {
            if (loaded) {
                return;
            }
            if (Files.isDirectory(root)) {
                try (Stream<Path> files = Files.walk(root, 2)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                        Entry e = json.readValue(f.toFile(), Entry.class);
                        index.put(e.share().id(), e);
                    }
                }
            }
            loaded = true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            loading.unlock();
        }
    }

    private Path file(Share s) {
        return root.resolve(MONTH.format(s.createdAt())).resolve(s.id() + ".json");
    }

    private void write(Entry e) {
        Path f = file(e.share());
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            json.writeValue(tmp.toFile(), e);
            try {
                Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public void save(Share share, List<Recipient> recipients) {
        load();
        ReentrantLock lock = locks.computeIfAbsent(share.id(), k -> new ReentrantLock());
        lock.lock();
        try {
            Entry e = new Entry(share, List.copyOf(recipients));
            write(e);
            index.put(share.id(), e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Share> find(String id) {
        load();
        return Optional.ofNullable(index.get(id)).map(Entry::share);
    }

    @Override
    public List<Recipient> recipients(String shareId) {
        load();
        Entry e = index.get(shareId);
        return e == null ? List.of() : e.recipients();
    }

    @Override
    public boolean markOpened(String shareId, String username, Instant at) {
        load();
        ReentrantLock lock = locks.computeIfAbsent(shareId, k -> new ReentrantLock());
        lock.lock();
        try {
            Entry e = index.get(shareId);
            if (e == null) {
                return false;
            }
            boolean set = false;
            List<Recipient> out = new ArrayList<>();
            for (Recipient r : e.recipients()) {
                if (r.username().equals(username) && r.openedAt() == null) {
                    out.add(r.withOpened(at));
                    set = true;
                } else {
                    out.add(r);
                }
            }
            if (set) {
                Entry n = new Entry(e.share(), out);
                write(n);
                index.put(shareId, n);
            }
            return set;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Share> sent(String sender, int limit, String beforeId) {
        load();
        return index.values().stream().map(Entry::share).filter(s -> s.sender().equals(sender))
                .filter(s -> beforeId == null || beforeId.isBlank() || s.id().compareTo(beforeId) < 0)
                .sorted(Comparator.comparing(Share::id).reversed()).limit(Math.max(1, limit)).toList();
    }

    @Override
    public List<Share> received(String username, int limit, String beforeId) {
        load();
        return index.values().stream()
                .filter(e -> e.recipients().stream().anyMatch(r -> r.username().equals(username) && Recipient.NOTIFIED.equals(r.state())))
                .map(Entry::share).filter(s -> beforeId == null || beforeId.isBlank() || s.id().compareTo(beforeId) < 0)
                .sorted(Comparator.comparing(Share::id).reversed()).limit(Math.max(1, limit)).toList();
    }

    @Override
    public long countSentSince(String sender, Instant since) {
        load();
        return index.values().stream().map(Entry::share).filter(s -> s.sender().equals(sender) && !s.createdAt().isBefore(since)).count();
    }
}
