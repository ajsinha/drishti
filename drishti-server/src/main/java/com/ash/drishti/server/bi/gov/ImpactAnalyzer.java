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
package com.ash.drishti.server.bi.gov;

import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.bi.BiFolder;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.report.Report;
import com.ash.drishti.server.bi.report.ReportParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sets a change to a dataset against the reports that read it (docs/guides/BI_GOVERNANCE.md, "Impact"). It reads the store and the BI folder
 * directly and decides nothing about who may see what: the caller filters what it shows. A report names its dataset by catalogue id, shipped file
 * name, the dataset's own name or its title (the viewer's rule); the same rule is used here.
 */
public final class ImpactAnalyzer {

    /**
     * A report affected by a change.
     *
     * @param published true when the published text of the report is what breaks (readers would see it); false for a draft only
     * @param uses the broken names with where each is used, empty when only listing readers
     */
    public record Hit(BiStore.Item report, boolean published, List<String> uses) {}

    /** The change to a dataset and the reports it breaks: those readers see, and drafts in progress. */
    public record Result(Surface.Change change, List<Hit> published, List<Hit> drafts) {

        public boolean breaksPublished() {
            return !published.isEmpty();
        }
    }

    private final BiStore store;
    private final BiFolder folder;

    public ImpactAnalyzer(BiStore store, BiFolder folder) {
        this.store = store;
        this.folder = folder;
    }

    /** The offer of a dataset body; empty when it does not parse. */
    public static Surface.Offer offer(String body) {
        if (body == null || body.isBlank()) {
            return Surface.Offer.empty();
        }
        Dataset d = DatasetParser.parse(body).dataset();
        return d == null ? Surface.Offer.empty() : Surface.Offer.of(d);
    }

    /** The declared {@code dataset:} name of a dataset body, or an empty string. */
    public static String declaredName(String body) {
        Dataset d = body == null || body.isBlank() ? null : DatasetParser.parse(body).dataset();
        return d == null ? "" : d.name();
    }

    /** The text readers see: the published version of a stored item, the file of a shipped one; null when the item is not published. */
    public String publishedBody(BiStore.Item i) {
        if (BiFolder.isShipped(i.id())) {
            return i.body();
        }
        return i.isPublished() ? store.version(i.id(), i.publishedVersion()).map(BiStore.Version::body).orElse(null) : null;
    }

    /** True when {@code ref} (a report's {@code dataset:}) names this dataset item. */
    public boolean refersTo(String ref, BiStore.Item dataset) {
        if (ref == null || ref.isBlank()) {
            return false;
        }
        return dataset.id().equals(ref) || dataset.id().equals("file:dataset:" + ref) || dataset.title().equalsIgnoreCase(ref)
                || ref.equals(declaredName(dataset.body()));
    }

    /** Every report item, stored and shipped. */
    public List<BiStore.Item> reports() {
        List<BiStore.Item> all = new ArrayList<>();
        store.items().stream().filter(i -> "report".equals(i.kind())).forEach(all::add);
        folder.items().stream().filter(i -> "report".equals(i.kind())).forEach(all::add);
        return all;
    }

    /** Every dataset item, stored and shipped. */
    public List<BiStore.Item> datasets() {
        List<BiStore.Item> all = new ArrayList<>();
        store.items().stream().filter(i -> "dataset".equals(i.kind())).forEach(all::add);
        folder.items().stream().filter(i -> "dataset".equals(i.kind())).forEach(all::add);
        return all;
    }

    /** The dataset item a report body names (stored or shipped), or null. */
    public BiStore.Item datasetOf(String reportBody) {
        Report r = ReportParser.parse(reportBody).report();
        if (r == null) {
            return null;
        }
        for (BiStore.Item d : datasets()) {
            if (refersTo(r.dataset(), d)) {
                return d;
            }
        }
        return null;
    }

    /** The reports whose published text (or, when never published, whose draft) names this dataset. */
    public List<BiStore.Item> reportsOn(BiStore.Item dataset) {
        List<BiStore.Item> out = new ArrayList<>();
        for (BiStore.Item r : reports()) {
            String body = publishedBody(r) != null ? publishedBody(r) : r.body();
            Report parsed = ReportParser.parse(body).report();
            boolean draftNames = !r.body().equals(body) && ReportParser.parse(r.body()).report() != null && refersTo(ReportParser.parse(r.body()).report().dataset(), dataset);
            if (parsed != null && refersTo(parsed.dataset(), dataset) || draftNames) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * What replacing {@code before} by {@code after} (dataset bodies) breaks. {@code dataset} is the item whose body changes (reports are matched to it).
     * The offer of {@code before} is what readers have; names the reports use that are not in {@code after} are the damage.
     */
    public Result impact(BiStore.Item dataset, String before, String after) {
        Dataset parsed = after == null || after.isBlank() ? null : DatasetParser.parse(after).dataset();
        return impact(dataset, offer(before), parsed == null ? Surface.Offer.empty() : Surface.Offer.of(parsed), parsed == null);
    }

    /** As {@link #impact(BiStore.Item, String, String)} with the offers given; {@code unreadable} says the new text does not parse (then nothing can be compared). */
    public Result impact(BiStore.Item dataset, Surface.Offer was, Surface.Offer now, boolean unreadable) {
        Surface.Change change = unreadable ? new Surface.Change(List.of(), List.of(), List.of(), List.of(), List.of()) : Surface.change(was, now);
        List<Hit> published = new ArrayList<>();
        List<Hit> drafts = new ArrayList<>();
        if (!change.breaking()) {
            return new Result(change, published, drafts);
        }
        Set<String> tables = new HashSet<>(was.tables());
        tables.addAll(now.tables());
        for (BiStore.Item r : reports()) {
            String pub = publishedBody(r);
            if (pub != null) {
                List<String> uses = broken(pub, dataset, tables, change);
                if (uses != null && !uses.isEmpty()) {
                    published.add(new Hit(r, true, uses));
                }
            }
            if (!BiFolder.isShipped(r.id()) && (pub == null || !pub.equals(r.body()))) {
                List<String> uses = broken(r.body(), dataset, tables, change);
                if (uses != null && !uses.isEmpty()) {
                    drafts.add(new Hit(r, false, uses));
                }
            }
        }
        return new Result(change, published, drafts);
    }

    private List<String> broken(String body, BiStore.Item dataset, Set<String> tables, Surface.Change change) {
        Report report = ReportParser.parse(body).report();
        if (report == null || !refersTo(report.dataset(), dataset)) {
            return null;
        }
        return Surface.broken(Surface.refs(report, tables), change);
    }
}
