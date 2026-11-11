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
 * Legal holds in the content store (DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 9): {@code collab/holds/<id, 19 digits>.json}, numbered from the
 * cluster-wide sequence {@code collab/counters/holds}; a release is a compare-and-set, so it happens once.
 */
public final class ContentHoldStore implements HoldStore {

    static final String HOLDS = "collab/holds/";
    static final String COUNTER = "collab/counters/holds";

    private final TxContentStore store;
    private final ContentDocs docs;

    public ContentHoldStore(TxContentStore store) {
        this.store = store;
        this.docs = new ContentDocs(store);
    }

    private static String key(long id) {
        return HOLDS + ContentDocs.pad(id) + ".json";
    }

    @Override
    public Hold place(Hold hold) {
        Hold h = hold.withId(ContentDocs.next(store, COUNTER));
        store.put(key(h.id()), ContentDocs.encode(h), null);
        return h;
    }

    /** The import: the hold with its number. */
    void put(Hold h) {
        docs.create(key(h.id()), h);
    }

    @Override
    public Optional<Hold> find(long id) {
        return docs.value(key(id), Hold.class);
    }

    @Override
    public List<Hold> list(boolean activeOnly) {
        List<String> keys = store.list(HOLDS);
        List<Hold> out = new ArrayList<>();
        for (int i = keys.size() - 1; i >= 0; i--) {
            docs.value(keys.get(i), Hold.class).filter(h -> !activeOnly || h.active()).ifPresent(out::add);
        }
        return out;
    }

    @Override
    public boolean release(long id, String by, Instant at) {
        if (find(id).filter(Hold::active).isEmpty()) {
            return false;
        }
        boolean[] done = new boolean[1];
        docs.update(key(id), Hold.class, h -> {
            done[0] = h != null && h.active();
            return h == null ? null : done[0] ? h.released(by, at) : h;
        });
        return done[0];
    }
}
