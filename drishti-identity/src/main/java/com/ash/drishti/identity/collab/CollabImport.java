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
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one-time import of collaboration from the old stores (the identity database's {@code drishti_*} collaboration tables, or the
 * files under {@code <data>/collab}) into the content store (CONTENT_STORE.md section 9). Every item keeps its id, number and hashes
 * (a thread's revision chain still verifies); an item already in the store is skipped, so a rerun or two nodes importing at once are
 * harmless; the sequences are raised past the imported numbers. A marker {@code system/imports/collab-<source>.json} records the
 * import, so something deleted later does not come back. The old rows and files are left in place.
 */
public final class CollabImport {

    private static final Logger LOG = LoggerFactory.getLogger(CollabImport.class);

    /** The old stores of one source. */
    public record Source(String name, ThreadStore threads, ShareStore shares, InboxStore inbox, OutboxStore outbox, HoldStore holds, Seal.Store seals) {}

    /** The content stores to import into. */
    public record Target(TxContentStore store, ContentThreadStore threads, ContentShareStore shares, ContentInboxStore inbox,
            ContentOutboxStore outbox, ContentHoldStore holds, ContentSealStore seals) {

        public static Target of(TxContentStore store) {
            return new Target(store, new ContentThreadStore(store), new ContentShareStore(store), new ContentInboxStore(store, Integer.MAX_VALUE),
                    new ContentOutboxStore(store), new ContentHoldStore(store), new ContentSealStore(store));
        }
    }

    /** The content stores when collaboration is kept in the content store, else none (a Spring bean either way). */
    public record Content(Target target) {

        public boolean isPresent() {
            return target != null;
        }

        public Target get() {
            return target;
        }
    }

    private CollabImport() {}

    static String marker(String source) {
        return "system/imports/collab-" + source + ".json";
    }

    /** Imports {@code from} once; returns how many items were brought in (0 when the marker says it was done). */
    public static int run(Source from, Target to) {
        String marker = marker(from.name());
        if (to.store().get(marker).isPresent()) {
            return 0;
        }
        int n = 0;
        int failed = 0;
        for (String after = null; ; ) {
            List<CommentThread> page = from.threads().page(after, 200);
            if (page.isEmpty()) {
                break;
            }
            for (CommentThread t : page) {
                after = t.id();
                if (to.threads().thread(t.id()).isPresent()) {
                    continue;
                }
                try {
                    ContentThreadStore.Doc d = new ContentThreadStore.Doc();
                    d.thread = t;
                    d.comments.addAll(from.threads().comments(t.id()));
                    d.chain.addAll(from.threads().chain(t.id()));
                    d.follows.addAll(from.threads().followers(t.id()));
                    for (Comment c : d.comments) {
                        d.mentions.addAll(from.threads().mentions(c.id()));
                        from.threads().noteOfComment(c.id()).ifPresent(note -> d.notes.put(c.id(), note));
                    }
                    to.threads().importThread(d);
                    n++;
                } catch (RuntimeException e) {
                    failed++;
                    LOG.warn("collaboration: could not import thread {} from {}: {}", t.id(), from.name(), e.toString());
                }
            }
        }
        for (String after = null; ; ) {
            List<Share> page = from.shares().page(after, 200);
            if (page.isEmpty()) {
                break;
            }
            for (Share s : page) {
                after = s.id();
                if (to.shares().find(s.id()).isEmpty()) {
                    to.shares().save(s, from.shares().recipients(s.id()));
                    n++;
                }
            }
        }
        long max = 0;
        for (long after = 0; ; ) {
            List<Notice> page = from.inbox().after(after, 500);
            if (page.isEmpty()) {
                break;
            }
            for (Notice x : page) {
                after = x.seq();
                max = Math.max(max, x.seq());
                if (to.store().get(ContentInboxStore.SEQ + ContentDocs.pad(x.seq())).isEmpty()) {
                    to.inbox().put(x, Integer.MAX_VALUE);
                    n++;
                }
            }
        }
        ContentDocs.raise(to.store(), ContentInboxStore.COUNTER, Math.max(max, from.inbox().maxSeq()));
        max = 0;
        for (long after = 0; ; ) {
            List<OutboxItem> page = from.outbox().after(after, 500);
            if (page.isEmpty()) {
                break;
            }
            for (OutboxItem x : page) {
                after = x.seq();
                max = Math.max(max, x.seq());
                to.outbox().put(x);
                n++;
            }
        }
        ContentDocs.raise(to.store(), ContentOutboxStore.COUNTER, max);
        max = 0;
        for (Hold h : from.holds().list(false)) {
            max = Math.max(max, h.id());
            to.holds().put(h);
            n++;
        }
        ContentDocs.raise(to.store(), ContentHoldStore.COUNTER, max);
        for (Map.Entry<String, Seal> e : from.seals().all().entrySet()) {
            if (to.seals().get(e.getKey()).isEmpty()) {
                to.seals().put(e.getKey(), e.getValue());
            }
        }
        if (failed == 0) {
            String text = "{\"imported\":" + n + ",\"at\":" + System.currentTimeMillis() + "}";
            new ContentDocs(to.store()).create(marker, ContentDocs.JSON.valueToTree(java.util.Map.of("note", text)));
        }
        if (n + failed > 0) {
            LOG.info("collaboration: imported {} items from {} into the content store ({} failed)", n, from.name(), failed);
        }
        return n;
    }
}
