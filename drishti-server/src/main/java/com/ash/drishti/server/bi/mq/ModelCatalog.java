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
package com.ash.drishti.server.bi.mq;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.bi.DatasetService;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.engine.Layers;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.layer.Contracts;
import com.ash.drishti.server.bi.layer.DatasetDef;
import com.ash.drishti.server.bi.layer.DatasetDefParser;
import com.ash.drishti.server.bi.layer.Flattener;
import com.ash.drishti.server.bi.layer.ImplicitModels;
import com.ash.drishti.server.bi.layer.ModelChecker;
import com.ash.drishti.server.bi.layer.ModelDef;
import com.ash.drishti.server.bi.layer.ModelDefParser;
import com.ash.drishti.server.bi.layer.PackDatasets;
import com.ash.drishti.server.bi.layer.ResolvedDataset;
import com.ash.drishti.server.bi.layer.Semver;
import com.ash.drishti.server.security.Principal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The model layer's catalogue (docs/architecture/BI_MODEL_SPEC.md sections 1 to 4, 7 and 8.4): finds the model a query names, as the caller (step 2 of the
 * validation, {@code DRS-9151}), and binds it to them. A name resolves to a {@code model} item ({@code trading-risk@1}: the highest version in the range the
 * caller may read), {@code dataset:<name>} to the implicit model of a dataset (a pack-generated one, a {@code rachana: 2} item, or a version-1 file), and a
 * version-1 dataset's name to the compatibility reader's model of it. The datasets of a model resolve by name and range among the generated ones and the
 * published {@code rachana: 2} items, their {@code extends} chains folded in. Also the Check of model-layer items, the publish gate (Check, the contract's
 * versioning, the security safeguard) and {@code describe}.
 */
public final class ModelCatalog {

    private final BiService bi;
    private final DatasetService datasets;
    private final PackDatasets packs;
    private final MqService mq;
    private final ModelRoles roles = new ModelRoles();
    private final ModelConsumers consumers;

    public ModelCatalog(BiService bi, DatasetService datasets, PackDatasets packs) {
        this(bi, datasets, packs, new MqService(MqProperties.defaults()));
    }

    /** @param mq asks the inner queries of datasets whose source is a model */
    public ModelCatalog(BiService bi, DatasetService datasets, PackDatasets packs, MqService mq) {
        this.bi = bi;
        this.datasets = datasets;
        this.packs = packs;
        this.mq = mq;
        this.consumers = new ModelConsumers(bi);
    }

    /** Who reads which model (reports, boards, notebooks, datasets on a model's answer, alerts, live subscriptions). */
    public ModelConsumers consumers() {
        return consumers;
    }

    /**
     * The consumers of the model {@code ref} names, as the caller may see them: {@code {model, consumers[{kind, id, title, status, owner, workspace}], hidden}}
     * ({@code hidden} counts those the caller may not read). The caller must read the model.
     */
    public Map<String, Object> usedBy(Principal p, String ref) {
        Found f = find(p, ref);
        List<Map<String, Object>> all;
        if (ref.startsWith(ImplicitModels.QUERY_PREFIX)) {
            all = consumers.find(ModelConsumers.namesImplicit(ref.substring(ImplicitModels.QUERY_PREFIX.length())), "");
        } else if (f.item() != null && !f.view().compat()) {
            all = consumers.of(f.item());
        } else {
            all = List.of();
        }
        List<Map<String, Object>> shown = new ArrayList<>();
        int hidden = 0;
        for (Map<String, Object> c : all) {
            boolean item = Set.of("report", "board", "notebook", "dataset").contains(String.valueOf(c.get("kind")));
            boolean visible;
            if (item) {
                try {
                    bi.readableItem(p, String.valueOf(c.get("id")));
                    visible = true;
                } catch (DrishtiException e) {
                    visible = false;
                }
            } else {
                visible = p.user().equals(c.get("owner"));
            }
            if (visible) {
                shown.add(c);
            } else {
                hidden++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", f.view().id());
        m.put("consumers", shown);
        m.put("hidden", hidden);
        return m;
    }

    /** The roles that may read every dataset of the model {@code ref} names (the role picker of its security tab); the caller must read the model. */
    public Set<String> readers(Principal p, String ref) {
        ModelView v = find(p, ref).view();
        Set<String> out = new LinkedHashSet<>(roles.all().stream().map(ModelRoles.Role::name).toList());
        v.datasets().values().forEach(d -> out.retainAll(roles.readers(d)));
        return out;
    }

    /** Why a model item may not be deleted: something still reads it (counted by kind; the consumers API names those the caller may see). Empty when free. */
    public String deleteHold(BiStore.Item model) {
        List<Map<String, Object>> all = consumers.of(model);
        if (all.isEmpty()) {
            return "";
        }
        Map<String, Integer> byKind = new java.util.TreeMap<>();
        all.forEach(c -> byKind.merge(String.valueOf(c.get("kind")), 1, Integer::sum));
        List<String> counts = byKind.entrySet().stream().map(e -> e.getValue() + " " + e.getKey() + (e.getValue() == 1 ? "" : "s")).toList();
        return "'" + model.title() + "' cannot be deleted: " + String.join(", ", counts) + " still read it. A model is shared, never owned by what reads it: "
                + "rebind or delete those first (its page lists them under Used by; GET /api/v1/bi/models/" + model.id() + "/consumers)";
    }

    /** The identity roles as the model layer sees them (the security tab's role picker, Check's DRS-9131). */
    /** How a SQL view dataset's statement is answered (set by the SQL module: the model layer does not depend on it). */
    public interface SqlViews {
        /** The answer of {@code sql} over the bound model, as its reader, at {@code date} when the statement names no time (null: its own). */
        QueryService.Result answer(MqService.Bound bound, String model, String sql, java.time.LocalDate date);
    }

    private volatile SqlViews sqlViews;

    public void sqlViews(SqlViews views) {
        this.sqlViews = views;
    }

    public SqlViews sqlViews() {
        return sqlViews;
    }

    public ModelRoles roles() {
        return roles;
    }

    public PackDatasets packs() {
        return packs;
    }

    MqService mq() {
        return mq;
    }

    DatasetService datasets() {
        return datasets;
    }

    // ---- binding -----------------------------------------------------------------------------------------------------

    /** The model the reference names, bound to the caller: how its requests run as them, and their side of the checks. */
    public MqService.Bound bind(Principal p, String ref) {
        return bound(p, find(p, ref));
    }

    /**
     * The model a published consumer (a report) is bound to, for its reader (docs/architecture/BI_DESIGN.md section 5.3): with read on the model, free; without
     * it, a reader who may view the published report gets the model limited to the report's {@link Envelope} (their own row rules and masks still apply).
     */
    public MqService.Bound bindVia(Principal p, String ref, String consumerId) {
        try {
            return bind(p, ref);
        } catch (MqException e) {
            if (consumerId == null || consumerId.isBlank() || !"DRS-9151".equals(e.problems().get(0).code())) {
                throw e;
            }
            BiStore.Item c;
            try {
                c = bi.readableItem(p, consumerId);
            } catch (DrishtiException x) {
                throw e;
            }
            if (!"report".equals(c.kind()) || !c.isPublished()) {
                throw e;
            }
            com.ash.drishti.server.bi.report.Report rep = com.ash.drishti.server.bi.report.ReportParser.parse(bi.publishedBody(c)).report();
            Found f = rep == null || rep.model() == null ? null : findPublished(ref);
            if (f == null) {
                throw e;
            }
            return bound(p, f).within(Envelope.of(c.id(), rep, f.view()));
        }
    }

    /** The published model a reference names, whoever reads it (only a consumer's envelope uses it). */
    private Found findPublished(String ref) {
        Semver.Ref r = Semver.Ref.parse(ref);
        if (r == null) {
            return null;
        }
        Found best = null;
        for (BiStore.Item i : bi.publishedOfKind("model")) {
            String body = bi.publishedBody(i);
            ModelDefParser.Result parsed = ModelDefParser.parse(body);
            ModelDef m = parsed.model();
            if (m == null || !(i.id().equals(r.name()) || i.id().equals("file:model:" + r.name()) || m.name().equals(r.name()))) {
                continue;
            }
            boolean older = !r.range().matches(Semver.parse(m.version()));
            if (older) {
                String earlier = inEarlierVersion(i, r.range());              // a consumer pinned to an earlier major keeps its latest published version
                if (earlier == null) {
                    continue;
                }
                body = earlier;
                parsed = ModelDefParser.parse(body);
                m = parsed.model();
            }
            if ("retired".equals(m.lifecycle())) {
                continue;
            }
            if (best == null || Semver.parse(m.version()).compareTo(Semver.parse(best.view().def().version())) > 0) {
                best = new Found(older ? earlierView(i.id(), m, parsed, body) : view(i.id(), m, parsed, body), i);
            }
        }
        return best;
    }

    /**
     * Impact analysis from envelopes (docs/architecture/BI_DESIGN.md sections 5.3 and 7): the consumers of the model that use a field or a measure (reports by
     * their envelopes; other consumers are listed as {@code unknown} use), as far as the caller may see them.
     */
    public Map<String, Object> impact(Principal p, String ref, String fieldOrMeasure) {
        Found f = find(p, ref);
        List<Map<String, Object>> uses = new ArrayList<>();
        List<Map<String, Object>> unknown = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> consumersSeen = (List<Map<String, Object>>) usedBy(p, ref).get("consumers");
        for (Map<String, Object> c : consumersSeen) {
            if (!"report".equals(c.get("kind"))) {
                unknown.add(c);
                continue;
            }
            BiStore.Item item = bi.readableItem(p, String.valueOf(c.get("id")));
            String body = item.isPublished() ? bi.publishedBody(item) : bi.bodyFor(p, item);
            com.ash.drishti.server.bi.report.Report rep = com.ash.drishti.server.bi.report.ReportParser.parse(body).report();
            if (rep != null && Envelope.of(item.id(), rep, f.view()).uses(fieldOrMeasure)) {
                uses.add(c);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", f.view().id());
        m.put("target", fieldOrMeasure);
        m.put("consumers", uses);
        m.put("unknown", unknown);
        return m;
    }

    /** The envelope of a report the caller may read ({@code GET /bi/reports/{id}/envelope}): what its readers without read on the model may ask. */
    public Map<String, Object> envelope(Principal p, String reportId) {
        BiStore.Item item = bi.readableItem(p, reportId);
        if (!"report".equals(item.kind())) {
            throw new DrishtiException(ErrorCode.BI_INVALID, "'" + reportId + "' is not a report");
        }
        com.ash.drishti.server.bi.report.Report rep = com.ash.drishti.server.bi.report.ReportParser.parse(item.isPublished() ? bi.publishedBody(item) : bi.bodyFor(p, item)).report();
        if (rep == null) {
            throw new DrishtiException(ErrorCode.REPORT_INVALID, "the report has errors");
        }
        ModelView v = null;
        if (rep.model() != null) {
            try {
                v = find(p, rep.model()).view();
            } catch (MqException e) {
                Found f = item.isPublished() && "DRS-9151".equals(e.problems().get(0).code()) ? findPublished(rep.model()) : null;   // a reader of the report
                if (f == null) {
                    throw e;
                }
                v = f.view();
            }
        }
        return Envelope.of(item.id(), rep, v).toMap();
    }

    /**
     * The model of a dataset item a consumer already holds (a report's dataset, an extract's, an alert's), bound to the caller: the implicit model of a
     * {@code rachana: 2} dataset, else the compatibility reader's model. Whether the caller may reach the item was the consumer's decision (a published report
     * reads its dataset with its publisher's grants); the caller's own row rules and masks still apply.
     */
    public MqService.Bound bindItem(Principal p, BiStore.Item item) {
        return bound(p, ofItem(p, item, true));
    }

    /**
     * The implicit model of a {@code rachana: 2} dataset's text (a draft being checked or published), bound to the caller: what its data tests ask. The text's
     * own row rules and masks apply, with the caller's roles.
     */
    public MqService.Bound bindText(Principal p, String body) {
        DatasetDefParser.Result r = DatasetDefParser.parse(body);
        if (!r.ok()) {
            throw new MqException(MqException.Problem.of("DRS-9151", "model", "the dataset has errors: " + r.firstError()));
        }
        List<DatasetParser.Problem> problems = new ArrayList<>();
        ResolvedDataset d = chain(r.dataset(), new LinkedHashSet<>(), problems);
        if (d == null) {
            throw new MqException(MqException.Problem.of("DRS-9151", "model", problems.isEmpty() ? "the dataset cannot be resolved" : problems.get(0).message()));
        }
        return bound(p, implicitOf(d));
    }

    /**
     * The implicit model of a pack-kind dataset made for one question (a Transform's kind input that names its own connector or {@code each}: TransformInputs),
     * bound to the caller. Like a generated dataset's, its security is the kind's: entitlements, the caller's row rules and masks.
     */
    MqService.Bound bindDataset(Principal p, DatasetDef d) {
        ModelView v = implicitOf(ResolvedDataset.of(d)).view();
        String salt = d.id() + "|" + d.source().connector() + "|" + d.source().each();
        MqService.Bound b = bound(p, new Found(new ModelView(v.id(), v.key(), hash(v.hash() + "|" + salt), v.def(), v.flat(), v.datasets(), v.unbuilt(), v.compat()), null));
        // no generation: its answers are never put in (or served from) the result cache, whose plan key does not name the connector, so the generated
        // dataset's answer through the routed connectors and this one through another can never be mistaken for each other (the Transform keeps its own)
        return new MqService.Bound(b.view(), b.runner(), b.context(), b.security(), null, b.envelope(), b.generationAt());
    }

    MqService.Bound bound(Principal p, Found f) {
        ModelView v = f.view();
        QueryService.Caller who = datasets.caller(p, v.flat());
        MqValidator.Context ctx = new MqValidator.Context(who.masks(), who.roles(), who.attributes(), who.enforce(), datasets.currentDate(), v.flat().model().holidays() == null ? datasets.calendar() : com.ash.drishti.common.BusinessCalendar.of(v.flat().model().holidays()));   // the model's calendar when it names one (wave B)
        MqService.Runner run;
        if (v.compat()) {
            BiStore.Item item = f.item();
            run = (ds, q, explain) -> ds == v.flat() ? datasets.queryItem(p, item, q, explain) : datasets.queryWith(p, item, ds, salt(v.flat(), ds), q, explain);
        } else {
            run = (ds, q, explain) -> {                             // connector, Python and model-answer datasets are staged for the query, as the reader
                DatasetSources staged = DatasetSources.aliases(v).isEmpty() ? null : new DatasetSources(this, p, v, q);
                return datasets.queryModel(p, v.id(), v.key(), ds, v.hash() + "|" + salt(v.flat(), ds), q, explain, staged);
            };
        }
        java.util.Set<String> hidden = ObjectSecurity.hidden(v, who.roles());
        com.ash.drishti.server.bi.plan.SecuritySignature.Reader security = new com.ash.drishti.server.bi.plan.SecuritySignature.Reader(who.user(), who.enforce(), who.roles(),
                who.attributes(), who.masks(), who.denied(), hidden);
        return new MqService.Bound(v, run, ctx, security, () -> generation(p, v), null, d -> datasets.generationAt(v.key(), v.flat(), d));
    }

    /** The generation the model's data is at (its layers and live tables), for a standing query. */
    /** The generation bus of this node. */
    public com.ash.drishti.server.bi.engine.GenerationBus bus() {
        return datasets.bus();
    }

    /** A running live table of a model's (its layer key's) generation. */
    public long liveGeneration(String key, String table) {
        return datasets.liveGeneration(key, table);
    }

    public long generation(Principal p, ModelView v) {
        return datasets.generation(v.id(), v.key(), v.flat());
    }

    private static String salt(Dataset flat, Dataset ds) {
        if (ds == flat) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        ds.measures().forEach((k, m) -> {
            if (!flat.measures().containsKey(k) || !java.util.Objects.equals(flat.measures().get(k).expr(), m.expr())) {   // a local measure, or one a calculation group or a parameter rewrote
                b.append(k).append('=').append(m.expr()).append(';');
            }
        });
        return hash(b.toString());
    }

    /** A model found for a caller: its view and, for a version-1 file, the item it runs through. */
    record Found(ModelView view, BiStore.Item item) {}

    Found find(Principal p, String ref) {
        if (ref == null || ref.isBlank()) {
            throw new MqException(MqException.Problem.of("DRS-9151", "model", "the query names no model (model: <name>@<major>, or dataset:<name> for one dataset)"));
        }
        if (ref.startsWith(ImplicitModels.QUERY_PREFIX)) {
            return implicit(p, ref.substring(ImplicitModels.QUERY_PREFIX.length()));
        }
        Semver.Ref r = Semver.Ref.parse(ref);
        if (r == null) {
            throw new MqException(MqException.Problem.of("DRS-9151", "model", "'" + ref + "' is not a model reference (name, name@1, name@1.2.0)"));
        }
        Found best = null;
        String problem = null;
        for (BiStore.Item i : candidates(p, "model")) {
            String body;
            try {
                body = bi.bodyFor(p, bi.readableItem(p, i.id()));
            } catch (DrishtiException e) {
                continue;
            }
            ModelDefParser.Result parsed = ModelDefParser.parse(body);
            ModelDef m = parsed.model();
            if (m == null || !(i.id().equals(r.name()) || i.id().equals("file:model:" + r.name()) || m.name().equals(r.name()))) {
                continue;
            }
            String earlier = r.range().matches(Semver.parse(m.version())) ? body : inEarlierVersion(i, r.range());
            if (earlier == null) {
                problem = "'" + r.name() + "' is at version " + m.version() + ", not in " + r.range().text();
                continue;
            }
            boolean older = !earlier.equals(body);
            if (older) {                                                     // a reader pinned to an earlier major keeps its latest published version
                body = earlier;
                parsed = ModelDefParser.parse(body);
                m = parsed.model();
            }
            if ("retired".equals(m.lifecycle())) {
                problem = "'" + r.name() + "' " + m.version() + " is retired" + (m.replacedBy() == null ? "" : ": use " + m.replacedBy());
                continue;
            }
            if (best == null || Semver.parse(m.version()).compareTo(Semver.parse(best.view().def().version())) > 0) {
                best = new Found(older ? earlierView(i.id(), m, parsed, body) : view(i.id(), m, parsed, body), i);
            }
        }
        if (best != null) {
            return best;
        }
        if (problem != null) {
            throw new MqException(MqException.Problem.of("DRS-9151", "model", problem));
        }
        Optional<BiStore.Item> v1 = bi.datasetNamed(r.name());
        if (v1.isPresent()) {
            return implicitOrCompat(p, v1.get());
        }
        throw new MqException(MqException.Problem.of("DRS-9151", "model", "no model '" + r.name() + "' that you may read"));
    }

    private List<BiStore.Item> candidates(Principal p, String kind) {
        List<BiStore.Item> all = new ArrayList<>(bi.publishedOfKind(kind));
        bi.draftsOfKind(p, kind).forEach(d -> {
            if (all.stream().noneMatch(x -> x.id().equals(d.id()))) {
                all.add(d);
            }
        });
        return all;
    }

    /**
     * The newest published text of a model item whose version is in {@code range}, or null: what a reference pinned to an earlier major reads after a new major
     * is published (docs/architecture/BI_MODEL_SPEC.md section 2.8; the item's history keeps {@code drishti.bi.versions-keep} versions). A retired one is left.
     */
    private String inEarlierVersion(BiStore.Item i, Semver.Range range) {
        if (i.publishedVersion() <= 0 || range.low() == null) {
            return null;
        }
        for (String b : bi.publishedBodies(i)) {
            ModelDef m = ModelDefParser.parse(b).model();
            if (m != null && range.matches(Semver.parse(m.version())) && !"retired".equals(m.lifecycle())) {
                return b;
            }
        }
        return null;
    }

    private ModelView view(String id, ModelDef m, ModelDefParser.Result parsed, String body) {
        if (!parsed.ok()) {
            throw new MqException(MqException.Problem.of("DRS-9151", "model", "the model '" + m.name() + "' has errors: " + parsed.firstError()));
        }
        List<DatasetParser.Problem> problems = new ArrayList<>();
        Map<String, ResolvedDataset> resolved = resolveAll(m, problems);
        problems.addAll(ModelChecker.check(m, resolved, packs::ofKind));
        problems.stream().filter(DatasetParser.Problem::error).findFirst().ifPresent(x -> {
            throw new MqException(MqException.Problem.of("DRS-9151", "model", "the model '" + m.name() + "' has errors: " + x.code() + " " + x.path() + ": " + x.message()));
        });
        DatasetSources.cycle(this, "model:" + m.name(), resolved).ifPresent(x -> {
            throw new MqException(MqException.Problem.of("DRS-9109", "model", x));
        });
        Flattener.Flat flat = Flattener.flatten(m, resolved, packs::ofKind);
        StringBuilder text = new StringBuilder(body);
        resolved.values().forEach(r -> text.append('|').append(r.def().name()).append('@').append(r.def().version()).append(r.layers()).append(r.masked()));
        return new ModelView(id, Layers.key(id), hash(text.toString()), m, flat.dataset(), resolved, flat.unbuilt(), false);
    }

    /** As {@link #view} for an earlier major's text read after a new major was published: its layers are kept apart from the new major's. */
    private ModelView earlierView(String id, ModelDef m, ModelDefParser.Result parsed, String body) {
        ModelView v = view(id, m, parsed, body);
        return new ModelView(v.id(), v.key() + "@" + m.major(), v.hash(), v.def(), v.flat(), v.datasets(), v.unbuilt(), v.compat());
    }

    private Found implicit(Principal p, String name) {
        Optional<ResolvedDataset> d = resolveDataset(name, Semver.Range.ANY, new LinkedHashSet<>(), new ArrayList<>());
        if (d.isPresent() && !name.startsWith(DatasetDef.PACK_PREFIX) && !mayReadDataset(p, name)) {
            d = Optional.empty();                                          // the implicit model of an item reads with the item's audience (a pack's: its kinds)
        }
        if (d.isPresent()) {
            return implicitOf(d.get());
        }
        Optional<BiStore.Item> v1 = bi.datasetNamed(name);
        if (v1.isPresent()) {
            return implicitOrCompat(p, v1.get());
        }
        throw new MqException(MqException.Problem.of("DRS-9151", "model", "no dataset '" + name + "' that you may read"));
    }

    private Found implicitOf(ResolvedDataset d) {
        DatasetSources.cycle(this, "dataset:" + d.def().name(), Map.of(d.def().name(), d)).ifPresent(x -> {
            throw new MqException(MqException.Problem.of("DRS-9109", "model", x));
        });
        ImplicitModels.Implicit im = ImplicitModels.of(d, kind -> packs.ofKind(kind).map(ResolvedDataset::of));
        Flattener.Flat flat = Flattener.flatten(im.model(), im.datasets(), packs::ofKind);
        String id = im.model().id();
        StringBuilder text = new StringBuilder();
        im.datasets().values().forEach(r -> text.append(r.def().name()).append('@').append(r.def().version()).append(r.def().columns().keySet()).append(r.layers()));
        return new Found(new ModelView(id, Layers.key(id), hash(text.toString()), im.model(), flat.dataset(), im.datasets(), flat.unbuilt(), false), null);
    }

    /** Whether the caller may read a published {@code rachana: 2} dataset item of this name (or id). */
    private boolean mayReadDataset(Principal p, String name) {
        for (BiStore.Item i : bi.publishedOfKind("dataset")) {
            DatasetDef d = DatasetDefParser.isVersion2(bi.publishedBody(i)) ? DatasetDefParser.parse(bi.publishedBody(i)).dataset() : null;
            if (d != null && (d.name().equals(name) || i.id().equals(name) || i.id().equals("file:dataset:" + name))) {
                try {
                    bi.readableItem(p, i.id());
                    return true;
                } catch (DrishtiException e) {
                    // not this caller's: another item of the name may be
                }
            }
        }
        return false;
    }

    /** A version-1 dataset item as the caller reads it: a {@code rachana: 2} body is an implicit model; else the compatibility reader's model. */
    private Found implicitOrCompat(Principal p, BiStore.Item item) {
        BiStore.Item readable;
        try {
            readable = bi.readableItem(p, item.id());
        } catch (DrishtiException e) {
            throw new MqException(MqException.Problem.of("DRS-9151", "model", "no model or dataset '" + item.title() + "' that you may read"));
        }
        return ofItem(p, readable, false);
    }

    private Found ofItem(Principal p, BiStore.Item readable, boolean consumer) {
        String body = bi.bodyFor(p, readable);
        if (DatasetDefParser.isVersion2(body)) {
            DatasetDefParser.Result r = DatasetDefParser.parse(body);
            if (!r.ok()) {
                throw new MqException(MqException.Problem.of("DRS-9151", "model", "the dataset '" + readable.title() + "' has errors: " + r.firstError()));
            }
            List<DatasetParser.Problem> problems = new ArrayList<>();
            ResolvedDataset d = chain(r.dataset(), new LinkedHashSet<>(), problems);
            if (d == null) {
                throw new MqException(MqException.Problem.of("DRS-9151", "model", problems.isEmpty() ? "the dataset cannot be resolved" : problems.get(0).message()));
            }
            return implicitOf(d);
        }
        // the compatibility reader is gone (2.2, docs/architecture/BI_MODEL_SPEC.md section 8.4): a 2.1 dataset is never read as a model
        throw new MqException(MqException.Problem.of("DRS-9151", "model", "'" + readable.title() + "' is a rachana: 1 dataset, no longer read as a model: "
                + "drishti.py bi migrate --models converts it to datasets and a model of the same name (docs/architecture/BI_MODEL_SPEC.md section 8.3)"));
    }

    /** True for a {@code rachana: 1} dataset item (a live report's dataset until wave W3): asked through the engine as before, never as a model. */
    public boolean isVersion1(Principal p, BiStore.Item item) {
        return "dataset".equals(item.kind()) && !DatasetDefParser.isVersion2(bi.bodyFor(p, item));
    }

    /** A {@code rachana: 1} dataset's answer through the engine, as the caller (its rules joined into the query, as in 2.1). */
    public QueryService.Result askVersion1(Principal p, BiStore.Item item, com.ash.drishti.server.bi.engine.QueryRequest q, boolean explain) {
        return datasets.queryItem(p, item, q, explain);
    }

    // ---- datasets ----------------------------------------------------------------------------------------------------

    /** Every dataset of a model, resolved; a dataset that is missing or out of range is a DRS-9120 problem. */
    Map<String, ResolvedDataset> resolveAll(ModelDef m, List<DatasetParser.Problem> problems) {
        Map<String, ResolvedDataset> out = new LinkedHashMap<>();
        for (ModelDef.Use u : m.datasets().values()) {
            Optional<ResolvedDataset> r = resolveDataset(u.dataset(), u.range(), new LinkedHashSet<>(), problems);
            if (r.isEmpty()) {
                problems.add(new DatasetParser.Problem("error", "DRS-9120", "datasets." + u.alias(), "no dataset '" + u.dataset() + "'"
                        + (u.range().text().isEmpty() ? "" : " at " + u.range().text()) + " is published (a pack-generated one, or a rachana: 2 dataset)"));
            } else {
                out.put(u.alias(), r.get());
            }
        }
        return out;
    }

    /** A dataset by name and range: a pack-generated one, else the highest published {@code rachana: 2} item in range, its {@code extends} chain folded in. */
    Optional<ResolvedDataset> resolveDataset(String name, Semver.Range range, Set<String> visiting, List<DatasetParser.Problem> problems) {
        if (name.startsWith(DatasetDef.PACK_PREFIX)) {
            return packs.named(name).filter(d -> range.matches(Semver.parse(d.version()))).map(ResolvedDataset::of);
        }
        DatasetDef best = null;
        for (BiStore.Item i : bi.publishedOfKind("dataset")) {
            String body = bi.publishedBody(i);
            if (!DatasetDefParser.isVersion2(body)) {
                continue;
            }
            DatasetDefParser.Result r = DatasetDefParser.parse(body);
            DatasetDef d = r.dataset();
            if (d == null || !r.ok() || !(d.name().equals(name) || i.id().equals(name) || i.id().equals("file:dataset:" + name)) || !range.matches(Semver.parse(d.version()))) {
                continue;
            }
            if (best == null || Semver.parse(d.version()).compareTo(Semver.parse(best.version())) > 0) {
                best = d;
            }
        }
        return best == null ? Optional.empty() : Optional.ofNullable(chain(best, visiting, problems));
    }

    /**
     * Every published model that resolves, flattened under its own layers key (a model's tables keep their layers there): what a data-load signal clears and
     * the refresh scheduler rebuilds, as it does a 2.1 dataset's. A model that does not resolve is left out (Check and publish say why).
     */
    public List<QueryService.Loaded> publishedLoaded() {
        List<QueryService.Loaded> out = new ArrayList<>();
        for (BiStore.Item i : bi.publishedOfKind("model")) {
            String body = bi.publishedBody(i);
            ModelDefParser.Result parsed = ModelDefParser.parse(body);
            if (parsed.model() == null) {
                continue;
            }
            try {
                ModelView v = view(i.id(), parsed.model(), parsed, body);
                out.add(new QueryService.Loaded(v.id(), v.key(), v.flat(), v.hash()));
            } catch (RuntimeException e) {
                // not resolvable now: nothing of it is kept to clear
            }
        }
        return out;
    }

    /**
     * What a refresh of the dataset {@code name} rebuilds: every published model that reads it (directly or through {@code extends}), with the tables (aliases)
     * that read it, and the dataset's own implicit model ({@code dataset:<name>}).
     */
    public List<DatasetService.ModelGateway.Reader> readersOf(String name) {
        List<DatasetService.ModelGateway.Reader> out = new ArrayList<>();
        for (BiStore.Item i : bi.publishedOfKind("model")) {
            String body = bi.publishedBody(i);
            ModelDefParser.Result parsed = ModelDefParser.parse(body);
            if (parsed.model() == null) {
                continue;
            }
            try {
                ModelView v = view(i.id(), parsed.model(), parsed, body);
                List<String> tables = v.datasets().entrySet().stream().filter(e -> e.getValue().def().name().equals(name) || e.getValue().chain().contains(name))
                        .map(Map.Entry::getKey).toList();
                if (!tables.isEmpty()) {                                     // a staged table (Python, a query, a model's answer) has no layer to rebuild
                    out.add(new DatasetService.ModelGateway.Reader(new QueryService.Loaded(v.id(), v.key(), v.flat(), v.hash()),
                            tables.stream().filter(t -> !DatasetSources.aliases(v).contains(t)).toList()));
                }
            } catch (RuntimeException e) {
                // not resolvable now: nothing of it is kept to refresh
            }
        }
        try {
            resolveDataset(name, Semver.Range.ANY, new LinkedHashSet<>(), new ArrayList<>()).ifPresent(d -> {
                ModelView v = implicitOf(d).view();
                out.add(new DatasetService.ModelGateway.Reader(new QueryService.Loaded(v.id(), v.key(), v.flat(), v.hash()),
                        v.datasets().keySet().stream().filter(t -> !DatasetSources.aliases(v).contains(t)).toList()));
            });
        } catch (RuntimeException e) {
            // the dataset does not resolve (Check says why): only the models above
        }
        return out;
    }

    /** The published models of this name (or catalogue id), with their datasets resolved as far as they resolve: what the cycle check walks. */
    List<Map<String, ResolvedDataset>> publishedModels(String name) {
        List<Map<String, ResolvedDataset>> out = new ArrayList<>();
        for (BiStore.Item i : bi.publishedOfKind("model")) {
            ModelDef m = ModelDefParser.parse(bi.publishedBody(i)).model();
            if (m != null && (m.name().equals(name) || i.id().equals(name) || i.id().equals("file:model:" + name))) {
                out.add(resolveAll(m, new ArrayList<>()));
            }
        }
        return out;
    }

    /** A dataset's text with its {@code extends} chain folded in (null when the chain does not resolve; the problems say why). */
    ResolvedDataset chainOf(DatasetDef d, List<DatasetParser.Problem> problems) {
        return chain(d, new LinkedHashSet<>(), problems);
    }

    private ResolvedDataset chain(DatasetDef d, Set<String> visiting, List<DatasetParser.Problem> problems) {
        if (d.extendsRef() == null) {
            return ResolvedDataset.of(d);
        }
        if (!visiting.add(d.name())) {
            problems.add(new DatasetParser.Problem("error", "DRS-9105", "extends", "'" + d.name() + "' extends itself through " + visiting));
            return null;
        }
        Semver.Ref base = Semver.Ref.parse(d.extendsRef());
        Optional<ResolvedDataset> b = base == null ? Optional.empty() : resolveDataset(base.name(), base.range(), visiting, problems);
        if (b.isEmpty()) {
            problems.add(new DatasetParser.Problem("error", "DRS-9105", "extends", "the base '" + d.extendsRef() + "' of '" + d.name() + "' is not published"));
            return null;
        }
        for (DatasetDef.Col c : d.columns().values()) {
            DatasetDef.Col was = b.get().def().columns().get(c.name());
            if (was != null && !was.type().equals(c.type())) {
                problems.add(new DatasetParser.Problem("error", "DRS-9105", "columns." + c.name(), "a narrowing dataset cannot change the type of '" + c.name() + "'"));
            }
        }
        problems.addAll(com.ash.drishti.server.bi.layer.Derivations.check(d, b.get().def(), packs::ofKind));
        return ResolvedDataset.extend(d, b.get());
    }

    // ---- check, publish, describe ------------------------------------------------------------------------------------

    /** Check of a model or a {@code rachana: 2} dataset's YAML (which, by its top key): the problems, and for a model the safeguard's findings. */
    public Map<String, Object> check(String yaml) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<DatasetParser.Problem> problems = new ArrayList<>();
        if (DatasetDefParser.isVersion2(yaml)) {
            DatasetDefParser.Result r = DatasetDefParser.parse(yaml);
            problems.addAll(r.problems());
            m.put("kind", "dataset");
            if (r.dataset() != null) {
                DatasetDef d = r.dataset();
                if (d.source() != null && DatasetDef.Source.PACK.equals(d.source().type()) && !d.source().pack().isBlank()
                        && packs.named(DatasetDef.PACK_PREFIX + d.source().pack() + "." + d.source().kind()).isEmpty()) {
                    problems.add(new DatasetParser.Problem("error", "DRS-9102", "source", "no pack '" + d.source().pack() + "' with the kind '" + d.source().kind() + "' is enabled"));
                }
                ResolvedDataset folded = d.extendsRef() != null && r.ok() ? chain(d, new LinkedHashSet<>(), problems) : null;
                if (folded != null) {                                         // a narrowing dataset: the base's columns, grain and keys are its own
                    d = folded.def();
                    m.put("extends", d.extendsRef());
                    m.put("chain", folded.chain());
                }
                if (d.source() != null && DatasetDef.Source.MODEL.equals(d.source().type()) && r.ok()) {
                    DatasetSources.cycle(this, "dataset:" + d.name(), Map.of(d.name(), ResolvedDataset.of(d))).ifPresent(x -> problems.add(new DatasetParser.Problem("error", "DRS-9109", "source.model", x)));
                }
                if (r.ok()) {                                                 // the root: Check refuses a dataset whose root cannot be traced (DRS-9110)
                    m.put("lineage", com.ash.drishti.server.bi.layer.Derivations.lineage(folded != null ? folded : ResolvedDataset.of(d), packs, problems));
                }
                m.put("name", d.name());
                m.put("version", d.version());
                m.put("columns", d.columns().keySet());
                if (d.grain() != null) {
                    m.put("grain", Map.of("keys", d.grain().keys(), "says", d.grain().says() == null ? "" : d.grain().says()));
                }
            }
        } else {
            ModelDefParser.Result r = ModelDefParser.parse(yaml);
            problems.addAll(r.problems());
            m.put("kind", "model");
            if (r.model() != null) {
                Map<String, ResolvedDataset> resolved = resolveAll(r.model(), problems);
                problems.addAll(ModelChecker.check(r.model(), resolved, packs::ofKind));
                problems.addAll(roles.warnings(r.model(), resolved));
                problems.addAll(ObjectSecurity.reachable(r.model(), resolved));       // a hidden measure its roles can still work out
                for (Map.Entry<String, ResolvedDataset> e : resolved.entrySet()) {
                    List<DatasetParser.Problem> roots = new ArrayList<>();
                    com.ash.drishti.server.bi.layer.Derivations.lineage(e.getValue(), packs, roots);
                    roots.forEach(x -> problems.add(new DatasetParser.Problem(x.level(), x.code(), "datasets." + e.getKey(), x.message())));
                }
                DatasetSources.cycle(this, "model:" + r.model().name(), resolved).ifPresent(x -> problems.add(new DatasetParser.Problem("error", "DRS-9109", "datasets", x)));
                m.put("name", r.model().name());
                m.put("version", r.model().version());
                m.put("datasets", resolved.entrySet().stream().map(e -> Map.of("alias", e.getKey(), "dataset", e.getValue().def().name(), "version", e.getValue().def().version())).toList());
                m.put("exposures", ModelChecker.exposures(r.model(), resolved).stream().map(x -> Map.of("dataset", x.alias(), "role", x.role())).toList());
                m.put("measures", Flattener.withDefaults(r.model(), resolved).keySet());
            }
        }
        m.put("valid", problems.stream().noneMatch(DatasetParser.Problem::error));
        m.put("problems", problems.stream().map(x -> Map.of("level", x.level(), "code", x.code(), "path", x.path(), "message", x.message())).toList());
        return m;
    }

    /**
     * The publish gate of a model or a {@code rachana: 2} dataset: Check must pass ({@code DRS-9100}, {@code DRS-9120}), a model's safeguard must be answered
     * ({@code DRS-9130}), and against the published version a breaking change needs a new major ({@code DRS-9126}). Answers the {@code may-see-all} notes the
     * publish relies on, for the audit log (empty when none).
     */
    public String gate(Principal p, BiStore.Item i) {
        String body = i.body();
        String published = i.isPublished() ? bi.publishedBody(i) : null;
        if ("dataset".equals(i.kind())) {
            Map<String, Object> c = check(body);
            if (!Boolean.TRUE.equals(c.get("valid"))) {
                throw new DrishtiException(ErrorCode.LAYER_DATASET_INVALID, "'" + i.title() + "' cannot be published: " + firstError(c));
            }
            if (published != null && DatasetDefParser.isVersion2(published)) {
                DatasetDef was = DatasetDefParser.parse(published).dataset();
                DatasetDef now = DatasetDefParser.parse(body).dataset();
                String why = was == null ? null : Contracts.datasets(was, now).refusal();
                if (why != null) {
                    throw new DrishtiException(ErrorCode.LAYER_MODEL_BREAKING, "'" + i.title() + "' cannot be published: " + why);
                }
            }
            return "";
        }
        ModelDefParser.Result r = ModelDefParser.parse(body);
        com.ash.drishti.server.bi.ModelNames.requireUniqueInCatalogue(bi.publishedOfKind("model"), i.id(), body, bi::publishedBody);   // one published model per name
        List<DatasetParser.Problem> problems = new ArrayList<>(r.problems());
        Map<String, ResolvedDataset> resolved = r.model() == null ? Map.of() : resolveAll(r.model(), problems);
        if (r.model() != null) {
            problems.addAll(ModelChecker.check(r.model(), resolved, packs::ofKind));
        }
        problems.stream().filter(DatasetParser.Problem::error).findFirst().ifPresent(x -> {
            throw new DrishtiException(ErrorCode.LAYER_MODEL_INVALID, "'" + i.title() + "' cannot be published: " + x.code() + " " + x.path() + ": " + x.message());
        });
        List<ModelChecker.Exposure> open = ModelChecker.exposures(r.model(), resolved);
        if (!open.isEmpty()) {
            throw new DrishtiException(ErrorCode.LAYER_MODEL_SAFEGUARD, "'" + i.title() + "' cannot be published: " + open.size() + " dataset" + (open.size() == 1 ? "" : "s")
                    + " would show every row to a role restricted elsewhere in the model (" + String.join("; ", open.stream().map(x -> x.alias() + " to " + x.role()).toList())
                    + "). Give each a row rule, or say `may-see-all: { <dataset>: [<role>] }` under security");
        }
        if (published != null) {
            ModelDef was = ModelDefParser.parse(published).model();
            String why = was == null ? null : Contracts.models(was, r.model(), resolveAll(was, new ArrayList<>()), resolved).refusal();
            if (why != null) {
                throw new DrishtiException(ErrorCode.LAYER_MODEL_BREAKING, "'" + i.title() + "' cannot be published: " + why);
            }
        }
        List<String> notes = new ArrayList<>();
        r.model().security().maySeeAll().forEach((alias, roles) -> notes.add(alias + ": " + roles));
        return notes.isEmpty() ? "" : "version " + r.model().version() + " lets roles see every row of: " + String.join("; ", notes);
    }

    /**
     * Why a report bound to {@code ref} cannot be published (empty when it can): every reader must reach a published model, so a draft model is refused, and
     * the published model must pass its Check and the security safeguard ({@code DRS-9130}; a folder file is not gated when it is read, so it is checked here).
     */
    public List<String> publishProblems(Principal p, String ref) {
        try {
            find(p, ref);
        } catch (DrishtiException e) {
            return List.of("you may not read the model '" + ref + "' this report reads, so you cannot publish a report on it: a report uses a model by reference, "
                    + "which needs read on the model (a member of its workspace, or in its audience). Ask its owner to share it");
        }
        return publishProblems(ref);
    }

    public List<String> publishProblems(String ref) {
        if (ref.startsWith(ImplicitModels.QUERY_PREFIX)) {
            String name = ref.substring(ImplicitModels.QUERY_PREFIX.length());
            boolean found = resolveDataset(name, Semver.Range.ANY, new LinkedHashSet<>(), new ArrayList<>()).isPresent()
                    || bi.datasetNamed(name).filter(BiStore.Item::isPublished).isPresent();
            return found ? List.of() : List.of("no published dataset '" + name + "' for the report's model: publish the dataset first");
        }
        Semver.Ref r = Semver.Ref.parse(ref);
        ModelDef best = null;
        for (BiStore.Item i : bi.publishedOfKind("model")) {
            ModelDef m = ModelDefParser.parse(bi.publishedBody(i)).model();
            if (m != null && r != null && (m.name().equals(r.name()) || i.id().equals(r.name())) && !r.range().matches(Semver.parse(m.version()))) {
                String earlier = inEarlierVersion(i, r.range());              // a report pinned to an earlier major reads its latest published version
                m = earlier == null ? m : ModelDefParser.parse(earlier).model();
            }
            if (m != null && r != null && (m.name().equals(r.name()) || i.id().equals(r.name())) && r.range().matches(Semver.parse(m.version())) && !"retired".equals(m.lifecycle())
                    && (best == null || Semver.parse(m.version()).compareTo(Semver.parse(best.version())) > 0)) {
                best = m;
            }
        }
        if (best == null) {
            if (r != null && bi.datasetNamed(r.name()).filter(BiStore.Item::isPublished).isPresent()) {
                return List.of();                                                // a version-1 dataset's name: its compatibility model
            }
            return List.of("the model '" + ref + "' is not published (a draft model is read by its author only): publish the model first, where its Check and security safeguard run");
        }
        List<DatasetParser.Problem> problems = new ArrayList<>();
        Map<String, ResolvedDataset> resolved = resolveAll(best, problems);
        problems.addAll(ModelChecker.check(best, resolved));
        List<String> out = new ArrayList<>(problems.stream().filter(DatasetParser.Problem::error).map(x -> x.code() + " " + x.path() + ": " + x.message()).toList());
        List<ModelChecker.Exposure> open = ModelChecker.exposures(best, resolved);
        if (!open.isEmpty()) {
            out.add("DRS-9130 the model '" + best.name() + "' would show every row of " + String.join("; ", open.stream().map(x -> x.alias() + " to " + x.role()).toList())
                    + ": give each a row rule or a `may-see-all` note in the model before a report on it is published");
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String firstError(Map<String, Object> check) {
        return ((List<Map<String, Object>>) check.get("problems")).stream().filter(x -> "error".equals(x.get("level"))).findFirst()
                .map(x -> x.get("code") + " " + x.get("path") + ": " + x.get("message")).orElse("");
    }

    /**
     * {@code POST /api/v1/bi/expr/validate}: an expression checked in its scope, against the model (or {@code dataset:<name>}) as the caller sees it when one is
     * named, else against the language and the catalogue only.
     */
    public Map<String, Object> validateExpression(Principal p, String expr, com.ash.drishti.server.bi.expr.Scope scope, String model, String alias) {
        if (model == null) {
            return ExpressionCheck.run(expr, scope, com.ash.drishti.server.bi.expr.Fields.NONE, null, Map.of());
        }
        return validateExpression(expr, scope, bind(p, model), alias);
    }

    /**
     * As {@link #validateExpression(Principal, String, com.ash.drishti.server.bi.expr.Scope, String, String)} against an unsaved draft: the YAML of a model
     * ({@code model:}) or of a {@code rachana: 2} dataset (its implicit model) as the designer holds it, its datasets resolved as published, read as the
     * caller (their row rules, masks and hidden fields).
     */
    public Map<String, Object> validateExpressionDraft(Principal p, String expr, com.ash.drishti.server.bi.expr.Scope scope, String yaml, String alias) {
        Found f;
        if (DatasetDefParser.isVersion2(yaml)) {
            DatasetDefParser.Result r = DatasetDefParser.parse(yaml);
            if (!r.ok()) {
                throw new MqException(MqException.Problem.of("DRS-9151", "yaml", "the draft dataset has errors: " + r.firstError()));
            }
            List<DatasetParser.Problem> problems = new ArrayList<>();
            ResolvedDataset d = chain(r.dataset(), new LinkedHashSet<>(), problems);
            if (d == null) {
                throw new MqException(MqException.Problem.of("DRS-9151", "yaml", problems.isEmpty() ? "the draft dataset cannot be resolved" : problems.get(0).message()));
            }
            f = implicitOf(d);
        } else {
            ModelDefParser.Result parsed = ModelDefParser.parse(yaml);
            if (parsed.model() == null) {
                throw new MqException(MqException.Problem.of("DRS-9151", "yaml", "the draft is neither a model nor a rachana: 2 dataset: " + parsed.firstError()));
            }
            f = new Found(view("draft:" + parsed.model().name(), parsed.model(), parsed, yaml), null);
        }
        Map<String, Object> out = validateExpression(expr, scope, bound(p, f), alias);
        out.put("draft", true);
        return out;
    }

    private Map<String, Object> validateExpression(String expr, com.ash.drishti.server.bi.expr.Scope scope, MqService.Bound b, String alias) {
        ModelFields f = ModelFields.of(b, alias, b.context().roles());
        Map<String, Object> out = ExpressionCheck.run(expr, scope, f, b.view().flat(), com.ash.drishti.server.bi.layer.Flattener.measureIds(b.view().measureDefs()));
        out.put("model", MqService.model(b.view()));
        if (f.current() != null && scope != com.ash.drishti.server.bi.expr.Scope.AGGREGATE) {
            out.put("alias", f.current());
        }
        return out;
    }

    /**
     * The lineage of a report, a model or a dataset ({@code dataset:<name>}) as the caller may read it (docs/architecture/BI_DESIGN.md section 7): report to
     * model, model to each dataset by alias, each dataset down its {@code extends} chain to its root (a pack kind and the connector that serves it, a connector
     * query, a Python item, another model, a SQL view). {@code problems} lists a root that cannot be traced ({@code DRS-9110}).
     */
    public Map<String, Object> lineage(Principal p, String ref) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> top = new ArrayList<>();
        String modelRef = ref;
        if (!ref.startsWith(ImplicitModels.QUERY_PREFIX)) {
            BiStore.Item item;
            try {
                item = bi.readableItem(p, ref);
            } catch (RuntimeException e) {
                item = null;                                                    // not an item id: a model reference
            }
            if (item != null && "report".equals(item.kind())) {
                com.ash.drishti.server.bi.report.Report rep = com.ash.drishti.server.bi.report.ReportParser.parse(bi.bodyFor(p, item)).report();
                if (rep == null) {
                    throw new MqException(MqException.Problem.of("DRS-9151", "ref", "the report '" + ref + "' has errors"));
                }
                modelRef = rep.model() != null ? rep.model() : ImplicitModels.QUERY_PREFIX + rep.dataset();
                top.add(Map.of("kind", "report", "name", item.id(), "title", item.title()));
            }
        }
        Found f = find(p, modelRef);
        ModelView v = f.view();
        top.add(Map.of("kind", v.def().implicit() ? "implicit-model" : "model", "name", v.def().name(), "version", v.def().version()));
        List<DatasetParser.Problem> problems = new ArrayList<>();
        Map<String, Object> aliases = new LinkedHashMap<>();
        v.datasets().forEach((alias, r) -> aliases.put(alias, r == null ? List.of() : com.ash.drishti.server.bi.layer.Derivations.lineage(r, packs, problems)));
        out.put("ref", ref);
        out.put("path", top);
        out.put("datasets", aliases);
        out.put("problems", problems.stream().map(x -> Map.of("code", x.code(), "path", x.path(), "message", x.message())).toList());
        return out;
    }

    /** What a designer, an AI or a client needs to write a query on the model, as the caller sees it: masked fields marked, hidden ones absent. */
    public Map<String, Object> describe(Principal p, String ref) {
        return describe(p, ref, null);
    }

    /**
     * As {@link #describe(Principal, String)}; {@code perspective}: only the fields and measures of that perspective of the model (an unknown one is
     * {@code DRS-9153}). Fields and measures hidden from the caller ({@code hidden-for}) are absent; each carries the model's field properties.
     */
    public Map<String, Object> describe(Principal p, String ref, String perspective) {
        return describe(p, ref, find(p, ref), perspective, null);
    }

    /**
     * As {@link #describe(Principal, String)} for a reader of a published consumer (a report): with read on the model, the model; without it, the fields and
     * measures of the report's envelope only (docs/architecture/BI_DESIGN.md section 5.3), so the report opens and its Fields pane shows what it may ask.
     */
    public Map<String, Object> describeVia(Principal p, String ref, String consumerId) {
        try {
            return describe(p, ref, null);
        } catch (MqException e) {
            if (consumerId == null || !"DRS-9151".equals(e.problems().get(0).code())) {
                throw e;
            }
            com.ash.drishti.server.bi.report.Report rep;
            try {
                rep = publishedReport(p, consumerId);
            } catch (DrishtiException x) {
                throw e;
            }
            Found f = rep == null || rep.model() == null ? null : findPublished(ref);
            if (f == null) {
                throw e;
            }
            Map<String, Object> m = describe(p, ref, f, null, Envelope.of(consumerId, rep, f.view()));
            m.put("envelope", true);                                            // what this reader may ask through the report, not the whole model
            return m;
        }
    }

    /** The published text of a report the caller may read, parsed (null when it is no published report). */
    private com.ash.drishti.server.bi.report.Report publishedReport(Principal p, String consumerId) {
        BiStore.Item c = bi.readableItem(p, consumerId);
        if (!"report".equals(c.kind()) || !c.isPublished()) {
            return null;
        }
        return com.ash.drishti.server.bi.report.ReportParser.parse(bi.publishedBody(c)).report();
    }

    private Map<String, Object> describe(Principal p, String ref, Found f, String perspective, Envelope within) {
        ModelView v = f.view();
        QueryService.Caller who = datasets.caller(p, v.flat());
        Set<String> hiddenFor = ObjectSecurity.hidden(v, who.roles());
        ModelDef.Perspective view = null;
        if (perspective != null && !perspective.isBlank()) {
            view = v.def().semantics().perspectives().get(perspective);
            if (view == null) {
                throw new MqException(MqException.Problem.of("DRS-9153", "perspective", "the model has no perspective '" + perspective + "' (it has "
                        + v.def().semantics().perspectives().keySet() + ")"));
            }
        }
        Map<String, Object> m = new LinkedHashMap<>(MqService.model(v));
        m.put("title", v.def().title());
        m.put("description", v.def().description());
        m.put("lifecycle", v.def().lifecycle());
        List<Map<String, Object>> sets = new ArrayList<>();
        for (ModelDef.Use u : v.def().datasets().values()) {
            Dataset.Table t = v.flat().tables().get(u.alias());
            ResolvedDataset r = v.datasets().get(u.alias());
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("alias", u.alias());
            d.put("dataset", r == null ? u.dataset() : r.def().name());
            d.put("version", r == null ? null : r.def().version());
            d.put("role", u.role());
            d.put("mode", r != null && r.def().time() != null ? r.def().time().mode() : t == null ? null : "live".equals(t.mode()) ? "live" : "snapshot");
            List<Map<String, Object>> cols = new ArrayList<>();
            if (t != null) {
                for (Dataset.Column c : t.columns().values()) {
                    String fieldName = u.alias() + "." + c.name();
                    if (hiddenFor.contains(fieldName) || view != null && !view.shows(fieldName) || within != null && !within.uses(fieldName)) {
                        continue;
                    }
                    DatasetDef.Col def = r == null ? null : r.def().columns().get(c.name());
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("field", u.alias() + "." + c.name());
                    cm.put("id", v.fieldId(u.alias() + "." + c.name()));
                    cm.put("type", c.type());
                    cm.put("role", def == null ? null : def.role());
                    cm.put("meaning", def == null ? null : def.meaning());
                    cm.put("references", def == null ? null : def.references());
                    cm.put("unit", def == null || def.unitCurrency() == null ? null : Map.of("currency", def.unitCurrency()));
                    cm.put("format", c.format());
                    cm.put("masked", who.masks().masked(u.alias(), c.name()));
                    cm.put("description", c.description());
                    ModelDef.FieldProps props = v.def().semantics().fields().get(fieldName);
                    if (props != null) {                                      // the model's field properties: every consumer inherits them
                        Map<String, Object> pm = props.toMap();
                        cm.put("display", pm.get("display"));
                        cm.put("folder", pm.get("folder"));
                        cm.put("hidden", pm.get("hidden"));
                        cm.put("sortBy", pm.get("sortBy"));
                        cm.put("category", pm.get("category"));
                        cm.put("summarise", pm.get("summarise"));
                        if (props.format() != null) {
                            cm.put("format", props.format());
                        }
                        if (props.description() != null) {
                            cm.put("description", props.description());
                        }
                    }
                    cols.add(cm);
                }
            }
            d.put("fields", cols);
            sets.add(d);
        }
        m.put("datasets", sets);
        m.put("relationships", v.def().relationships().stream().map(r -> Map.of("id", r.id(), "from", r.fromAlias() + "." + r.fromColumn(), "to", r.toAlias() + "." + r.toColumn(),
                "cardinality", r.cardinality(), "active", r.active())).toList());
        List<Map<String, Object>> measures = new ArrayList<>();
        for (ModelDef.Meas x : v.measureDefs().values()) {
            if (hiddenFor.contains(ObjectSecurity.MEASURE + x.name()) || view != null && !view.measures().contains(x.name()) && !view.measures().contains(x.id())
                    || within != null && !within.uses(x.name())) {
                continue;
            }
            ModelView.MeasureInfo info = v.info(v.flat(), x.name());
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("name", x.name());
            mm.put("id", x.id());
            mm.put("expr", x.expr());
            mm.put("python", x.python());
            mm.put("format", x.format());
            mm.put("additive", info.adds() ? Boolean.TRUE : info.semiAdditive() ? Map.of("over-time", info.additive()) : Boolean.FALSE);
            mm.put("currency", info.currency());
            mm.put("implicit", x.implicit());
            mm.put("masked", maskedMeasure(v, who, x.name()));
            mm.put("description", x.description());
            ModelDef.FieldProps mp = v.def().semantics().measures().get(x.name());
            mm.put("display", mp == null ? null : mp.display());
            mm.put("folder", mp == null ? null : mp.folder());
            mm.put("hidden", mp != null && mp.hidden() != null && mp.hidden());
            measures.add(mm);
        }
        m.put("measures", measures);
        m.put("hierarchies", v.def().hierarchies());
        m.putAll(MqDescribe.calculation(v.def()));                              // wave B: calculation groups, parameters, calendar, FX
        m.put("perspectives", v.def().semantics().perspectives().values().stream().map(x -> Map.of("name", x.name(), "title", x.title(), "description", x.description())).toList());
        if (view != null) {
            m.put("perspective", view.name());
        }
        m.put("contract", Map.of("exposes", v.def().contract().exposes(), "fields", v.def().contract().fields(), "measures", v.def().contract().measures(),
                "deprecated", v.def().contract().deprecated()));
        m.put("ai", v.def().ai());
        if (within != null) {
            m.put("usedBy", List.of());                                         // a reader within a report's envelope is not shown the model's other consumers
            return m;
        }
        try {
            Map<String, Object> used = usedBy(p, ref);
            m.put("usedBy", used.get("consumers"));
            m.put("usedByHidden", used.get("hidden"));
        } catch (RuntimeException e) {
            m.put("usedBy", List.of());
        }
        return m;
    }

    private static boolean maskedMeasure(ModelView v, QueryService.Caller who, String measure) {
        try {
            return new com.ash.drishti.server.bi.engine.SqlCompiler(v.flat(), com.ash.drishti.server.bi.engine.Dialect.DUCKDB).measureMasked(measure, who.masks());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * A {@code rachana: 2} dataset item the caller may read, as stored and as it reads with its {@code extends} chain folded in ({@code resolved}: the base's
     * columns, grain, keys, meanings and every layer's row rules), with Check's problems: what the dataset page and the model designer show for a narrowed dataset.
     */
    public Map<String, Object> layerDataset(Principal p, String id) {
        BiStore.Item i = bi.readableItem(p, id);
        if (!"dataset".equals(i.kind())) {
            throw new DrishtiException(ErrorCode.BI_NOT_FOUND, "'" + id + "' is not a dataset");
        }
        String body = bi.bodyFor(p, i);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id());
        m.put("status", i.status());
        m.put("draft", !i.isPublished());
        m.put("yaml", body);
        List<DatasetParser.Problem> problems = new ArrayList<>();
        String resolved = body;
        if (DatasetDefParser.isVersion2(body)) {
            DatasetDefParser.Result r = DatasetDefParser.parse(body);
            problems.addAll(r.problems());
            if (r.dataset() != null && r.ok() && r.dataset().extendsRef() != null) {
                ResolvedDataset folded = chain(r.dataset(), new LinkedHashSet<>(), problems);
                if (folded != null) {
                    resolved = PackDatasets.resolvedYaml(folded);
                }
            }
        }
        m.put("resolved", resolved);
        m.put("valid", problems.stream().noneMatch(DatasetParser.Problem::error));
        m.put("problems", problems.stream().map(x -> Map.of("level", x.level(), "code", x.code(), "path", x.path(), "message", x.message())).toList());
        return m;
    }

    /** The models the caller may read: model items, and the version-1 datasets the compatibility reader serves as models. */
    public List<Map<String, Object>> list(Principal p) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BiStore.Item i : candidates(p, "model")) {
            try {
                BiStore.Item r = bi.readableItem(p, i.id());
                ModelDef m = ModelDefParser.parse(bi.bodyFor(p, r)).model();
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("id", i.id());
                e.put("name", m == null ? i.title() : m.name());
                e.put("version", m == null ? "" : m.version());
                e.put("title", i.title());
                e.put("status", i.status());
                e.put("draft", !i.isPublished());                                 // the caller's own draft models are listed too
                e.put("owner", i.owner());
                e.put("workspace", i.workspace());
                e.put("kind", "model");
                e.put("implicitFrom", m == null ? null : m.implicitFrom());          // written out from a dataset (Make it a model reuses it)
                out.add(e);
            } catch (DrishtiException e) {
                // not readable by this caller
            }
        }
        List<BiStore.Item> sets = new ArrayList<>(bi.datasetItems(p));
        Set<String> seen = new java.util.HashSet<>(sets.stream().map(BiStore.Item::id).toList());
        bi.draftDatasets(p).stream().filter(i -> seen.add(i.id())).forEach(sets::add);    // the caller's own drafts too (a narrowed dataset before it is published)
        for (BiStore.Item i : sets) {
            String body = bi.bodyFor(p, i);
            boolean v2 = DatasetDefParser.isVersion2(body);
            String name = v2 ? Optional.ofNullable(DatasetDefParser.parse(body).dataset()).map(DatasetDef::name).orElse(i.title())
                    : com.ash.drishti.server.bi.gov.ImpactAnalyzer.declaredName(body);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", i.id());
            e.put("name", name);
            e.put("title", i.title());
            e.put("status", i.status());
            e.put("kind", v2 ? "implicit" : "compat");
            e.put("query", v2 ? ImplicitModels.QUERY_PREFIX + name : name);
            e.put("draft", !i.isPublished());
            e.put("owner", i.owner());
            e.put("workspace", i.workspace());
            out.add(e);
        }
        return out;
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
