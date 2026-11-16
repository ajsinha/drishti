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

import com.ash.drishti.identity.content.ContentDocs;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.identity.content.TxContentStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Comment threads in the content store (DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 9), so every node of a cluster sees the same discussion. One document
 * per thread, {@code collab/threads/<thread id>.json}, holds the thread, its comments, the sealed revision chain, the mentions, the
 * followers and the legacy-note links: a reply, an edit or a follow is one compare-and-set of that document, so the chain can never
 * fork between two nodes. Lookups go through small index documents written in the same transaction ({@link TxContentStore}):
 * {@code collab/entities/<kind>/<sha1 of entity>/<thread>}, {@code collab/comments/<comment>} (its thread),
 * {@code collab/mentions/<sha1 of target>/<comment>} and {@code collab/note-links/<note, 19 digits>}. The thread document is the truth:
 * readers check an index entry against it, so an index left without its thread changes nothing.
 */
public final class ContentThreadStore implements ThreadStore {

    static final String THREADS = "collab/threads/";

    /** The stored thread document. */
    public static final class Doc {
        public CommentThread thread;
        public List<Comment> comments = new ArrayList<>();
        public List<Revision> chain = new ArrayList<>();
        public List<Mention> mentions = new ArrayList<>();
        public List<Follow> follows = new ArrayList<>();
        public Map<String, Long> notes = new LinkedHashMap<>();
    }

    private final TxContentStore store;
    private final ContentDocs docs;

    public ContentThreadStore(TxContentStore store) {
        this.store = store;
        this.docs = new ContentDocs(store);
    }

    static String threadKey(String id) {
        return THREADS + id + ".json";
    }

    private static String entityPrefix(String kind, String entityId) {
        return "collab/entities/" + ContentDocs.name(kind) + "/" + ContentDocs.hash(entityId) + "/";
    }

    private static String commentKey(String id) {
        return "collab/comments/" + id;
    }

    private static String mentionPrefix(String target) {
        return "collab/mentions/" + ContentDocs.hash(target) + "/";
    }

    private static String noteKey(long noteId) {
        return "collab/note-links/" + ContentDocs.pad(noteId);
    }

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private Optional<Doc> doc(String threadId) {
        if (threadId == null || threadId.isBlank() || threadId.contains("/")) {
            return Optional.empty();
        }
        return docs.value(threadKey(threadId), Doc.class);
    }

    private void index(String key, String value) {
        if (store.get(key).isEmpty()) {
            store.put(key, text(value), null);
        }
    }

    // ---- writes ------------------------------------------------------------------------------------------------------

    @Override
    public void saveThread(CommentThread t) {
        store.runRetrying(() -> {
            index(entityPrefix(t.kind(), t.entityId()) + t.id(), t.id());
            docs.update(threadKey(t.id()), Doc.class, d -> {
                Doc out = d == null ? new Doc() : d;
                out.thread = t;
                return out;
            });
            return null;
        });
    }

    @Override
    public Revision append(Comment c, Revision draft, Collection<String> targets) {
        return store.runRetrying(() -> {
            Revision[] sealed = new Revision[1];
            docs.update(threadKey(c.threadId()), Doc.class, d -> {
                if (d == null) {
                    throw new IllegalStateException("no thread " + c.threadId());
                }
                Revision last = d.chain.isEmpty() ? null : d.chain.get(d.chain.size() - 1);
                sealed[0] = HashChain.seal(last == null ? HashChain.genesis(c.threadId()) : last.hash(),
                        new Revision(draft.commentId(), draft.revision(), HashChain.after(draft.at(), last == null ? null : last.at()), draft.actor(),
                                draft.action(), draft.body(), draft.reason(), "", ""));
                d.chain.add(sealed[0]);
                boolean replaced = false;
                for (int i = 0; i < d.comments.size(); i++) {
                    if (d.comments.get(i).id().equals(c.id())) {
                        d.comments.set(i, c);
                        replaced = true;
                    }
                }
                if (!replaced) {
                    d.comments.add(c);
                }
                for (String target : new java.util.LinkedHashSet<>(targets)) {
                    if (d.mentions.stream().noneMatch(m -> m.commentId().equals(c.id()) && m.target().equals(target))) {
                        d.mentions.add(new Mention(c.id(), target, sealed[0].at()));
                    }
                }
                return d;
            });
            index(commentKey(c.id()), c.threadId());
            for (String target : targets) {
                index(mentionPrefix(target) + c.id(), c.threadId());
            }
            return sealed[0];
        });
    }

    @Override
    public void deleteThread(String id) {
        store.runRetrying(() -> {
            Doc d = doc(id).orElse(null);
            if (d == null) {
                return null;
            }
            for (Comment c : d.comments) {
                store.deleteLatest(commentKey(c.id()));
            }
            for (Mention m : d.mentions) {
                store.deleteLatest(mentionPrefix(m.target()) + m.commentId());
            }
            for (Long note : d.notes.values()) {
                store.deleteLatest(noteKey(note));
            }
            store.deleteLatest(entityPrefix(d.thread.kind(), d.thread.entityId()) + id);
            store.deleteLatest(threadKey(id));
            return null;
        });
    }

    @Override
    public void follow(Follow f) {
        docs.update(threadKey(f.threadId()), Doc.class, d -> {
            if (d == null) {
                throw new IllegalStateException("no thread " + f.threadId());
            }
            d.follows.removeIf(x -> x.username().equals(f.username()));
            d.follows.add(f);
            return d;
        });
    }

    @Override
    public boolean unfollow(String threadId, String username) {
        boolean[] had = new boolean[1];
        if (doc(threadId).map(d -> d.follows.stream().noneMatch(x -> x.username().equals(username))).orElse(true)) {
            return false;
        }
        docs.update(threadKey(threadId), Doc.class, d -> {
            if (d == null) {
                return null;
            }
            had[0] = d.follows.removeIf(x -> x.username().equals(username));
            return d;
        });
        return had[0];
    }

    /** Links the legacy note; {@code 409 DRS-5057} when another node already gave that number to a comment. */
    @Override
    public void linkNote(long noteId, String commentId) {
        store.run(() -> {
            String threadId = store.get(commentKey(commentId)).map(ContentStore.Item::text)
                    .orElseThrow(() -> new IllegalStateException("no comment " + commentId));
            store.put(noteKey(noteId), text(commentId), null);
            docs.update(threadKey(threadId), Doc.class, d -> {
                if (d == null) {
                    throw new IllegalStateException("no thread " + threadId);
                }
                d.notes.put(commentId, noteId);
                return d;
            });
            return null;
        });
    }

    /** The import: the thread document exactly as the old store had it (revisions keep their hashes) and its indexes. */
    void importThread(Doc d) {
        store.run(() -> {
            String id = d.thread.id();
            store.put(threadKey(id), ContentDocs.encode(d), null);
            index(entityPrefix(d.thread.kind(), d.thread.entityId()) + id, id);
            for (Comment c : d.comments) {
                index(commentKey(c.id()), id);
            }
            for (Mention m : d.mentions) {
                index(mentionPrefix(m.target()) + m.commentId(), id);
            }
            d.notes.forEach((comment, note) -> index(noteKey(note), comment));
            return null;
        });
    }

    // ---- reads -------------------------------------------------------------------------------------------------------

    @Override
    public Optional<CommentThread> thread(String id) {
        return doc(id).map(d -> d.thread);
    }

    @Override
    public List<CommentThread> threads(String kind, String entityId) {
        String p = entityPrefix(kind, entityId);
        List<CommentThread> out = new ArrayList<>();
        for (String k : store.list(p)) {
            doc(k.substring(p.length())).map(d -> d.thread).filter(t -> t.kind().equals(kind) && t.entityId().equals(entityId)).ifPresent(out::add);
        }
        out.sort(Comparator.comparing(CommentThread::lastAt).reversed().thenComparing(CommentThread::id, Comparator.reverseOrder()));
        return out;
    }

    @Override
    public List<CommentThread> page(String afterId, int limit) {
        List<CommentThread> out = new ArrayList<>();
        for (String k : store.list(THREADS)) {
            String id = k.substring(THREADS.length(), k.length() - ".json".length());
            if (afterId != null && id.compareTo(afterId) <= 0) {
                continue;
            }
            doc(id).ifPresent(d -> out.add(d.thread));
            if (out.size() >= Math.max(1, limit)) {
                break;
            }
        }
        return out;
    }

    private Optional<Doc> docOfComment(String commentId) {
        if (commentId == null || commentId.isBlank() || commentId.contains("/")) {
            return Optional.empty();
        }
        return store.get(commentKey(commentId)).flatMap(it -> doc(it.text()));
    }

    @Override
    public Optional<Comment> comment(String id) {
        return docOfComment(id).flatMap(d -> d.comments.stream().filter(c -> c.id().equals(id)).findFirst());
    }

    @Override
    public List<Comment> comments(String threadId) {
        return doc(threadId).map(d -> d.comments.stream().sorted(Comparator.comparing(Comment::createdAt).thenComparing(Comment::id)).toList())
                .orElse(List.of());
    }

    @Override
    public List<Revision> revisions(String commentId) {
        return docOfComment(commentId).map(d -> d.chain.stream().filter(r -> r.commentId().equals(commentId)).toList()).orElse(List.of());
    }

    @Override
    public List<Revision> chain(String threadId) {
        return doc(threadId).map(d -> List.copyOf(d.chain)).orElse(List.of());
    }

    @Override
    public Optional<Follow> following(String threadId, String username) {
        return doc(threadId).flatMap(d -> d.follows.stream().filter(f -> f.username().equals(username)).findFirst());
    }

    @Override
    public List<Follow> followers(String threadId) {
        return doc(threadId).map(d -> List.copyOf(d.follows)).orElse(List.of());
    }

    @Override
    public List<Mention> mentionsOf(Collection<String> targets, int limit, String before) {
        TreeSet<String[]> candidates = new TreeSet<>(Comparator.<String[], String>comparing(a -> a[0]).reversed().thenComparing(a -> a[1]));
        for (String target : new java.util.LinkedHashSet<>(targets)) {
            String p = mentionPrefix(target);
            for (String k : store.list(p)) {
                String comment = k.substring(p.length());
                if (before == null || before.isBlank() || comment.compareTo(before) < 0) {
                    candidates.add(new String[] {comment, target});
                }
            }
        }
        List<Mention> out = new ArrayList<>();
        Map<String, Optional<Doc>> seen = new LinkedHashMap<>();
        for (String[] c : candidates) {
            Optional<Doc> d = seen.computeIfAbsent(c[0], this::docOfComment);
            d.flatMap(x -> x.mentions.stream().filter(m -> m.commentId().equals(c[0]) && m.target().equals(c[1])).findFirst()).ifPresent(out::add);
            if (out.size() >= Math.max(1, limit)) {
                break;
            }
        }
        return out;
    }

    @Override
    public List<Mention> mentions(String commentId) {
        return docOfComment(commentId).map(d -> d.mentions.stream().filter(m -> m.commentId().equals(commentId)).toList()).orElse(List.of());
    }

    @Override
    public Optional<String> commentOfNote(long noteId) {
        return store.get(noteKey(noteId)).map(ContentStore.Item::text).filter(c -> noteOfComment(c).filter(n -> n == noteId).isPresent());
    }

    @Override
    public Optional<Long> noteOfComment(String commentId) {
        return docOfComment(commentId).map(d -> d.notes.get(commentId));
    }

    @Override
    public Set<Long> linkedNotes() {
        Set<Long> out = new TreeSet<>();
        String p = "collab/note-links/";
        for (String k : store.list(p)) {
            out.add(Long.parseLong(k.substring(p.length())));
        }
        return out;
    }
}
