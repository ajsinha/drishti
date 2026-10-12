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
import com.ash.drishti.server.bi.dataset.Composition;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.report.Report;
import com.ash.drishti.server.bi.report.ReportParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Sets a change to a dataset against the reports that read it (docs/guides/BI_GOVERNANCE.md, "Impact"). It reads the store and the BI folder
 * directly and decides nothing about who may see what: the caller filters what it shows. A report names its dataset by catalogue id, shipped file
 * name, the dataset's own name or its title (the viewer's rule); the same rule is used here.
 */
public final class ImpactAnalyzer implements Composition.Directory, AutoCloseable {

    /**
     * A report affected by a change.
     *
     * @param published true when the published text of the report is what breaks (readers would see it); false for a draft only
     * @param uses the broken names with where each is used, empty when only listing readers
     */
    public record Hit(BiStore.Item report, boolean published, List<String> uses) {}

    /** The change to a dataset and the reports it breaks: those readers see, and drafts in progress. */
    public record Result(Surface.Change change, List<Hit> published, List<Hit> drafts, List<Hit> altered) {

        public Result(Surface.Change change, List<Hit> published, List<Hit> drafts) {
            this(change, published, drafts, List.of());
        }

        public boolean breaksPublished() {
            return !published.isEmpty();
        }
    }

    private final BiStore store;
    private final BiFolder folder;

    private volatile Function<String, Map<String, String>> learned = name -> Map.of();

    public ImpactAnalyzer(BiStore store, BiFolder folder) {
        this.store = store;
        this.folder = folder;
        Composition.install(this);                       // composed and derived datasets find their sources through this analyzer's view of the store
    }

    @Override
    public void close() {
        Composition.uninstall(this);
    }

    /** Where the columns a derived dataset's Transform last returned are looked up (set once by the Transform service). */
    public void learnedColumns(Function<String, Map<String, String>> f) {
        this.learned = f;
    }

    @Override
    public Map<String, String> learned(String datasetName) {
        return learned.apply(datasetName);
    }

    /** The dataset item a source or a report names (by catalogue id, file name, the dataset's name or its title); a published one before a draft of the same name. */
    public Optional<BiStore.Item> datasetNamed(String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        BiStore.Item draft = null;
        for (BiStore.Item d : datasets()) {
            if (refersTo(ref, d)) {
                if (publishedBody(d) != null) {
                    return Optional.of(d);
                }
                draft = draft == null ? d : draft;
            }
        }
        return Optional.ofNullable(draft);
    }

    @Override
    public Optional<Composition.Found> find(String ref) {
        return datasetNamed(ref).map(d -> new Composition.Found(d.id(), publishedBody(d) != null ? publishedBody(d) : d.body()));
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
        return Composition.declaredName(body);
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

    /** Every board item, stored and shipped. */
    public List<BiStore.Item> boards() {
        List<BiStore.Item> all = new ArrayList<>();
        store.items().stream().filter(i -> "board".equals(i.kind())).forEach(all::add);
        folder.items().stream().filter(i -> "board".equals(i.kind())).forEach(all::add);
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

    /** The report items (stored or shipped) a board body names in its tiles, each once; empty when the board does not parse. */
    public List<BiStore.Item> reportsOf(String boardBody) {
        com.ash.drishti.server.bi.board.Board b = com.ash.drishti.server.bi.board.BoardParser.parse(boardBody).board();
        List<BiStore.Item> out = new ArrayList<>();
        if (b == null) {
            return out;
        }
        List<BiStore.Item> pool = reports();
        for (com.ash.drishti.server.bi.board.Board.Page pg : b.pages()) {
            for (com.ash.drishti.server.bi.board.Board.Tile t : pg.tiles()) {
                pool.stream().filter(i -> reportNamed(t.report(), i)).findFirst().filter(i -> !out.contains(i)).ifPresent(out::add);
            }
        }
        return out;
    }

    /** True when {@code ref} (a tile's {@code report:}) names this report item: its id, its file name or its title. */
    public static boolean reportNamed(String ref, BiStore.Item report) {
        return ref != null && !ref.isBlank() && (report.id().equals(ref) || report.id().equals("file:report:" + ref) || report.title().equalsIgnoreCase(ref));
    }

    /** The reports whose published text (or, when never published, whose draft) names this dataset. */
    public List<BiStore.Item> reportsOn(BiStore.Item dataset) {
        List<BiStore.Item> out = new ArrayList<>();
        for (BiStore.Item r : reports()) {
            String body = publishedBody(r) != null ? publishedBody(r) : r.body();
            Report parsed = ReportParser.parse(body).report();
            boolean draftNames = !r.body().equals(body) && ReportParser.parse(r.body()).report() != null
                    && ReportParser.parse(r.body()).report().datasets().stream().anyMatch(ref -> refersTo(ref, dataset));
            if (parsed != null && parsed.datasets().stream().anyMatch(ref -> refersTo(ref, dataset)) || draftNames) {
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
        List<Hit> altered = new ArrayList<>();
        if (!change.breaking() && !change.altering()) {
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
                List<String> changed = alteredUses(pub, dataset, tables, change);
                if (!changed.isEmpty()) {
                    altered.add(new Hit(r, true, changed));
                }
            }
            if (!BiFolder.isShipped(r.id()) && (pub == null || !pub.equals(r.body()))) {
                List<String> uses = broken(r.body(), dataset, tables, change);
                if (uses != null && !uses.isEmpty()) {
                    drafts.add(new Hit(r, false, uses));
                }
            }
        }
        for (BiStore.Item d : datasets()) {
            if (d.id().equals(dataset.id())) {
                continue;
            }
            String pub = publishedBody(d);
            if (pub != null) {
                List<String> uses = brokenSource(pub, dataset, tables, change);
                if (uses != null && !uses.isEmpty()) {
                    published.add(new Hit(d, true, uses));
                }
            }
            if (!BiFolder.isShipped(d.id()) && (pub == null || !pub.equals(d.body()))) {
                List<String> uses = brokenSource(d.body(), dataset, tables, change);
                if (uses != null && !uses.isEmpty()) {
                    drafts.add(new Hit(d, false, uses));
                }
            }
        }
        return new Result(change, published, drafts, altered);
    }

    private List<String> alteredUses(String body, BiStore.Item dataset, Set<String> tables, Surface.Change change) {
        Report report = ReportParser.parse(body).report();
        if (report == null || report.datasets().stream().noneMatch(ref -> refersTo(ref, dataset))) {
            return List.of();
        }
        return Surface.altered(Surface.refs(report, tables, ref -> refersTo(ref, dataset)), change);
    }

    /** The composed or derived datasets (stored or shipped) that take something from {@code dataset}: what a change to it can break, and what lineage shows below it. */
    public List<BiStore.Item> dependentsOn(BiStore.Item dataset) {
        List<BiStore.Item> out = new ArrayList<>();
        for (BiStore.Item d : datasets()) {
            if (d.id().equals(dataset.id())) {
                continue;
            }
            String body = publishedBody(d) != null ? publishedBody(d) : d.body();
            if (Composition.dependsOnOthers(body) && readsFrom(body, dataset) || !body.equals(d.body()) && Composition.dependsOnOthers(d.body()) && readsFrom(d.body(), dataset)) {
                out.add(d);
            }
        }
        return out;
    }

    private boolean readsFrom(String body, BiStore.Item dataset) {
        Dataset parsed = DatasetParser.parse(body).dataset();
        return parsed != null && Composition.sources(parsed).stream().anyMatch(in -> refersTo(in.dataset(), dataset));
    }

    private List<String> brokenSource(String body, BiStore.Item dataset, Set<String> tables, Surface.Change change) {
        if (!Composition.dependsOnOthers(body)) {
            return null;
        }
        Dataset parsed = DatasetParser.parse(body).dataset();
        if (parsed == null || Composition.sources(parsed).stream().noneMatch(in -> refersTo(in.dataset(), dataset))) {
            return null;
        }
        return Surface.broken(Surface.refs(parsed, ref -> refersTo(ref, dataset), tables), change);
    }

    private List<String> broken(String body, BiStore.Item dataset, Set<String> tables, Surface.Change change) {
        Report report = ReportParser.parse(body).report();
        if (report == null || report.datasets().stream().noneMatch(ref -> refersTo(ref, dataset))) {
            return null;
        }
        return Surface.broken(Surface.refs(report, tables, ref -> refersTo(ref, dataset)), change);
    }
}
