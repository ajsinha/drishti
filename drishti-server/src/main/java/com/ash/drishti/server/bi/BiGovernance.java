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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.bi.dataset.Composition;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.gov.ImpactAnalyzer;
import com.ash.drishti.server.bi.gov.LineDiff;
import com.ash.drishti.server.bi.gov.Surface;
import com.ash.drishti.server.bi.report.DatasetSet;
import com.ash.drishti.server.bi.report.Report;
import com.ash.drishti.server.bi.report.ReportParser;
import com.ash.drishti.server.bi.report.ReportValidator;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The read side of publishing and governance (docs/guides/BI_GOVERNANCE.md): the history of saved states, the side-by-side diff, what a reviewer is shown
 * (diff, Check results, a data preview), lineage and impact. Every answer starts from {@link BiService#readable}: a person sees the history and drafts of
 * what their workspace role lets them edit (a steward also what is proposed for review), the published text of what they may read, and the names of
 * other reports only where they could open them.
 */
public final class BiGovernance {

    private final BiService bi;
    private final BiStore store;
    private final BiAccess access;
    private final ImpactAnalyzer impact;
    private final BiGovProperties props;
    private final PackRegistry packs;
    private final DatasetService datasets;

    public BiGovernance(BiService bi, BiStore store, BiAccess access, ImpactAnalyzer impact, BiGovProperties props, PackRegistry packs, DatasetService datasets) {
        this.bi = bi;
        this.store = store;
        this.access = access;
        this.impact = impact;
        this.props = props;
        this.packs = packs;
        this.datasets = datasets;
    }

    // ---- who may see drafts ----------------------------------------------------------------------------------------------

    private boolean draftsVisible(Principal p, BiStore.Item i) {
        if (BiFolder.isShipped(i.id())) {
            return false;
        }
        return BiAccess.rank(bi.roleIn(bi.workspaceOf(i.workspace()), p)) >= BiAccess.rank("contributor")
                || i.status().equals("proposed") && access.has(p, BiAccess.STEWARD);
    }

    private BiStore.Item drafts(Principal p, String id, String what) {
        BiStore.Item i = bi.readableForGovernance(p, id);
        if (!draftsVisible(p, i)) {
            throw new DrishtiException(ErrorCode.BI_FORBIDDEN, what + " is for people who may edit '" + i.title() + "' (and, while it is proposed, for stewards); the published versions are open to you");
        }
        return i;
    }

    // ---- history ---------------------------------------------------------------------------------------------------------

    /** The saved states of an item, newest first, with who saved each and when, and the published versions among them; the governance events beside them. */
    public Map<String, Object> history(Principal p, String id) {
        BiStore.Item i = drafts(p, id, "the history of saved states");
        List<BiStore.Revision> revisions = store.revisions(id);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int k = 0; k < revisions.size(); k++) {
            BiStore.Revision r = revisions.get(k);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", r.seq());
            m.put("message", r.message());
            m.put("author", r.author());
            m.put("createdAt", r.createdAt());
            m.put("origin", r.origin());
            m.put("publishedVersion", r.publishedVersion());
            m.put("draft", k == 0);
            m.put("bytes", r.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("title", i.title());
        out.put("status", i.status());
        out.put("publishedVersion", i.publishedVersion());
        out.put("revisions", rows);
        out.put("events", events(id));
        out.put("canRestore", BiAccess.rank(bi.roleIn(bi.workspaceOf(i.workspace()), p)) >= BiAccess.rank("contributor") && !i.status().equals("proposed") && access.has(p, BiAccess.AUTHOR));
        return out;
    }

    private List<Map<String, Object>> events(String id) {
        return store.events(id).stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", e.seq());
            m.put("action", e.action());
            m.put("actor", e.actor());
            m.put("note", e.note());
            m.put("revision", e.revision());
            m.put("at", e.createdAt());
            return m;
        }).toList();
    }

    public Map<String, Object> revision(Principal p, String id, int seq) {
        drafts(p, id, "a saved state");
        BiStore.Revision r = store.revision(id, seq).orElseThrow(() -> new DrishtiException(ErrorCode.BI_NOT_FOUND, "no saved state " + seq + " of '" + id + "'"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", r.seq());
        m.put("message", r.message());
        m.put("author", r.author());
        m.put("createdAt", r.createdAt());
        m.put("origin", r.origin());
        m.put("publishedVersion", r.publishedVersion());
        m.put("body", r.body());
        return m;
    }

    // ---- diff --------------------------------------------------------------------------------------------------------------

    private record Side(String ref, String label, String body) {}

    private Side side(Principal p, BiStore.Item i, String ref) {
        String r = ref == null || ref.isBlank() ? "draft" : ref.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if (r.equals("draft")) {
                drafts(p, i.id(), "the draft");
                return new Side(r, "the draft", i.body());
            }
            if (r.equals("published")) {
                String b = impact.publishedBody(i);
                if (b == null) {
                    return new Side(r, "nothing published yet", "");
                }
                return new Side(r, "published version " + i.publishedVersion(), b);
            }
            if (r.matches("v\\d{1,6}")) {
                int n = Integer.parseInt(r.substring(1));
                if (BiFolder.isShipped(i.id())) {
                    if (n != i.publishedVersion()) {
                        throw new DrishtiException(ErrorCode.BI_NOT_FOUND, "shipped content keeps only its current version (" + i.publishedVersion() + ")");
                    }
                    return new Side(r, "the file", i.body());
                }
                BiStore.Version v = store.version(i.id(), n).orElseThrow(() -> new DrishtiException(ErrorCode.BI_NOT_FOUND, "'" + i.title() + "' has no version " + n));
                return new Side(r, "published version " + n, v.body());
            }
            if (r.matches("r\\d{1,6}")) {
                int n = Integer.parseInt(r.substring(1));
                drafts(p, i.id(), "a saved state");
                BiStore.Revision v = store.revision(i.id(), n).orElseThrow(() -> new DrishtiException(ErrorCode.BI_NOT_FOUND, "'" + i.title() + "' has no saved state " + n));
                return new Side(r, "saved state " + n + " (" + v.author() + ")", v.body());
            }
        } catch (NumberFormatException e) {
            // falls to the refusal below
        }
        throw new DrishtiException(ErrorCode.BI_INVALID, "'" + ref + "' is not draft, published, vN (a published version) or rN (a saved state)");
    }

    /** The YAML of two states side by side: {@code from} and {@code to} are {@code draft}, {@code published}, {@code vN} or {@code rN}. */
    public Map<String, Object> diff(Principal p, String id, String from, String to) {
        BiStore.Item i = bi.readableForGovernance(p, id);
        Side a = side(p, i, from == null || from.isBlank() ? "published" : from);
        Side b = side(p, i, to == null || to.isBlank() ? "draft" : to);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("from", Map.of("ref", a.ref(), "label", a.label()));
        m.put("to", Map.of("ref", b.ref(), "label", b.label()));
        m.putAll(LineDiff.diff(a.body(), b.body()).toMap());
        return m;
    }

    // ---- review ------------------------------------------------------------------------------------------------------------

    /** What a reviewer is shown: the diff of the draft against the published version, the Check results, what the change would break, a data preview. */
    public Map<String, Object> review(Principal p, String id) {
        BiStore.Item i = drafts(p, id, "the review page");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", i.title());
        m.put("kind", i.kind());
        m.put("status", i.status());
        m.put("proposedBy", i.proposedBy());
        m.put("proposedAt", i.proposedAt());
        List<Map<String, Object>> events = events(id);
        m.put("events", events);
        m.put("request", events.stream().filter(e -> "request".equals(e.get("action"))).findFirst().orElse(null));
        m.put("diff", diff(p, id, "published", "draft"));
        m.put("check", check(i));
        m.put("impact", impactMap(p, i, impactOf(i, null)));
        m.put("preview", preview(p, i));
        m.put("mayDecide", i.status().equals("proposed") && BiAccess.rank(bi.roleIn(bi.workspaceOf(i.workspace()), p)) >= 0 || access.has(p, BiAccess.STEWARD));
        return m;
    }

    /** The problems Check finds in the draft: a dataset's structure, expressions and references; a report's structure and names against its dataset. */
    Map<String, Object> check(BiStore.Item i) {
        List<Map<String, Object>> problems = new ArrayList<>();
        boolean ok = true;
        if ("dataset".equals(i.kind()) && !i.body().isBlank()) {
            DatasetParser.Result r = DatasetParser.parse(i.body());
            ok = r.ok();
            r.problems().forEach(x -> problems.add(Map.of("level", x.level(), "code", x.code(), "path", x.path(), "message", x.message())));
        } else if ("board".equals(i.kind()) && !i.body().isBlank()) {
            com.ash.drishti.server.bi.board.BoardParser.Result r = com.ash.drishti.server.bi.board.BoardParser.parse(i.body());
            ok = r.ok();
            r.problems().forEach(x -> problems.add(x.toMap()));
        } else if ("report".equals(i.kind()) && !i.body().isBlank()) {
            ReportParser.Result r = ReportParser.parse(i.body());
            List<ReportParser.Problem> all = new ArrayList<>(r.problems());
            if (r.report() != null) {
                BiStore.Item dsItem = impact.datasetOf(i.body());
                Dataset ds = dsItem == null ? null : DatasetParser.parse(impact.publishedBody(dsItem) != null ? impact.publishedBody(dsItem) : dsItem.body()).dataset();
                if (ds != null) {
                    all.addAll(ReportValidator.check(r.report(), ds, setsOf(r.report(), dsItem, ds)));
                } else {
                    all.add(new ReportParser.Problem("error", ErrorCode.REPORT_DATASET.code(), "dataset", "no dataset '" + r.report().dataset() + "' to check the report against"));
                }
            }
            ok = all.stream().noneMatch(ReportParser.Problem::error);
            all.forEach(x -> problems.add(x.toMap()));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        m.put("problems", problems);
        return m;
    }

    private Map<String, Object> preview(Principal p, BiStore.Item i) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            if ("dataset".equals(i.kind())) {
                Dataset ds = DatasetParser.parse(i.body()).dataset();
                if (ds == null || ds.tables().isEmpty()) {
                    m.put("available", false);
                    m.put("reason", "the draft has no readable tables yet");
                    return m;
                }
                Dataset.Table t = ds.tables().values().iterator().next();
                List<String> fields = t.columns().values().stream().filter(c -> c.expr() == null).limit(4).map(c -> t.name() + "." + c.name()).toList();
                List<String> measures = ds.measures().values().stream().filter(x -> x.python() == null).limit(2).map(Dataset.Measure::name).toList();
                QueryService.Result r = datasets.queryDraft(p, i, i.body(), new QueryRequest(fields, measures, List.of(), null, null, List.of(), props.previewRows(), "auto"));
                return previewMap(r, "the first rows of table " + t.name() + " from the draft");
            }
            if ("report".equals(i.kind())) {
                Report rep = ReportParser.parse(i.body()).report();
                BiStore.Item dsItem = rep == null ? null : impact.datasetOf(i.body());
                Dataset ds = dsItem == null ? null : DatasetParser.parse(impact.publishedBody(dsItem) != null ? impact.publishedBody(dsItem) : dsItem.body()).dataset();
                if (ds == null) {
                    m.put("available", false);
                    m.put("reason", "the report's dataset cannot be found");
                    return m;
                }
                Surface.Refs refs = Surface.refs(rep, ds.tables().keySet(), ref -> impact.refersTo(ref, dsItem));
                List<String> fields = refs.fields().stream().map(Surface.Use::name).distinct().limit(3).toList();
                List<String> measures = refs.measures().stream().map(Surface.Use::name).distinct().filter(ds.measures()::containsKey).limit(2).toList();
                if (fields.isEmpty() && measures.isEmpty()) {
                    m.put("available", false);
                    m.put("reason", "the report names no field or measure yet");
                    return m;
                }
                QueryService.Result r = datasets.queryItem(p, dsItem, new QueryRequest(fields, measures, List.of(), null, null, List.of(), props.previewRows(), "auto"), false);
                return previewMap(r, "a sample of the data the report reads (dataset '" + dsItem.title() + "')");
            }
            m.put("available", false);
            m.put("reason", "there is no data to preview for a " + i.kind());
        } catch (DrishtiException e) {
            m.put("available", false);
            m.put("reason", e.getMessage());
        }
        return m;
    }

    private Map<String, Object> previewMap(QueryService.Result r, String label) {
        Map<String, Object> m = new LinkedHashMap<>(datasets.json(r));
        m.put("available", true);
        m.put("label", label + ", with your row rules and masks");
        return m;
    }

    // ---- impact ------------------------------------------------------------------------------------------------------------

    /**
     * What a change to a dataset breaks. {@code remove} names fields ({@code table.column}) or measures to take away from the published dataset; null:
     * the change the draft would make (draft against published).
     */
    ImpactAnalyzer.Result impactOf(BiStore.Item dataset, List<String> remove) {
        if (!"dataset".equals(dataset.kind())) {
            return new ImpactAnalyzer.Result(new Surface.Change(List.of(), List.of(), List.of(), List.of(), List.of()), List.of(), List.of());
        }
        String published = impact.publishedBody(dataset);
        if (remove == null) {
            return published == null ? impact.impact(dataset, Surface.Offer.empty(), Surface.Offer.empty(), false) : impact.impact(dataset, published, dataset.body());
        }
        Surface.Offer was = ImpactAnalyzer.offer(published != null ? published : dataset.body());
        Set<String> fields = new TreeSet<>(was.fields());
        Set<String> measures = new TreeSet<>(was.measures());
        Set<String> hierarchies = new TreeSet<>(was.hierarchies());
        for (String r : remove) {
            fields.remove(r);
            measures.remove(r);
            hierarchies.remove(r);
            fields.removeIf(f -> f.startsWith(r + "."));                       // naming a table removes its columns
        }
        Surface.Offer now = new Surface.Offer(fields, measures, hierarchies, was.tables());
        return impact.impact(dataset, was, now, false);
    }

    private Map<String, Object> hitMap(Principal p, ImpactAnalyzer.Hit h) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean seen = bi.canRead(p, h.report());
        m.put("visible", seen);
        m.put("id", seen ? h.report().id() : null);
        m.put("title", seen ? h.report().title() : null);
        m.put("workspace", seen ? h.report().workspace() : null);
        m.put("endorsement", seen ? h.report().endorsement() : null);
        m.put("published", h.published());
        m.put("uses", seen ? h.uses() : List.of());
        return m;
    }

    private Map<String, Object> impactMap(Principal p, BiStore.Item dataset, ImpactAnalyzer.Result res) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("change", res.change().toMap());
        m.put("breaksPublished", res.breaksPublished());
        m.put("published", res.published().stream().map(h -> hitMap(p, h)).toList());
        m.put("drafts", res.drafts().stream().map(h -> hitMap(p, h)).toList());
        boolean refused = props.refuseBreaking() && res.change().breaking() && res.breaksPublished();
        m.put("refused", refused);
        m.put("canForce", refused && access.has(p, BiAccess.ADMIN));
        boolean wouldRevoke = props.revokeCertification() && res.change().breaking();
        List<String> revoke = new ArrayList<>();
        if (wouldRevoke && dataset.endorsement().equals("certified")) {
            revoke.add(dataset.title());
        }
        if (wouldRevoke) {
            res.published().stream().filter(h -> h.report().endorsement().equals("certified")).map(h -> bi.canRead(p, h.report()) ? h.report().title() : "(a report you cannot see)").forEach(revoke::add);
        }
        m.put("wouldRevokeCertification", revoke);
        return m;
    }

    /** "What breaks if I remove field X" or, without {@code remove}, "what breaks if I publish this dataset's draft". */
    public Map<String, Object> impact(Principal p, String id, List<String> remove) {
        BiStore.Item i = bi.readableForGovernance(p, id);
        if (!"dataset".equals(i.kind())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("applies", false);
            m.put("note", "Nothing in BI is built on a " + i.kind() + " yet; impact analysis answers for datasets (what their reports use).");
            return m;
        }
        Map<String, Object> m = new LinkedHashMap<>(impactMap(p, i, impactOf(i, remove)));
        m.put("applies", true);
        m.put("hypothetical", remove != null);
        m.put("remove", remove == null ? List.of() : remove);
        return m;
    }

    /** The dataset change a draft of this item would make, for the dataset's Check (the Model view): same answer as {@link #impact} for the saved draft with a given text. */
    public Map<String, Object> impactOfText(Principal p, String id, String yaml) {
        BiStore.Item i = bi.readableForGovernance(p, id);
        if (!"dataset".equals(i.kind())) {
            return Map.of("applies", false);
        }
        String published = impact.publishedBody(i);
        ImpactAnalyzer.Result res = published == null ? impact.impact(i, Surface.Offer.empty(), Surface.Offer.empty(), false) : impact.impact(i, published, yaml);
        Map<String, Object> m = new LinkedHashMap<>(impactMap(p, i, res));
        m.put("applies", true);
        return m;
    }

    // ---- lineage -----------------------------------------------------------------------------------------------------------

    private List<String> packsOfKind(String kind) {
        return packs.packs().stream().filter(x -> x.kinds().contains(kind)).map(Pack::name).sorted().toList();
    }

    /** The datasets a report reads, for checking it: the report's own ({@code home}) and the ones its visuals name, as published (else the draft). */
    private DatasetSet setsOf(Report report, BiStore.Item homeItem, Dataset home) {
        DatasetSet sets = DatasetSet.single(report.dataset(), homeItem, home);
        for (String ref : report.datasets()) {
            if (sets.of(ref) == null) {
                impact.datasetNamed(ref).ifPresent(item -> {
                    Dataset d = DatasetParser.parse(bi.sourceBody(item)).dataset();
                    if (d != null) {
                        sets.put(ref, new DatasetSet.Entry(item, d));
                    }
                });
            }
        }
        return sets;
    }

    /** What a composed or derived dataset is built from: its source datasets and what it takes of each. */
    private List<Map<String, Object>> sourcesOf(Principal p, Dataset ds) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Dataset.Input in : Composition.sources(ds)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", in.alias());
            m.put("kind", "dataset");
            m.put("dataset", in.dataset());
            BiStore.Item item = impact.datasetNamed(in.dataset()).orElse(null);
            m.put("id", item == null ? null : item.id());
            m.put("title", item == null || !bi.canRead(p, item) && !item.isPublished() ? null : item.title());
            m.put("by", in.by().stream().map(Dataset.Named::source).toList());
            m.put("fields", in.fields().stream().map(Dataset.Named::source).toList());
            m.put("measures", in.measures().stream().map(Dataset.Named::source).toList());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> tables(Dataset ds, Set<String> only) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (ds.framed()) {
            return out;                                                        // its one table is built from datasets: see sourcesOf
        }
        for (Dataset.Table t : ds.tables().values()) {
            if (only != null && !only.contains(t.name())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", t.name());
            m.put("kind", t.kind());
            m.put("packs", packsOfKind(t.kind()));
            m.put("connector", t.connector().isBlank() ? null : t.connector());
            m.put("each", t.each());
            m.put("mode", t.mode());
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> itemRef(Principal p, BiStore.Item x) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", x.id());
        m.put("title", x.title());
        m.put("kind", x.kind());
        m.put("status", x.status());
        m.put("endorsement", x.endorsement());
        m.put("publishedVersion", x.publishedVersion());
        return m;
    }

    /** Where an item comes from and what depends on it: a dataset's pack kinds and connectors and the reports that read it; a report's dataset, fields and measures. */
    public Map<String, Object> lineage(Principal p, String id) {
        BiStore.Item i = bi.readableForGovernance(p, id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", itemRef(p, i));
        if ("dataset".equals(i.kind())) {
            String body = impact.publishedBody(i) != null ? impact.publishedBody(i) : i.body();
            Dataset ds = DatasetParser.parse(body).dataset();
            m.put("upstream", ds == null ? List.of() : ds.framed() ? sourcesOf(p, ds) : tables(ds, null));
            List<Map<String, Object>> down = new ArrayList<>();
            int hidden = 0;
            Set<String> tablesAll = ds == null ? Set.of() : ds.tables().keySet();
            for (BiStore.Item r : impact.reportsOn(i)) {
                if (!bi.canRead(p, r)) {
                    hidden++;
                    continue;
                }
                Map<String, Object> rm = itemRef(p, r);
                String rb = impact.publishedBody(r) != null ? impact.publishedBody(r) : r.body();
                Report rep = ReportParser.parse(rb).report();
                rm.put("uses", rep == null ? Map.of() : Surface.used(Surface.refs(rep, tablesAll, ref -> impact.refersTo(ref, i))));
                down.add(rm);
            }
            for (BiStore.Item d : impact.dependentsOn(i)) {                 // composed and derived datasets built from this one
                if (!bi.canRead(p, d)) {
                    hidden++;
                    continue;
                }
                Map<String, Object> dm = itemRef(p, d);
                String db = impact.publishedBody(d) != null ? impact.publishedBody(d) : d.body();
                Dataset dd = DatasetParser.parse(db).dataset();
                dm.put("uses", dd == null ? Map.of() : Surface.used(Surface.refs(dd, ref -> impact.refersTo(ref, i), tablesAll)));
                down.add(dm);
            }
            m.put("downstream", down);
            m.put("hidden", hidden);
        } else if ("report".equals(i.kind())) {
            String body = bi.bodyFor(p, i);
            Report rep = ReportParser.parse(body).report();
            BiStore.Item dsItem = rep == null ? null : impact.datasetOf(body);
            if (rep == null) {
                m.put("note", "the report's YAML does not parse, so its lineage cannot be drawn");
            } else if (dsItem == null) {
                m.put("note", "the report's dataset '" + rep.dataset() + "' cannot be found");
            } else {
                String dsBody = impact.publishedBody(dsItem) != null ? impact.publishedBody(dsItem) : dsItem.body();
                Dataset ds = DatasetParser.parse(dsBody).dataset();
                m.put("dataset", bi.canRead(p, dsItem) || dsItem.isPublished() ? itemRef(p, dsItem) : null);
                Surface.Refs refs = Surface.refs(rep, ds == null ? Set.of() : ds.tables().keySet(), ref -> impact.refersTo(ref, dsItem));
                Map<String, List<String>> used = Surface.used(refs);
                m.put("uses", used);
                Set<String> tableNames = used.get("fields").stream().map(f -> f.substring(0, f.indexOf('.'))).collect(Collectors.toSet());
                m.put("upstream", ds == null ? List.of() : tables(ds, tableNames.isEmpty() ? null : tableNames));
            }
        } else {
            m.put("note", "boards and notebooks are not built yet, so they have no lineage");
        }
        return m;
    }
}
