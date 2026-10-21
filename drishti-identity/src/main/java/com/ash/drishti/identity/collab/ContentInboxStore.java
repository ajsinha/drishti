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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The inbox in the content store (CONTENT_STORE.md section 9): one document per user, {@code collab/inbox/<sha1 of user>.json}, holding
 * that user's newest rows (at most {@code keep}), so the bell's count and list are one read. Row numbers come from the cluster-wide
 * sequence {@code collab/counters/inbox} (never reused); {@code collab/inbox-seq/<seq, 19 digits>} names the user of each row so another
 * node's hub can poll {@link #after}. The user document is the truth; a sequence entry without its row is skipped.
 */
public final class ContentInboxStore implements InboxStore {

    static final String USERS = "collab/inbox/";
    static final String SEQ = "collab/inbox-seq/";
    static final String COUNTER = "collab/counters/inbox";

    /** The stored document: the user and their rows, oldest first. */
    public static final class Doc {
        public String username;
        public List<Notice> rows = new ArrayList<>();
    }

    private final TxContentStore store;
    private final ContentDocs docs;
    private final int keep;

    public ContentInboxStore(TxContentStore store, int keep) {
        this.store = store;
        this.docs = new ContentDocs(store);
        this.keep = Math.max(1, keep);
    }

    private static String key(String user) {
        return USERS + ContentDocs.hash(user) + ".json";
    }

    private List<Notice> rows(String user) {
        return docs.value(key(user), Doc.class).map(d -> d.rows).orElse(List.of());
    }

    @Override
    public Notice add(Notice notice) {
        Notice n = notice.withSeq(ContentDocs.next(store, COUNTER));
        put(n, keep);
        return n;
    }

    /** Stores a row with its number as given (the import), trimming to {@code max} rows. */
    void put(Notice n, int max) {
        store.runRetrying(() -> {
            store.put(SEQ + ContentDocs.pad(n.seq()), n.username().getBytes(StandardCharsets.UTF_8), null);
            docs.update(key(n.username()), Doc.class, d -> {
                Doc out = d == null ? new Doc() : d;
                out.username = n.username();
                out.rows.add(n);
                out.rows.sort(java.util.Comparator.comparingLong(Notice::seq));
                trim(out, max);
                return out;
            });
            return null;
        });
    }

    private void trim(Doc d, int max) {
        while (d.rows.size() > max) {
            store.deleteLatest(SEQ + ContentDocs.pad(d.rows.remove(0).seq()));
        }
    }

    @Override
    public List<Notice> list(String username, String type, boolean unreadOnly, int limit, long beforeSeq) {
        List<Notice> all = rows(username);
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
        return rows(username).stream().filter(n -> n.readAt() == null).count();
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
        if (rows(user).stream().noneMatch(n -> n.readAt() == null && (n.seq() <= upTo || seqs.contains(n.seq())))) {
            return 0;
        }
        int[] changed = new int[1];
        docs.update(key(user), Doc.class, d -> {
            changed[0] = 0;
            if (d == null) {
                return null;
            }
            for (int i = 0; i < d.rows.size(); i++) {
                Notice n = d.rows.get(i);
                if (n.readAt() == null && (n.seq() <= upTo || seqs.contains(n.seq()))) {
                    d.rows.set(i, n.withRead(at));
                    changed[0]++;
                }
            }
            return d;
        });
        return changed[0];
    }

    @Override
    public List<Notice> after(long afterSeq, int limit) {
        String from = SEQ + ContentDocs.pad(afterSeq);
        Map<String, List<Notice>> cache = new LinkedHashMap<>();
        List<Notice> out = new ArrayList<>();
        for (String k : store.list(SEQ)) {
            if (k.compareTo(from) <= 0) {
                continue;
            }
            long seq = Long.parseLong(k.substring(SEQ.length()));
            Optional<String> user = store.get(k).map(ContentStore.Item::text);
            user.flatMap(u -> cache.computeIfAbsent(u, this::rows).stream().filter(n -> n.seq() == seq).findFirst()).ifPresent(out::add);
            if (out.size() >= Math.max(1, limit)) {
                break;
            }
        }
        return out;
    }

    @Override
    public long maxSeq() {
        return ContentDocs.current(store, COUNTER);
    }

    @Override
    public void prune(String username, int keepRows) {
        if (rows(username).size() <= Math.max(1, keepRows)) {
            return;
        }
        store.runRetrying(() -> docs.update(key(username), Doc.class, d -> {
            if (d != null) {
                trim(d, Math.max(1, keepRows));
            }
            return d;
        }));
    }

    @Override
    public int purgeBefore(Instant before) {
        int removed = 0;
        for (String k : store.list(USERS)) {
            if (docs.value(k, Doc.class).map(d -> d.rows.stream().noneMatch(x -> x.at().isBefore(before))).orElse(true)) {
                continue;
            }
            int[] n = new int[1];
            store.runRetrying(() -> docs.update(k, Doc.class, d -> {
                n[0] = 0;
                if (d == null) {
                    return null;
                }
                for (Notice x : List.copyOf(d.rows)) {
                    if (x.at().isBefore(before)) {
                        d.rows.remove(x);
                        store.deleteLatest(SEQ + ContentDocs.pad(x.seq()));
                        n[0]++;
                    }
                }
                return d.rows.isEmpty() ? null : d;
            }));
            removed += n[0];
        }
        return removed;
    }

    @Override
    public void forget(String username) {
        store.runRetrying(() -> {
            for (Notice n : rows(username)) {
                store.deleteLatest(SEQ + ContentDocs.pad(n.seq()));
            }
            store.deleteLatest(key(username));
            return null;
        });
    }
}
