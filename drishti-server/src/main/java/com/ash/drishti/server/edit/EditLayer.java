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
package com.ash.drishti.server.edit;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.engine.source.DocumentLayer;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Overlays and owned values laid over documents at read time (WRITE_BACK.md section 5.2), installed on the router while write-back is
 * on: a view of date D shows the overlays valid on D (an overlay of a later business date is not shown on an earlier page), then the
 * owned values. A record nothing was ever written to is returned as it is, with no database read once the site has no overlay at all.
 * {@link #changed} is called after an apply commits: it drops the cached rows and tells every live view of the record, which reads it
 * again with its own viewer's masks (section 18.5).
 */
public final class EditLayer implements DocumentLayer {

    private static final Logger LOG = LoggerFactory.getLogger(EditLayer.class);

    private final EditRecords records;
    private final Map<EntityRef, List<Runnable>> watchers = new ConcurrentHashMap<>();
    private volatile boolean active;

    public EditLayer(EditRecords records, boolean active) {
        this.records = records;
        this.active = active;
    }

    public void active(boolean on) {
        active = on;
    }

    @Override
    public boolean active() {
        return active;
    }

    @Override
    public EntityDocument apply(EntityDocument doc, AsOf asOf) {
        if (doc == null || doc.deleted() || !records.any()) {
            return doc;
        }
        EntityRef ref = doc.ref();
        LocalDate date = doc.provenance() != null && doc.provenance().businessDate() != null ? doc.provenance().businessDate()
                : asOf != null && asOf.businessDate() != null ? asOf.businessDate() : LocalDate.now(ZoneOffset.UTC);
        Map<String, EditRecords.Overlay> over;
        List<EditRecords.Owned> owned;
        try {
            over = records.on(ref.kind(), ref.id(), date);
            owned = records.owned(ref.kind(), ref.id());
        } catch (RuntimeException e) {
            LOG.warn("write-back: the overlays of {} could not be read ({}); showing the source's values", ref, e.getMessage());
            return doc;
        }
        if (over.isEmpty() && owned.isEmpty()) {
            return doc;
        }
        DataNode data = doc.data();
        for (EditRecords.Overlay o : over.values()) {
            data = EditPaths.set(data, o.path(), o.value());
        }
        for (EditRecords.Owned o : owned) {
            data = EditPaths.set(data, o.path(), o.value());
        }
        return new EntityDocument(ref, data, doc.provenance(), doc.deleted());
    }

    /** The words "How this view was built" adds for a record with overlays on the date: which changes, by whom (null when none). */
    public String note(EntityRef ref, LocalDate date) {
        if (!records.any()) {
            return null;
        }
        Map<String, EditRecords.Overlay> over = records.on(ref.kind(), ref.id(), date == null ? LocalDate.now(ZoneOffset.UTC) : date);
        if (over.isEmpty()) {
            return null;
        }
        Set<String> changes = new java.util.LinkedHashSet<>();
        over.values().forEach(o -> changes.add(o.changeId() + " (" + o.path().replace("$.", "") + ", " + o.by() + ")"));
        return over.size() + " field" + (over.size() == 1 ? "" : "s") + " edited here (overlays): " + String.join("; ", changes);
    }

    @Override
    public Subscription watch(EntityRef ref, Runnable changed) {
        if (!active) {
            return Subscription.NONE;
        }
        watchers.computeIfAbsent(ref, r -> new CopyOnWriteArrayList<>()).add(changed);
        return () -> watchers.computeIfPresent(ref, (r, l) -> {
            l.remove(changed);
            return l.isEmpty() ? null : l;
        });
    }

    /** A change to the record was applied (here, or on the node that owns its lane): the views of it read it again. */
    public void changed(String kind, String entity) {
        records.forget(kind, entity);
        List<Runnable> l = watchers.get(new EntityRef(kind, entity));
        if (l != null) {
            l.forEach(r -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    LOG.debug("write-back: a view of {}/{} could not be told: {}", kind, entity, e.getMessage());
                }
            });
        }
    }

    /** How many records live views are watching (Admin → Edits). */
    public int watched() {
        return watchers.size();
    }
}
