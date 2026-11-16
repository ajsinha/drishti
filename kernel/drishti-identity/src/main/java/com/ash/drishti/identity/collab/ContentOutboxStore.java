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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.content.ContentDocs;
import com.ash.drishti.identity.content.TxContentStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The outbox in the content store (DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 9): one document per delivery, {@code collab/outbox/<seq, 19 digits>.json},
 * numbered from the cluster-wide sequence {@code collab/counters/outbox}. A claim is a compare-and-set from due to {@code sending} with
 * the claimer's lease: when two nodes' dispatchers reach the same delivery, one write wins and the other skips it, so a delivery is
 * sent once. An expired lease (a node that died while sending) makes it due again, as before.
 */
public final class ContentOutboxStore implements OutboxStore {

    static final String ITEMS = "collab/outbox/";
    static final String COUNTER = "collab/counters/outbox";

    private final TxContentStore store;
    private final ContentDocs docs;

    public ContentOutboxStore(TxContentStore store) {
        this.store = store;
        this.docs = new ContentDocs(store);
    }

    private static String key(long seq) {
        return ITEMS + ContentDocs.pad(seq) + ".json";
    }

    private static long seqOf(String key) {
        return Long.parseLong(key.substring(ITEMS.length(), key.length() - ".json".length()));
    }

    @Override
    public OutboxItem add(OutboxItem item) {
        OutboxItem n = item.withSeq(ContentDocs.next(store, COUNTER));
        store.put(key(n.seq()), ContentDocs.encode(n), null);
        return n;
    }

    /** Stores a delivery with its number as given (the import). */
    void put(OutboxItem item) {
        docs.create(key(item.seq()), item);
    }

    @Override
    public Optional<OutboxItem> find(long seq) {
        return docs.value(key(seq), OutboxItem.class);
    }

    private List<OutboxItem> all() {
        List<OutboxItem> out = new ArrayList<>();
        for (String k : store.list(ITEMS)) {
            docs.value(k, OutboxItem.class).ifPresent(out::add);
        }
        return out;
    }

    @Override
    public List<OutboxItem> claim(Instant now, int limit, Instant leaseUntil, String owner) {
        List<OutboxItem> won = new ArrayList<>();
        for (String k : store.list(ITEMS)) {
            if (won.size() >= Math.max(1, limit)) {
                break;
            }
            Optional<ContentDocs.Doc<OutboxItem>> d = docs.read(k, OutboxItem.class);
            if (d.isEmpty()) {
                continue;
            }
            OutboxItem i = d.get().value();
            boolean due = OutboxItem.PENDING.equals(i.state()) && !i.nextAt().isAfter(now)
                    || OutboxItem.SENDING.equals(i.state()) && i.leaseUntil() != null && i.leaseUntil().isBefore(now);
            if (!due) {
                continue;
            }
            OutboxItem c = i.with(OutboxItem.SENDING, i.attempts(), i.nextAt(), leaseUntil, owner, i.lastError(), null);
            try {
                store.base().put(k, ContentDocs.encode(c), d.get().version());
                won.add(c);
            } catch (DrishtiException e) {
                if (e.errorCode() != ErrorCode.CONTENT_CONFLICT) {
                    throw e;                                                    // a conflict: another node claimed it first
                }
            }
        }
        return won;
    }

    private void change(long seq, UnaryOperator<OutboxItem> edit) {
        if (find(seq).isPresent()) {
            docs.update(key(seq), OutboxItem.class, i -> i == null ? null : edit.apply(i));
        }
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
        boolean[] done = new boolean[1];
        if (find(s).filter(i -> OutboxItem.DEAD.equals(i.state()) || OutboxItem.CANCELLED.equals(i.state())).isEmpty()) {
            return false;
        }
        docs.update(key(s), OutboxItem.class, i -> {
            done[0] = i != null && (OutboxItem.DEAD.equals(i.state()) || OutboxItem.CANCELLED.equals(i.state()));
            return i == null ? null : done[0] ? i.with(OutboxItem.PENDING, 0, at, null, null, i.lastError(), null) : i;
        });
        return done[0];
    }

    @Override
    public List<OutboxItem> list(String state, int limit) {
        List<String> keys = store.list(ITEMS);
        List<OutboxItem> out = new ArrayList<>();
        for (int i = keys.size() - 1; i >= 0 && out.size() < Math.max(1, limit); i--) {
            docs.value(keys.get(i), OutboxItem.class).filter(x -> state == null || state.isBlank() || state.equals(x.state())).ifPresent(out::add);
        }
        return out;
    }

    @Override
    public List<OutboxItem> after(long afterSeq, int limit) {
        List<OutboxItem> out = new ArrayList<>();
        for (String k : store.list(ITEMS)) {
            if (seqOf(k) > afterSeq) {
                docs.value(k, OutboxItem.class).ifPresent(out::add);
                if (out.size() >= Math.max(1, limit)) {
                    break;
                }
            }
        }
        return out;
    }

    @Override
    public Map<String, Long> counts() {
        List<OutboxItem> all = all();
        Map<String, Long> out = new LinkedHashMap<>();
        for (String s : List.of(OutboxItem.PENDING, OutboxItem.SENDING, OutboxItem.SENT, OutboxItem.DEAD, OutboxItem.CANCELLED)) {
            out.put(s, all.stream().filter(i -> s.equals(i.state())).count());
        }
        return out;
    }

    @Override
    public long countSince(String recipient, Instant since) {
        return all().stream().filter(i -> i.recipient().equals(recipient) && !i.createdAt().isBefore(since)).count();
    }

    @Override
    public int purgeSent(Instant before) {
        int n = 0;
        for (String k : store.list(ITEMS)) {
            Optional<ContentDocs.Doc<OutboxItem>> d = docs.read(k, OutboxItem.class);
            if (d.isPresent() && OutboxItem.SENT.equals(d.get().value().state()) && d.get().value().sentAt() != null
                    && d.get().value().sentAt().isBefore(before)) {
                try {
                    if (store.delete(k, d.get().version())) {
                        n++;
                    }
                } catch (DrishtiException e) {
                    if (e.errorCode() != ErrorCode.CONTENT_CONFLICT) {
                        throw e;
                    }
                }
            }
        }
        return n;
    }
}
