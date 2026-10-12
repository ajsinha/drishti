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
import com.ash.drishti.server.bi.layer.CompatReader;
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

    public ModelCatalog(BiService bi, DatasetService datasets, PackDatasets packs) {
        this(bi, datasets, packs, new MqService(MqProperties.defaults()));
    }

    /** @param mq asks the inner queries of datasets whose source is a model */
    public ModelCatalog(BiService bi, DatasetService datasets, PackDatasets packs, MqService mq) {
        this.bi = bi;
        this.datasets = datasets;
        this.packs = packs;
        this.mq = mq;
    }

    public PackDatasets packs() {
        return packs;
    }

    MqService mq() {
        return mq;
    }

    // ---- binding -----------------------------------------------------------------------------------------------------

    /** The model the reference names, bound to the caller: how its requests run as them, and their side of the checks. */
    public MqService.Bound bind(Principal p, String ref) {
        return bound(p, find(p, ref));
    }

    /**
     * The model of a dataset item a consumer already holds (a report's dataset, an extract's, an alert's), bound to the caller: the implicit model of a
     * {@code rachana: 2} dataset, else the compatibility reader's model. Whether the caller may reach the item was the consumer's decision (a published report
     * reads its dataset with its publisher's grants); the caller's own row rules and masks still apply.
     */
    public MqService.Bound bindItem(Principal p, BiStore.Item item) {
        return bound(p, ofItem(p, item, true));
    }

    private MqService.Bound bound(Principal p, Found f) {
        ModelView v = f.view();
        QueryService.Caller who = datasets.caller(p, v.flat());
        MqValidator.Context ctx = new MqValidator.Context(who.masks(), who.roles(), who.attributes(), who.enforce(), datasets.currentDate(), datasets.calendar());
        MqService.Runner run;
        if (v.compat()) {
            BiStore.Item item = f.item();
            run = (ds, q, explain) -> ds == v.flat() ? datasets.queryItem(p, item, q, explain) : datasets.queryWith(p, item, ds, salt(v.flat(), ds), q, explain);
        } else {
            run = (ds, q, explain) -> {
                Set<String> touched = new LinkedHashSet<>();
                q.fields().forEach(x -> touched.add(x.substring(0, Math.max(0, x.indexOf('.')))));
                q.filters().forEach(x -> touched.add(x.field().contains(".") ? x.field().substring(0, x.field().indexOf('.')) : ""));
                q.measures().forEach(m -> touched.addAll(ModelView.tablesOf(ds, m)));
                touched.retainAll(v.unbuilt());
                Set<String> fromModels = ModelSources.aliases(v);
                touched.removeAll(fromModels);
                if (!touched.isEmpty()) {
                    throw new DrishtiException(ErrorCode.DATASET_NOT_BUILT, "the datasets " + touched + " are a connector query or a Python Transform: "
                            + "this server reads pack kinds and other models' answers through models so far (the other sources are wave W1 package S4)");
                }
                ModelSources staged = fromModels.isEmpty() ? null : new ModelSources(this, p, v, q);
                return datasets.queryModel(p, v.id(), v.key(), ds, v.hash() + "|" + salt(v.flat(), ds), q, explain, staged);
            };
        }
        return new MqService.Bound(v, run, ctx);
    }

    /** The generation the model's data is at (its layers and live tables), for a standing query. */
    public long generation(Principal p, ModelView v) {
        return datasets.generation(v.id(), v.key(), v.flat());
    }

    private static String salt(Dataset flat, Dataset ds) {
        if (ds == flat) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        ds.measures().forEach((k, m) -> {
            if (!flat.measures().containsKey(k)) {
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
            if (!r.range().matches(Semver.parse(m.version()))) {
                problem = "'" + r.name() + "' is at version " + m.version() + ", not in " + r.range().text();
                continue;
            }
            if ("retired".equals(m.lifecycle())) {
                problem = "'" + r.name() + "' " + m.version() + " is retired" + (m.replacedBy() == null ? "" : ": use " + m.replacedBy());
                continue;
            }
            if (best == null || Semver.parse(m.version()).compareTo(Semver.parse(best.view().def().version())) > 0) {
                best = new Found(view(i.id(), m, parsed, body), i);
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

    private ModelView view(String id, ModelDef m, ModelDefParser.Result parsed, String body) {
        if (!parsed.ok()) {
            throw new MqException(MqException.Problem.of("DRS-9151", "model", "the model '" + m.name() + "' has errors: " + parsed.firstError()));
        }
        List<DatasetParser.Problem> problems = new ArrayList<>();
        Map<String, ResolvedDataset> resolved = resolveAll(m, problems);
        problems.addAll(ModelChecker.check(m, resolved));
        problems.stream().filter(DatasetParser.Problem::error).findFirst().ifPresent(x -> {
            throw new MqException(MqException.Problem.of("DRS-9151", "model", "the model '" + m.name() + "' has errors: " + x.code() + " " + x.path() + ": " + x.message()));
        });
        ModelSources.cycle(this, "model:" + m.name(), resolved).ifPresent(x -> {
            throw new MqException(MqException.Problem.of("DRS-9109", "model", x));
        });
        Flattener.Flat flat = Flattener.flatten(m, resolved);
        StringBuilder text = new StringBuilder(body);
        resolved.values().forEach(r -> text.append('|').append(r.def().name()).append('@').append(r.def().version()).append(r.layers()).append(r.masked()));
        return new ModelView(id, Layers.key(id), hash(text.toString()), m, flat.dataset(), resolved, flat.unbuilt(), false);
    }

    private Found implicit(Principal p, String name) {
        Optional<ResolvedDataset> d = resolveDataset(name, Semver.Range.ANY, new LinkedHashSet<>(), new ArrayList<>());
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
        ModelSources.cycle(this, "dataset:" + d.def().name(), Map.of(d.def().name(), d)).ifPresent(x -> {
            throw new MqException(MqException.Problem.of("DRS-9109", "model", x));
        });
        ImplicitModels.Implicit im = ImplicitModels.of(d, kind -> packs.ofKind(kind).map(ResolvedDataset::of));
        Flattener.Flat flat = Flattener.flatten(im.model(), im.datasets());
        String id = im.model().id();
        StringBuilder text = new StringBuilder();
        im.datasets().values().forEach(r -> text.append(r.def().name()).append('@').append(r.def().version()).append(r.def().columns().keySet()).append(r.layers()));
        return new Found(new ModelView(id, Layers.key(id), hash(text.toString()), im.model(), flat.dataset(), im.datasets(), flat.unbuilt(), false), null);
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
        Dataset ds;
        try {
            ds = datasets.parsedDataset(p, readable);
        } catch (DrishtiException e) {
            if (consumer) {
                throw e;                                               // a consumer keeps the dataset's own refusal (DRS-9010)
            }
            throw new MqException(MqException.Problem.of("DRS-9151", "model", e.getMessage().replaceFirst("^DRS-\\d+ ", "")));
        }
        ModelDef def = CompatReader.read(ds, readable.id());
        return new Found(new ModelView(readable.id(), Layers.key(readable.id()), "", def, ds, Map.of(), Set.of(), true), readable);
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
                if (d.extendsRef() != null && r.ok()) {
                    chain(d, new LinkedHashSet<>(), problems);
                }
                if (d.source() != null && DatasetDef.Source.MODEL.equals(d.source().type()) && r.ok()) {
                    ModelSources.cycle(this, "dataset:" + d.name(), Map.of(d.name(), ResolvedDataset.of(d))).ifPresent(x -> problems.add(new DatasetParser.Problem("error", "DRS-9109", "source.model", x)));
                }
                m.put("name", d.name());
                m.put("version", d.version());
                m.put("columns", d.columns().keySet());
            }
        } else {
            ModelDefParser.Result r = ModelDefParser.parse(yaml);
            problems.addAll(r.problems());
            m.put("kind", "model");
            if (r.model() != null) {
                Map<String, ResolvedDataset> resolved = resolveAll(r.model(), problems);
                problems.addAll(ModelChecker.check(r.model(), resolved));
                ModelSources.cycle(this, "model:" + r.model().name(), resolved).ifPresent(x -> problems.add(new DatasetParser.Problem("error", "DRS-9109", "datasets", x)));
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
        List<DatasetParser.Problem> problems = new ArrayList<>(r.problems());
        Map<String, ResolvedDataset> resolved = r.model() == null ? Map.of() : resolveAll(r.model(), problems);
        if (r.model() != null) {
            problems.addAll(ModelChecker.check(r.model(), resolved));
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
            String why = was == null ? null : Contracts.models(was, r.model()).refusal();
            if (why != null) {
                throw new DrishtiException(ErrorCode.LAYER_MODEL_BREAKING, "'" + i.title() + "' cannot be published: " + why);
            }
        }
        List<String> notes = new ArrayList<>();
        r.model().security().maySeeAll().forEach((alias, roles) -> notes.add(alias + ": " + roles));
        return notes.isEmpty() ? "" : "version " + r.model().version() + " lets roles see every row of: " + String.join("; ", notes);
    }

    @SuppressWarnings("unchecked")
    private static String firstError(Map<String, Object> check) {
        return ((List<Map<String, Object>>) check.get("problems")).stream().filter(x -> "error".equals(x.get("level"))).findFirst()
                .map(x -> x.get("code") + " " + x.get("path") + ": " + x.get("message")).orElse("");
    }

    /** What a designer, an AI or a client needs to write a query on the model, as the caller sees it: masked fields marked, hidden ones absent. */
    public Map<String, Object> describe(Principal p, String ref) {
        Found f = find(p, ref);
        ModelView v = f.view();
        QueryService.Caller who = datasets.caller(p, v.flat());
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
                List<String> hidden = r == null || r.def().security().hiddenRoles().stream().noneMatch(who.roles()::contains) ? List.of() : r.def().security().hiddenColumns();
                for (Dataset.Column c : t.columns().values()) {
                    if (hidden.contains(c.name())) {
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
            measures.add(mm);
        }
        m.put("measures", measures);
        m.put("hierarchies", v.def().hierarchies());
        m.put("contract", Map.of("exposes", v.def().contract().exposes(), "fields", v.def().contract().fields(), "measures", v.def().contract().measures(),
                "deprecated", v.def().contract().deprecated()));
        m.put("ai", v.def().ai());
        return m;
    }

    private static boolean maskedMeasure(ModelView v, QueryService.Caller who, String measure) {
        try {
            return new com.ash.drishti.server.bi.engine.SqlCompiler(v.flat(), com.ash.drishti.server.bi.engine.Dialect.DUCKDB).measureMasked(measure, who.masks());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The models the caller may read: model items, and the version-1 datasets the compatibility reader serves as models. */
    public List<Map<String, Object>> list(Principal p) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BiStore.Item i : candidates(p, "model")) {
            try {
                BiStore.Item r = bi.readableItem(p, i.id());
                ModelDef m = ModelDefParser.parse(bi.bodyFor(p, r)).model();
                out.add(Map.of("id", i.id(), "name", m == null ? i.title() : m.name(), "version", m == null ? "" : m.version(), "title", i.title(), "status", i.status(),
                        "kind", "model"));
            } catch (DrishtiException e) {
                // not readable by this caller
            }
        }
        for (BiStore.Item i : bi.datasetItems(p)) {
            String body = bi.bodyFor(p, i);
            boolean v2 = DatasetDefParser.isVersion2(body);
            String name = v2 ? Optional.ofNullable(DatasetDefParser.parse(body).dataset()).map(DatasetDef::name).orElse(i.title())
                    : com.ash.drishti.server.bi.gov.ImpactAnalyzer.declaredName(body);
            out.add(Map.of("id", i.id(), "name", name, "title", i.title(), "status", i.status(), "kind", v2 ? "implicit" : "compat", "query",
                    v2 ? ImplicitModels.QUERY_PREFIX + name : name));
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
