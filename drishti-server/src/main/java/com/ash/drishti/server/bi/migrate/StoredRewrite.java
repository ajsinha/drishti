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
package com.ash.drishti.server.bi.migrate;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.bi.BiStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Writes a migration's new text into one stored item without losing anything (docs/architecture/BI_MODEL_SPEC.md section 8.3, step 5): the text before is a
 * saved state of the item's history first (so <i>Restore</i> brings it back), the published text that changed becomes the next published version (the old
 * version stays in the version list), a draft stays a draft, a proposal stays proposed, and the change is a governance event on the item and an audit
 * record. Old published versions are history and are not rewritten. Callers hold no lock; the store serialises its writes.
 */
final class StoredRewrite {

    /** What a rewrite did to one item: whether the draft and the published text changed, the new published version, and the saved state of the text before. */
    record Done(boolean draft, boolean published, int version, int before) {}

    private final BiStore store;
    private final AuditLog audit;
    private final int versionsKeep;
    private final int revisionsKeep;

    StoredRewrite(BiStore store, AuditLog audit, int versionsKeep, int revisionsKeep) {
        this.store = store;
        this.audit = audit;
        this.versionsKeep = versionsKeep;
        this.revisionsKeep = revisionsKeep;
    }

    /** The published text of an item, or empty when it was never published. */
    Optional<String> published(BiStore.Item i) {
        return i.isPublished() ? store.version(i.id(), i.publishedVersion()).map(BiStore.Version::body) : Optional.empty();
    }

    /**
     * Applies {@code convert} to the draft and to the published text of {@code item}; a text {@code convert} returns unchanged (or null) is left alone.
     *
     * @param origin the saved states' origin ({@code migrate})
     * @param action the audit action ({@code bi-expressions-migrated})
     * @param note what changed, for the history, the event and the audit record
     */
    Done apply(BiStore.Item item, UnaryOperator<String> convert, String actor, String origin, String action, String note) {
        BiStore.Item i = store.item(item.id()).orElse(item);
        String draft = i.body() == null ? "" : i.body();
        Optional<String> pub = published(i);
        String draftNew = draft.isBlank() ? draft : convert.apply(draft);
        String pubNew = pub.map(convert).orElse(null);
        boolean draftChanged = draftNew != null && !draftNew.equals(draft);
        boolean pubChanged = pub.isPresent() && pubNew != null && !pubNew.equals(pub.get());
        if (!draftChanged && !pubChanged) {
            return new Done(false, false, i.publishedVersion(), 0);
        }
        Instant at = Instant.now();
        int before = keepBefore(i, draft, actor, at);
        int version = i.publishedVersion();
        if (pubChanged) {
            if (!pub.get().equals(draft)) {                                       // the published text is restorable too, not only as an old version
                store.addRevision(i.id(), pub.get(), "the published text of version " + version + " before the migration (" + note + ")", actor, at, origin, 0, revisionsKeep);
            }
            version = version + 1;
            store.addVersion(new BiStore.Version(i.id(), version, pubNew, note + " (from version " + i.publishedVersion() + ")", actor, at), versionsKeep);
        }
        String body = draftChanged ? draftNew : draft;
        if (pubChanged && draft.equals(pub.get())) {
            body = pubNew;                                                         // the draft was the published text: it moves with it
        }
        String status = pubNew != null && body.equals(pubNew) && !"proposed".equals(i.status()) ? "published" : i.status();
        store.saveItem(new BiStore.Item(i.id(), i.workspace(), i.kind(), i.title(), i.description(), i.owner(), i.tags(), body, status, version, i.endorsement(),
                i.endorsedBy(), i.audienceRoles(), i.audienceUsers(), i.audiencePacks(), i.proposedBy(), i.proposedAt(), i.createdAt(), at, actor,
                pubChanged ? at : i.publishedAt(), pubChanged ? actor : i.publishedBy()));
        if (pubChanged && !body.equals(pubNew)) {                                  // the published text first: the newest saved state is the draft
            store.addRevision(i.id(), pubNew, note + ": the published text, version " + version, actor, at, origin, version, revisionsKeep);
        }
        BiStore.Revision r = store.addRevision(i.id(), body, note, actor, at, origin, pubChanged && body.equals(pubNew) ? version : 0, revisionsKeep);
        String detail = note + "; the text before is saved state " + before + (pubChanged ? "; published as version " + version : "; draft only");
        store.addEvent(i.id(), origin, actor, detail, r.seq(), at);
        audit.record(actor, action, i.id(), detail);
        return new Done(draftChanged, pubChanged, version, before);
    }

    /** Makes sure the draft text before the change is a saved state (the latest one, when it already is), and returns its number. */
    private int keepBefore(BiStore.Item i, String draft, String actor, Instant at) {
        List<BiStore.Revision> revs = store.revisions(i.id());
        if (!revs.isEmpty() && revs.get(0).body().equals(draft)) {
            return revs.get(0).seq();
        }
        return store.addRevision(i.id(), draft, "the text before the migration", actor, at, "migrate", 0, revisionsKeep).seq();
    }
}
