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
import com.ash.drishti.identity.content.TxContentStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shares in the content store (DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 9): one document per share with its recipients,
 * {@code collab/shares/<share id>.json}, and index entries {@code collab/shares-sent/<sha1 of sender>/<id>} and
 * {@code collab/shares-to/<sha1 of recipient>/<id>} written in the same transaction. The share document is the truth; an index entry
 * without it is skipped.
 */
public final class ContentShareStore implements ShareStore {

    static final String SHARES = "collab/shares/";

    /** The stored document. */
    public static final class Doc {
        public Share share;
        public List<Recipient> recipients = new ArrayList<>();
    }

    private final TxContentStore store;
    private final ContentDocs docs;

    public ContentShareStore(TxContentStore store) {
        this.store = store;
        this.docs = new ContentDocs(store);
    }

    private static String key(String id) {
        return SHARES + id + ".json";
    }

    private static String sentPrefix(String sender) {
        return "collab/shares-sent/" + ContentDocs.hash(sender) + "/";
    }

    private static String toPrefix(String user) {
        return "collab/shares-to/" + ContentDocs.hash(user) + "/";
    }

    private Optional<Doc> doc(String id) {
        return id == null || id.isBlank() || id.contains("/") ? Optional.empty() : docs.value(key(id), Doc.class);
    }

    private void index(String k) {
        if (store.get(k).isEmpty()) {
            store.put(k, new byte[0], null);
        }
    }

    @Override
    public void save(Share share, List<Recipient> recipients) {
        store.runRetrying(() -> {
            index(sentPrefix(share.sender()) + share.id());
            for (Recipient r : recipients) {
                if (r.username() != null) {
                    index(toPrefix(r.username()) + share.id());
                }
            }
            docs.update(key(share.id()), Doc.class, d -> {
                Doc out = new Doc();
                out.share = share;
                out.recipients = new ArrayList<>(recipients);
                return out;
            });
            return null;
        });
    }

    @Override
    public Optional<Share> find(String id) {
        return doc(id).map(d -> d.share);
    }

    @Override
    public List<Recipient> recipients(String shareId) {
        return doc(shareId).map(d -> List.copyOf(d.recipients)).orElse(List.of());
    }

    @Override
    public boolean markOpened(String shareId, String username, Instant at) {
        if (doc(shareId).map(d -> d.recipients.stream().noneMatch(r -> r.username().equals(username) && r.openedAt() == null)).orElse(true)) {
            return false;
        }
        boolean[] set = new boolean[1];
        docs.update(key(shareId), Doc.class, d -> {
            set[0] = false;
            if (d == null) {
                return null;
            }
            List<Recipient> out = new ArrayList<>();
            for (Recipient r : d.recipients) {
                if (r.username().equals(username) && r.openedAt() == null) {
                    out.add(r.withOpened(at));
                    set[0] = true;
                } else {
                    out.add(r);
                }
            }
            d.recipients = out;
            return d;
        });
        return set[0];
    }

    private List<Share> newest(String prefix, int limit, String beforeId, java.util.function.Predicate<Doc> keep) {
        List<String> keys = store.list(prefix);
        List<Share> out = new ArrayList<>();
        for (int i = keys.size() - 1; i >= 0 && out.size() < Math.max(1, limit); i--) {
            String id = keys.get(i).substring(prefix.length());
            if (beforeId == null || beforeId.isBlank() || id.compareTo(beforeId) < 0) {
                doc(id).filter(keep).ifPresent(d -> out.add(d.share));
            }
        }
        return out;
    }

    @Override
    public List<Share> sent(String sender, int limit, String beforeId) {
        return newest(sentPrefix(sender), limit, beforeId, d -> d.share.sender().equals(sender));
    }

    @Override
    public List<Share> received(String username, int limit, String beforeId) {
        return newest(toPrefix(username), limit, beforeId,
                d -> d.recipients.stream().anyMatch(r -> username.equals(r.username()) && Recipient.NOTIFIED.equals(r.state())));
    }

    @Override
    public List<Share> page(String afterId, int limit) {
        List<Share> out = new ArrayList<>();
        for (String k : store.list(SHARES)) {
            String id = k.substring(SHARES.length(), k.length() - ".json".length());
            if (afterId == null || id.compareTo(afterId) > 0) {
                doc(id).ifPresent(d -> out.add(d.share));
            }
            if (out.size() >= Math.max(1, limit)) {
                break;
            }
        }
        return out;
    }

    @Override
    public void delete(String id) {
        store.runRetrying(() -> {
            doc(id).ifPresent(d -> {
                store.deleteLatest(sentPrefix(d.share.sender()) + id);
                for (Recipient r : d.recipients) {
                    if (r.username() != null) {
                        store.deleteLatest(toPrefix(r.username()) + id);
                    }
                }
                store.deleteLatest(key(id));
            });
            return null;
        });
    }

    @Override
    public long countSentSince(String sender, Instant since) {
        String p = sentPrefix(sender);
        long n = 0;
        for (String k : store.list(p)) {
            if (doc(k.substring(p.length())).filter(d -> d.share.sender().equals(sender) && !d.share.createdAt().isBefore(since)).isPresent()) {
                n++;
            }
        }
        return n;
    }
}
