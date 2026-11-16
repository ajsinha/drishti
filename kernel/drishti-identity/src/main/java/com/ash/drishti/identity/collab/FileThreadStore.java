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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Comment threads as files: {@code <dir>/threads/<kind>/<sha1 of the entity id>.jsonl}, one append-only log per entity holding every
 * thread, comment (with its sealed revision and mentions) and follow event, folded into memory once at first use; and
 * {@code <dir>/threads/note-links.jsonl} for the legacy-note links. Every append is fsync'd. One server only (the server refuses a file
 * store beside a shared database). All access is serialised by one {@link ReentrantLock}; nothing is held across anything but the
 * append itself.
 */
public final class FileThreadStore implements ThreadStore {

    private final Path root;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final ReentrantLock lock = new ReentrantLock();
    private boolean loaded;
    private final Map<String, CommentThread> threads = new HashMap<>();
    private final Map<String, List<String>> byEntity = new HashMap<>();
    private final Map<String, Comment> comments = new HashMap<>();
    private final Map<String, List<String>> commentIds = new HashMap<>();
    private final Map<String, List<Revision>> revisions = new HashMap<>();
    private final Map<String, List<Revision>> chains = new HashMap<>();
    private final Map<String, Set<String>> mentionTargets = new HashMap<>();
    private final List<Mention> mentionList = new ArrayList<>();
    private final Map<String, Follow> follows = new HashMap<>();
    private final Map<Long, String> noteLinks = new TreeMap<>();

    public FileThreadStore(Path dir) {
        this.root = dir.toAbsolutePath().normalize().resolve("threads");
    }

    // ---- loading and appending ---------------------------------------------------------------------------------------

    private void load() {
        if (loaded) {
            return;
        }
        try {
            if (Files.isDirectory(root)) {
                try (Stream<Path> files = Files.walk(root, 2)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList()) {
                        try (BufferedReader in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                            for (String line; (line = in.readLine()) != null; ) {
                                if (!line.isBlank()) {
                                    fold(json.readTree(line));
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        loaded = true;
    }

    private void fold(JsonNode e) throws IOException {
        switch (e.path("t").asText()) {
            case "thread" -> putThread(json.treeToValue(e.get("thread"), CommentThread.class));
            case "comment" -> putComment(json.treeToValue(e.get("comment"), Comment.class), json.treeToValue(e.get("revision"), Revision.class),
                    mentionTargetsOf(e));
            case "follow" -> {
                Follow f = json.treeToValue(e.get("follow"), Follow.class);
                follows.put(f.threadId() + "\u0000" + f.username(), f);
            }
            case "unfollow" -> follows.remove(e.path("threadId").asText() + "\u0000" + e.path("username").asText());
            case "note-link" -> noteLinks.put(e.path("noteId").asLong(), e.path("commentId").asText());
            default -> { }
        }
    }

    private List<String> mentionTargetsOf(JsonNode e) {
        List<String> out = new ArrayList<>();
        e.path("mentions").forEach(n -> out.add(n.asText()));
        return out;
    }

    private void putThread(CommentThread t) {
        if (!threads.containsKey(t.id())) {
            byEntity.computeIfAbsent(t.kind() + "\u0000" + t.entityId(), k -> new ArrayList<>()).add(t.id());
        }
        threads.put(t.id(), t);
    }

    private void putComment(Comment c, Revision r, Collection<String> targets) {
        if (!comments.containsKey(c.id())) {
            commentIds.computeIfAbsent(c.threadId(), k -> new ArrayList<>()).add(c.id());
        }
        comments.put(c.id(), c);
        revisions.computeIfAbsent(c.id(), k -> new ArrayList<>()).add(r);
        chains.computeIfAbsent(c.threadId(), k -> new ArrayList<>()).add(r);
        Set<String> have = mentionTargets.computeIfAbsent(c.id(), k -> new LinkedHashSet<>());
        for (String t : targets) {
            if (have.add(t)) {
                mentionList.add(new Mention(c.id(), t, r.at()));
            }
        }
    }

    private Path fileOf(String kind, String entityId) {
        return root.resolve(kind.replaceAll("[^A-Za-z0-9._-]", "_")).resolve(sha1(entityId) + ".jsonl");
    }

    private void append(Path file, ObjectNode event) {
        try {
            Files.createDirectories(file.getParent());
            byte[] bytes = (json.writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ch.write(java.nio.ByteBuffer.wrap(bytes));
                ch.force(true);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private ObjectNode event(String type) {
        ObjectNode n = json.createObjectNode();
        n.put("t", type);
        return n;
    }

    private static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- ThreadStore -------------------------------------------------------------------------------------------------

    @Override
    public void saveThread(CommentThread t) {
        lock.lock();
        try {
            load();
            ObjectNode e = event("thread");
            e.set("thread", json.valueToTree(t));
            append(fileOf(t.kind(), t.entityId()), e);
            putThread(t);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Revision append(Comment c, Revision draft, Collection<String> targets) {
        lock.lock();
        try {
            load();
            CommentThread t = threads.get(c.threadId());
            if (t == null) {
                throw new IllegalStateException("no thread " + c.threadId());
            }
            List<Revision> chain = chains.getOrDefault(c.threadId(), List.of());
            Revision last = chain.isEmpty() ? null : chain.get(chain.size() - 1);
            Revision sealed = HashChain.seal(last == null ? HashChain.genesis(c.threadId()) : last.hash(),
                    new Revision(draft.commentId(), draft.revision(), HashChain.after(draft.at(), last == null ? null : last.at()), draft.actor(),
                            draft.action(), draft.body(), draft.reason(), "", ""));
            ObjectNode e = event("comment");
            e.set("comment", json.valueToTree(c));
            e.set("revision", json.valueToTree(sealed));
            e.set("mentions", json.valueToTree(new ArrayList<>(targets)));
            append(fileOf(t.kind(), t.entityId()), e);
            putComment(c, sealed, targets);
            return sealed;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<CommentThread> thread(String id) {
        lock.lock();
        try {
            load();
            return Optional.ofNullable(threads.get(id));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<CommentThread> threads(String kind, String entityId) {
        lock.lock();
        try {
            load();
            return byEntity.getOrDefault(kind + "\u0000" + entityId, List.of()).stream().map(threads::get)
                    .sorted(Comparator.comparing(CommentThread::lastAt).reversed().thenComparing(CommentThread::id, Comparator.reverseOrder()))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<CommentThread> page(String afterId, int limit) {
        lock.lock();
        try {
            load();
            return threads.values().stream().filter(t -> afterId == null || t.id().compareTo(afterId) > 0)
                    .sorted(Comparator.comparing(CommentThread::id)).limit(Math.max(1, limit)).toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void deleteThread(String id) {
        lock.lock();
        try {
            load();
            CommentThread t = threads.remove(id);
            if (t == null) {
                return;
            }
            rewriteWithout(fileOf(t.kind(), t.entityId()), id);
            List<String> key = byEntity.get(t.kind() + "\u0000" + t.entityId());
            if (key != null) {
                key.remove(id);
            }
            List<String> cids = commentIds.remove(id);
            if (cids != null) {
                for (String c : cids) {
                    comments.remove(c);
                    revisions.remove(c);
                    mentionTargets.remove(c);
                    noteLinks.values().removeIf(c::equals);
                }
                mentionList.removeIf(m -> cids.contains(m.commentId()));
            }
            chains.remove(id);
            follows.keySet().removeIf(k -> k.startsWith(id + "\u0000"));
        } finally {
            lock.unlock();
        }
    }

    /** Rewrites the entity's log without the thread's events (to a sibling, then moved into place), or deletes it when nothing is left. */
    private void rewriteWithout(Path file, String threadId) {
        try {
            if (!Files.isRegularFile(file)) {
                return;
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            long kept = 0;
            try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                    java.io.BufferedWriter out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (String line; (line = in.readLine()) != null; ) {
                    if (line.isBlank() || threadId.equals(threadOf(json.readTree(line)))) {
                        continue;
                    }
                    out.write(line);
                    out.newLine();
                    kept++;
                }
            }
            if (kept == 0) {
                Files.deleteIfExists(tmp);
                Files.deleteIfExists(file);
            } else {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String threadOf(JsonNode e) {
        return switch (e.path("t").asText()) {
            case "thread" -> e.path("thread").path("id").asText();
            case "comment" -> e.path("comment").path("threadId").asText();
            case "follow" -> e.path("follow").path("threadId").asText();
            case "unfollow" -> e.path("threadId").asText();
            default -> "";
        };
    }

    /** Re-reads every file (after the files were restored or changed from outside; the server never needs it). */
    public void reload() {
        lock.lock();
        try {
            threads.clear();
            byEntity.clear();
            comments.clear();
            commentIds.clear();
            revisions.clear();
            chains.clear();
            mentionTargets.clear();
            mentionList.clear();
            follows.clear();
            noteLinks.clear();
            loaded = false;
            load();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Comment> comment(String id) {
        lock.lock();
        try {
            load();
            return Optional.ofNullable(comments.get(id));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Comment> comments(String threadId) {
        lock.lock();
        try {
            load();
            return commentIds.getOrDefault(threadId, List.of()).stream().map(comments::get)
                    .sorted(Comparator.comparing(Comment::createdAt).thenComparing(Comment::id)).toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Revision> revisions(String commentId) {
        lock.lock();
        try {
            load();
            return List.copyOf(revisions.getOrDefault(commentId, List.of()));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Revision> chain(String threadId) {
        lock.lock();
        try {
            load();
            return List.copyOf(chains.getOrDefault(threadId, List.of()));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void follow(Follow f) {
        lock.lock();
        try {
            load();
            CommentThread t = threads.get(f.threadId());
            if (t == null) {
                throw new IllegalStateException("no thread " + f.threadId());
            }
            ObjectNode e = event("follow");
            e.set("follow", json.valueToTree(f));
            append(fileOf(t.kind(), t.entityId()), e);
            follows.put(f.threadId() + "\u0000" + f.username(), f);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean unfollow(String threadId, String username) {
        lock.lock();
        try {
            load();
            CommentThread t = threads.get(threadId);
            if (t == null || !follows.containsKey(threadId + "\u0000" + username)) {
                return false;
            }
            ObjectNode e = event("unfollow");
            e.put("threadId", threadId);
            e.put("username", username);
            append(fileOf(t.kind(), t.entityId()), e);
            follows.remove(threadId + "\u0000" + username);
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Follow> following(String threadId, String username) {
        lock.lock();
        try {
            load();
            return Optional.ofNullable(follows.get(threadId + "\u0000" + username));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Follow> followers(String threadId) {
        lock.lock();
        try {
            load();
            return follows.values().stream().filter(f -> f.threadId().equals(threadId)).toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Mention> mentionsOf(Collection<String> targets, int limit, String before) {
        lock.lock();
        try {
            load();
            return mentionList.stream().filter(m -> targets.contains(m.target()) && (before == null || before.isBlank()
                    || m.commentId().compareTo(before) < 0))
                    .sorted(Comparator.comparing(Mention::commentId).reversed().thenComparing(Mention::target)).limit(Math.max(1, limit)).toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Mention> mentions(String commentId) {
        lock.lock();
        try {
            load();
            return mentionList.stream().filter(m -> m.commentId().equals(commentId)).toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void linkNote(long noteId, String commentId) {
        lock.lock();
        try {
            load();
            ObjectNode e = event("note-link");
            e.put("noteId", noteId);
            e.put("commentId", commentId);
            append(root.resolve("note-links.jsonl"), e);
            noteLinks.put(noteId, commentId);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<String> commentOfNote(long noteId) {
        lock.lock();
        try {
            load();
            return Optional.ofNullable(noteLinks.get(noteId));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Long> noteOfComment(String commentId) {
        lock.lock();
        try {
            load();
            return noteLinks.entrySet().stream().filter(e -> e.getValue().equals(commentId)).map(Map.Entry::getKey).findFirst();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Set<Long> linkedNotes() {
        lock.lock();
        try {
            load();
            return Set.copyOf(noteLinks.keySet());
        } finally {
            lock.unlock();
        }
    }
}
