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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * The outbox as files: {@code <dir>/outbox/{pending,sent,dead}/<seq>.json}, one file per delivery, written to a temporary name and moved
 * into place atomically (a pending or sending delivery lives in {@code pending}, a cancelled one beside the dead letters). The folders are
 * read once at first use; every change is serialised by one lock. One server only.
 */
public final class FileOutboxStore implements OutboxStore {

    private final Path root;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final TreeMap<Long, OutboxItem> items = new TreeMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private long seq;
    private boolean loaded;

    public FileOutboxStore(Path dir) {
        this.root = dir.toAbsolutePath().normalize().resolve("outbox");
    }

    private static String folder(String state) {
        return switch (state) {
            case OutboxItem.SENT -> "sent";
            case OutboxItem.DEAD, OutboxItem.CANCELLED -> "dead";
            default -> "pending";
        };
    }

    private Path file(OutboxItem i) {
        return root.resolve(folder(i.state())).resolve(i.seq() + ".json");
    }

    private void load() {
        if (loaded) {
            return;
        }
        try {
            for (String f : List.of("pending", "sent", "dead")) {
                Path d = root.resolve(f);
                if (Files.isDirectory(d)) {
                    try (Stream<Path> files = Files.list(d)) {
                        for (Path p : files.filter(x -> x.toString().endsWith(".json")).toList()) {
                            OutboxItem i = json.readValue(p.toFile(), OutboxItem.class);
                            items.put(i.seq(), i);
                            seq = Math.max(seq, i.seq());
                        }
                    }
                }
            }
            Path hw = root.resolve("high-water");
            if (Files.isRegularFile(hw)) {
                try {
                    seq = Math.max(seq, Long.parseLong(Files.readString(hw).trim()));
                } catch (NumberFormatException ignored) {
                    // an unreadable mark is ignored; the files still give the floor
                }
            }
            loaded = true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes the new state and removes the file of the old one. Caller holds the lock. */
    private void put(OutboxItem old, OutboxItem now) {
        try {
            Path target = file(now);
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(now.seq() + ".tmp");
            json.writeValue(tmp.toFile(), now);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (old != null && !file(old).equals(target)) {
                Files.deleteIfExists(file(old));
            }
            items.put(now.seq(), now);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> T locked(Supplier<T> work) {
        lock.lock();
        try {
            load();
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public OutboxItem add(OutboxItem i) {
        return locked(() -> {
            OutboxItem n = i.withSeq(++seq);
            put(null, n);
            return n;
        });
    }

    @Override
    public Optional<OutboxItem> find(long s) {
        return locked(() -> Optional.ofNullable(items.get(s)));
    }

    @Override
    public List<OutboxItem> claim(Instant now, int limit, Instant leaseUntil, String owner) {
        return locked(() -> {
            List<OutboxItem> won = new ArrayList<>();
            for (OutboxItem i : new ArrayList<>(items.values())) {
                if (won.size() >= Math.max(1, limit)) {
                    break;
                }
                boolean due = OutboxItem.PENDING.equals(i.state()) && !i.nextAt().isAfter(now)
                        || OutboxItem.SENDING.equals(i.state()) && i.leaseUntil() != null && i.leaseUntil().isBefore(now);
                if (due) {
                    OutboxItem c = i.with(OutboxItem.SENDING, i.attempts(), i.nextAt(), leaseUntil, owner, i.lastError(), null);
                    put(i, c);
                    won.add(c);
                }
            }
            return won;
        });
    }

    private void change(long s, UnaryOperator<OutboxItem> edit) {
        locked(() -> {
            OutboxItem old = items.get(s);
            if (old != null) {
                put(old, edit.apply(old));
            }
            return null;
        });
    }

    @Override
    public void sent(long s, Instant at) {
        change(s, i -> i.with(OutboxItem.SENT, i.attempts(), i.nextAt(), null, null, null, at));
    }

    @Override
    public void retry(long s, int attempts, Instant nextAt, String error) {
        change(s, i -> i.with(OutboxItem.PENDING, attempts, nextAt, null, null, JpaOutboxStore.cut(error), null));
    }

    @Override
    public void dead(long s, int attempts, String error) {
        change(s, i -> i.with(OutboxItem.DEAD, attempts, i.nextAt(), null, null, JpaOutboxStore.cut(error), null));
    }

    @Override
    public void cancel(long s, String reason) {
        change(s, i -> i.with(OutboxItem.CANCELLED, i.attempts(), i.nextAt(), null, null, JpaOutboxStore.cut(reason), null));
    }

    @Override
    public boolean requeue(long s, Instant at) {
        return locked(() -> {
            OutboxItem old = items.get(s);
            if (old == null || !(OutboxItem.DEAD.equals(old.state()) || OutboxItem.CANCELLED.equals(old.state()))) {
                return false;
            }
            put(old, old.with(OutboxItem.PENDING, 0, at, null, null, old.lastError(), null));
            return true;
        });
    }

    @Override
    public List<OutboxItem> list(String state, int limit) {
        return locked(() -> items.descendingMap().values().stream()
                .filter(i -> state == null || state.isBlank() || state.equals(i.state())).limit(Math.max(1, limit)).toList());
    }

    @Override
    public List<OutboxItem> after(long afterSeq, int limit) {
        return locked(() -> items.tailMap(afterSeq, false).values().stream().limit(Math.max(1, limit)).toList());
    }

    @Override
    public Map<String, Long> counts() {
        return locked(() -> {
            Map<String, Long> out = new LinkedHashMap<>();
            for (String s : List.of(OutboxItem.PENDING, OutboxItem.SENDING, OutboxItem.SENT, OutboxItem.DEAD, OutboxItem.CANCELLED)) {
                out.put(s, items.values().stream().filter(i -> s.equals(i.state())).count());
            }
            return out;
        });
    }

    @Override
    public long countSince(String recipient, Instant since) {
        return locked(() -> items.values().stream().filter(i -> i.recipient().equals(recipient) && !i.createdAt().isBefore(since)).count());
    }

    @Override
    public int purgeSent(Instant before) {
        return locked(() -> {
            List<OutboxItem> gone = items.values().stream()
                    .filter(i -> OutboxItem.SENT.equals(i.state()) && i.sentAt() != null && i.sentAt().isBefore(before)).toList();
            try {
                if (!gone.isEmpty()) {
                    Files.createDirectories(root);
                    Path tmp = root.resolve("high-water.tmp");
                    Files.writeString(tmp, Long.toString(seq));
                    Files.move(tmp, root.resolve("high-water"), StandardCopyOption.REPLACE_EXISTING);
                }
                for (OutboxItem i : gone) {
                    Files.deleteIfExists(file(i));
                    items.remove(i.seq());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return gone.size();
        });
    }
}
