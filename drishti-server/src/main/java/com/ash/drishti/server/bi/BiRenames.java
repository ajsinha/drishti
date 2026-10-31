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
package com.ash.drishti.server.bi;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.bi.gov.ImpactAnalyzer;
import com.ash.drishti.server.bi.gov.ReportRenamer;
import com.ash.drishti.server.bi.gov.Surface;
import com.ash.drishti.server.bi.report.ReportParser;
import com.ash.drishti.server.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies a dataset's detected renames to the reports that use the old names, as part of publishing the dataset (docs/guides/BI_GUIDE.md, "Renames"): each
 * stored report that loses a name to a rename gets its published text rewritten as a new published version, and its draft (when it differs) rewritten as a new
 * saved state, so the reports keep drawing the moment the dataset's new version is live. Shipped reports are files and are not touched.
 */
final class BiRenames {

    private final BiStore store;
    private final AuditLog audit;
    private final BiProperties props;
    private final BiGovProperties gov;

    BiRenames(BiStore store, AuditLog audit, BiProperties props, BiGovProperties gov) {
        this.store = store;
        this.audit = audit;
        this.props = props;
        this.gov = gov;
    }

    /** The reports of {@code damage} (published and draft hits) that every lost name of is a rename and that are stored (not shipped). */
    List<BiStore.Item> renamable(ImpactAnalyzer.Result damage) {
        List<BiStore.Item> out = new ArrayList<>();
        for (ImpactAnalyzer.Hit h : concat(damage.published(), damage.drafts())) {
            BiStore.Item r = h.report();
            if ("report".equals(r.kind()) && !BiFolder.isShipped(r.id()) && h.uses().stream().allMatch(u -> u.contains("[renamed to ")) && out.stream().noneMatch(x -> x.id().equals(r.id()))) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * Rewrites the reports. Returns the lines to show the publisher ("Report 'X': 3 names updated"). Called before the dataset's version is written, after every
     * check has passed.
     */
    List<String> apply(Principal p, ImpactAnalyzer impact, ImpactAnalyzer.Result damage, int datasetVersion, String datasetTitle) {
        List<String> told = new ArrayList<>();
        List<Surface.Rename> renames = damage.change().renames();
        String note = "renamed " + renames.stream().map(r -> r.from() + " to " + r.to()).limit(3).toList() + " with " + datasetTitle + " v" + datasetVersion;
        for (BiStore.Item r : renamable(damage)) {
            BiStore.Item now = store.item(r.id()).orElse(r);
            String pub = impact.publishedBody(now);
            int replaced = 0;
            Instant at = Instant.now();
            int version = now.publishedVersion();
            String pubNew = pub;
            if (pub != null) {
                ReportRenamer.Result rr = ReportRenamer.apply(pub, renames);
                if (rr.replaced() > 0 && ReportParser.parse(rr.body()).ok()) {
                    pubNew = rr.body();
                    replaced += rr.replaced();
                    version = now.publishedVersion() + 1;
                    store.addVersion(new BiStore.Version(now.id(), version, pubNew, note, p.user(), at), props.versionsKeep());
                }
            }
            String draft = now.body();
            if (pub != null && draft.equals(pub)) {
                draft = pubNew;                                                            // the draft was the published text: it moves with it
            } else {
                ReportRenamer.Result dr = ReportRenamer.apply(draft, renames);
                if (dr.replaced() > 0 && ReportParser.parse(dr.body()).ok()) {
                    draft = dr.body();
                    replaced += dr.replaced();
                }
            }
            if (replaced == 0) {
                continue;
            }
            boolean published = version != now.publishedVersion();
            store.saveItem(new BiStore.Item(now.id(), now.workspace(), now.kind(), now.title(), now.description(), now.owner(), now.tags(), draft,
                    pub != null && draft.equals(pubNew) ? "published" : now.status(), version, now.endorsement(), now.endorsedBy(), now.audienceRoles(), now.audienceUsers(),
                    now.audiencePacks(), now.proposedBy(), now.proposedAt(), now.createdAt(), at, p.user(), published ? at : now.publishedAt(), published ? p.user() : now.publishedBy()));
            store.addRevision(now.id(), draft, note, p.user(), at, "rename", published ? version : 0, gov.revisionsKeep());
            store.addEvent(now.id(), "rename", p.user(), note, store.revisions(now.id()).stream().findFirst().map(BiStore.Revision::seq).orElse(0), at);
            audit.record(p.user(), "bi-item-renamed-names", now.id(), note + "; " + replaced + " name(s)");
            told.add("Report '" + now.title() + "': " + replaced + " name(s) updated" + (published ? ", published as version " + version : ", draft only"));
        }
        return told;
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
