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
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * The inbox as files: {@code <dir>/inbox/<hex of user>.jsonl}, append-only: a line per row, and a line per mark-as-read. The
 * files are read once at first use into memory (rows and read marks folded together); sequence numbers continue from the highest
 * stored. One server only. Appends are serialised per user with a {@link ReentrantLock} and synced.
 */
public final class FileInboxStore implements InboxStore {

    private final Path root;
    private final int keep;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Map<String, List<Notice>> rows = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final ReentrantLock loading = new ReentrantLock();
    private volatile boolean loaded;

    public FileInboxStore(Path dir, int keep) {
        this.root = dir.toAbsolutePath().normalize().resolve("inbox");
        this.keep = Math.max(1, keep);
    }

    private static String name(String user) {
        return HexFormat.of().formatHex(user.getBytes(StandardCharsets.UTF_8));
    }

    private Path file(String user) {
        return root.resolve(name(user) + ".jsonl");
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
                try (Stream<Path> files = Files.list(root)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".jsonl")).toList()) {
                        List<Notice> list = new ArrayList<>();
                        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                            if (!line.isBlank()) {
                                apply(list, json.readTree(line));
                            }
                        }
                        if (!list.isEmpty()) {
                            rows.put(list.get(0).username(), list);
                        }
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

    private void apply(List<Notice> list, JsonNode line) throws IOException {
        if (line.has("n")) {
            Notice n = json.treeToValue(line.get("n"), Notice.class);
            list.add(n);
            seq.accumulateAndGet(n.seq(), Math::max);
        } else if (line.has("read")) {
            JsonNode r = line.get("read");
            Instant at = Instant.parse(r.get("at").asText());
            long upTo = r.path("upTo").asLong(0);
            List<Long> only = new ArrayList<>();
            r.path("seqs").forEach(x -> only.add(x.asLong()));
            for (int i = 0; i < list.size(); i++) {
                Notice n = list.get(i);
                if (n.readAt() == null && (n.seq() <= upTo || only.contains(n.seq()))) {
                    list.set(i, n.withRead(at));
                }
            }
        }
    }

    private void append(String user, ObjectNode line) {
        try {
            Files.createDirectories(root);
            Files.writeString(file(user), json.writeValueAsString(line) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ReentrantLock lock(String user) {
        return locks.computeIfAbsent(user, u -> new ReentrantLock());
    }

    @Override
    public Notice add(Notice notice) {
        load();
        ReentrantLock l = lock(notice.username());
        l.lock();
        try {
            Notice n = notice.withSeq(seq.incrementAndGet());
            ObjectNode line = json.createObjectNode();
            line.set("n", json.valueToTree(n));
            append(n.username(), line);
            List<Notice> list = rows.computeIfAbsent(n.username(), u -> new ArrayList<>());
            list.add(n);
            while (list.size() > keep) {
                list.remove(0);
            }
            return n;
        } finally {
            l.unlock();
        }
    }

    private List<Notice> snapshot(String user) {
        load();
        ReentrantLock l = lock(user);
        l.lock();
        try {
            return new ArrayList<>(rows.getOrDefault(user, List.of()));
        } finally {
            l.unlock();
        }
    }

    @Override
    public List<Notice> list(String username, String type, boolean unreadOnly, int limit, long beforeSeq) {
        List<Notice> all = snapshot(username);
        List<Notice> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && out.size() < Math.max(1, limit); i--) {
            Notice n = all.get(i);
            if ((type == null || type.isBlank() || type.equals(n.type())) && (!unreadOnly || n.readAt() == null)
                    && (beforeSeq <= 0 || n.seq() < beforeSeq)) {
                out.add(n);
            }
        }
        return out;
    }

    @Override
    public long unread(String username) {
        return snapshot(username).stream().filter(n -> n.readAt() == null).count();
    }

    @Override
    public int markRead(String username, Collection<Long> seqs, Instant at) {
        return mark(username, seqs, 0, at);
    }

    @Override
    public int markReadUpTo(String username, long upTo, Instant at) {
        return mark(username, List.of(), upTo, at);
    }

    private int mark(String user, Collection<Long> seqs, long upTo, Instant at) {
        load();
        ReentrantLock l = lock(user);
        l.lock();
        try {
            List<Notice> list = rows.getOrDefault(user, new ArrayList<>());
            int changed = 0;
            for (int i = 0; i < list.size(); i++) {
                Notice n = list.get(i);
                if (n.readAt() == null && (n.seq() <= upTo || seqs.contains(n.seq()))) {
                    list.set(i, n.withRead(at));
                    changed++;
                }
            }
            if (changed > 0) {
                ObjectNode r = json.createObjectNode();
                r.put("at", at.toString());
                r.put("upTo", upTo);
                r.putArray("seqs").addAll(seqs.stream().map(json.getNodeFactory()::numberNode).toList());
                ObjectNode line = json.createObjectNode();
                line.set("read", r);
                append(user, line);
            }
            return changed;
        } finally {
            l.unlock();
        }
    }

    @Override
    public List<Notice> after(long afterSeq, int limit) {
        load();
        List<Notice> out = new ArrayList<>();
        for (String user : List.copyOf(rows.keySet())) {
            snapshot(user).stream().filter(n -> n.seq() > afterSeq).forEach(out::add);
        }
        out.sort(java.util.Comparator.comparingLong(Notice::seq));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    @Override
    public long maxSeq() {
        load();
        return seq.get();
    }

    @Override
    public void prune(String username, int keepRows) {
        load();
        ReentrantLock l = lock(username);
        l.lock();
        try {
            List<Notice> list = rows.get(username);
            while (list != null && list.size() > Math.max(1, keepRows)) {
                list.remove(0);
            }
        } finally {
            l.unlock();
        }
    }

    @Override
    public void forget(String username) {
        load();
        ReentrantLock l = lock(username);
        l.lock();
        try {
            rows.remove(username);
            Files.deleteIfExists(file(username));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            l.unlock();
        }
    }
}
