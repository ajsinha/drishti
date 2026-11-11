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
package com.ash.drishti.identity.bi;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.content.ContentStore;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one-time import of BI content from the identity database ({@code drishti_bi_*}) into the content store (docs/architecture/DATA_ARCHITECTURE.md
 * section 9). Every document is created with "must be absent", so what is already in the store is never overwritten and a rerun is harmless; a
 * marker ({@code system/imports/bi-jpa.json}) keeps later starts from importing again, so an item deleted later does not come back. The rows are
 * left in place.
 */
public final class BiImport {

    private static final Logger LOG = LoggerFactory.getLogger(BiImport.class);
    static final String MARKER = "system/imports/bi-jpa.json";

    /** What the import did: documents created, skipped (already there) and failed. */
    public record Result(int imported, int skipped, int failed) {}

    private BiImport() {}

    public static Result fromDatabase(JpaBiStore jpa, ContentBiStore to) {
        ContentStore c = to.content();
        if (c.get(MARKER).isPresent()) {
            return new Result(0, 0, 0);
        }
        int[] n = new int[3];
        BiDocs docs = to.docs();
        java.util.function.BiConsumer<String, Object> put = (key, value) -> {
            try {
                n[docs.create(key, value) ? 0 : 1]++;
            } catch (RuntimeException e) {
                n[2]++;
                LOG.warn("BI import: {} failed: {}", key, e.getMessage());
            }
        };
        try {
            for (BiStore.Workspace w : jpa.workspaces()) {
                put.accept(ContentBiStore.workspaceKey(w.id()), w);
                for (BiStore.Member m : jpa.members(w.id())) {
                    put.accept(ContentBiStore.memberKey(m.workspace(), m.username()), m);
                }
            }
            for (BiStore.Item i : jpa.items()) {
                put.accept(ContentBiStore.itemKey(i.id()), i);
                for (BiStore.Version v : jpa.versions(i.id())) {
                    put.accept(ContentBiStore.versionKey(i.id(), v.version()), v);
                }
                for (BiStore.Revision r : jpa.revisions(i.id())) {
                    put.accept(ContentBiStore.revisionKey(i.id(), r.seq()), r);
                }
                for (BiStore.Event e : jpa.events(i.id())) {
                    put.accept(ContentBiStore.eventKey(i.id(), e.seq()), e);
                }
            }
            for (Map.Entry<String, Map<String, BiStore.Mark>> m : jpa.allMarks().entrySet()) {
                to.addMarks(m.getKey(), m.getValue());
                n[0]++;
            }
            for (Map.Entry<String, Map<String, Integer>> u : jpa.allUsage().entrySet()) {
                put.accept(ContentBiStore.ROOT + "usage/" + u.getKey() + "/imported.json", new ContentBiStore.Views(u.getValue()));   // its own segment: a rerun adds nothing twice
            }
            for (Map.Entry<String, String> s : jpa.allSettings().entrySet()) {
                put.accept(ContentBiStore.settingKey(s.getKey()), new ContentBiStore.Setting(s.getValue()));
            }
        } catch (RuntimeException e) {
            n[2]++;
            LOG.warn("BI import from the identity database stopped: {}", e.getMessage());
        }
        Result r = new Result(n[0], n[1], n[2]);
        if (r.failed() == 0) {
            String marker = "{\"imported\":" + r.imported() + ",\"skipped\":" + r.skipped() + ",\"at\":" + System.currentTimeMillis() + "}";
            try {
                c.put(MARKER, marker.getBytes(StandardCharsets.UTF_8), null);
            } catch (DrishtiException e) {
                if (e.errorCode() != ErrorCode.CONTENT_CONFLICT) {
                    throw e;                                             // another node wrote it first: the same import
                }
            }
        }
        if (r.imported() + r.failed() > 0) {
            LOG.info("BI: imported {} document(s) from the identity database into the content store ({} already there, {} failed)", r.imported(), r.skipped(), r.failed());
        }
        return r;
    }
}
