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
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.bi.BiGovernance;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.layer.Contracts;
import com.ash.drishti.server.bi.layer.DatasetDef;
import com.ash.drishti.server.bi.layer.DatasetDefParser;
import com.ash.drishti.server.bi.layer.Derivations;
import com.ash.drishti.server.bi.layer.ModelDef;
import com.ash.drishti.server.bi.layer.ModelDefParser;
import com.ash.drishti.server.bi.layer.ResolvedDataset;
import com.ash.drishti.server.bi.report.Report;
import com.ash.drishti.server.bi.report.ReportParser;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lineage and impact of the model layer's items (docs/guides/BI_GOVERNANCE.md, Lineage and Impact), computed from the items as they are now: a model's
 * datasets at the versions it resolves and each one's chain to its root, and its consumers with what each uses; a {@code rachana: 2} dataset's root and the
 * models (and narrowing datasets) that read it; a report on a model, the model at the version it reads and the datasets of the fields it uses. Impact says
 * which consumers use what a change takes away: the names given ({@code remove}), else what the draft drops against the published version. Answers
 * {@code GET /items/{id}/lineage} and {@code /impact} for these items (the version 1 analysis stays for version 1 datasets and reports).
 */
public final class LayerLineage implements BiGovernance.Layered {

    private final ModelCatalog catalog;
    private final BiService bi;

    public LayerLineage(ModelCatalog catalog, BiService bi) {
        this.catalog = catalog;
        this.bi = bi;
    }

    private static boolean isLayer(BiStore.Item i, String body) {
        return "model".equals(i.kind()) || "dataset".equals(i.kind()) && DatasetDefParser.isVersion2(body)
                || "report".equals(i.kind()) && ReportParser.parse(body).report() != null && ReportParser.parse(body).report().model() != null;
    }

    // ---- lineage -----------------------------------------------------------------------------------------------------

    @Override
    public Map<String, Object> lineage(Principal p, BiStore.Item i) {
        String body = bi.bodyFor(p, i);
        if (!isLayer(i, body)) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", ref(i));
        m.put("layer", true);
        if ("model".equals(i.kind())) {
            ModelView v = catalog.find(p, i.id()).view();
            m.put("model", Map.of("name", v.def().name(), "version", v.def().version()));
            m.put("upstream", upstream(v, null));
            downstream(p, i, v, m);
        } else if ("dataset".equals(i.kind())) {
            datasetLineage(p, i, body, m);
        } else {
            Report rep = ReportParser.parse(body).report();
            ModelView v = catalog.find(p, rep.model()).view();
            Envelope e = Envelope.of(i.id(), rep, v);
            m.put("model", Map.of("ref", rep.model(), "name", v.def().name(), "version", v.def().version()));
            m.put("uses", Map.of("fields", List.copyOf(e.fields()), "measures", List.copyOf(e.measures()), "filters", List.copyOf(e.filters())));
            Set<String> aliases = new LinkedHashSet<>();
            e.fields().forEach(f -> aliases.add(f.substring(0, Math.max(0, f.indexOf('.')))));
            e.filters().forEach(f -> aliases.add(f.substring(0, Math.max(0, f.indexOf('.')))));
            aliases.retainAll(v.datasets().keySet());
            m.put("upstream", upstream(v, aliases.isEmpty() ? null : aliases));
        }
        return m;
    }

    /** Each alias of the model (or only {@code only}): the dataset it reads now, its version and role, and its chain to the root. */
    private List<Map<String, Object>> upstream(ModelView v, Set<String> only) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<DatasetParser.Problem> problems = new ArrayList<>();
        v.def().datasets().values().forEach(u -> {
            if (only != null && !only.contains(u.alias())) {
                return;
            }
            ResolvedDataset r = v.datasets().get(u.alias());
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("table", u.alias());
            a.put("dataset", r == null ? u.dataset() : r.def().name());
            a.put("version", r == null ? null : r.def().version());
            a.put("range", u.range().text());
            a.put("role", u.role());
            a.put("chain", r == null ? List.of() : Derivations.lineage(r, catalog.packs(), problems));
            out.add(a);
        });
        return out;
    }

    /** The consumers of a model with, for a report, what it uses of the model (its envelope); {@code hidden} counts those the caller may not open. */
    @SuppressWarnings("unchecked")
    private void downstream(Principal p, BiStore.Item model, ModelView v, Map<String, Object> m) {
        Map<String, Object> used = catalog.usedBy(p, model.id());
        List<Map<String, Object>> down = new ArrayList<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) used.get("consumers")) {
            Map<String, Object> d = new LinkedHashMap<>(c);
            if ("report".equals(c.get("kind"))) {
                Report rep = report(p, String.valueOf(c.get("id")));
                if (rep != null) {
                    Envelope e = Envelope.of(String.valueOf(c.get("id")), rep, v);
                    d.put("uses", Map.of("fields", List.copyOf(e.fields()), "measures", List.copyOf(e.measures())));
                }
            }
            down.add(d);
        }
        m.put("downstream", down);
        m.put("hidden", used.get("hidden"));
    }

    private Report report(Principal p, String id) {
        try {
            BiStore.Item r = bi.readableItem(p, id);
            return ReportParser.parse(r.isPublished() ? bi.publishedBody(r) : bi.bodyFor(p, r)).report();
        } catch (DrishtiException e) {
            return null;
        }
    }

    private void datasetLineage(Principal p, BiStore.Item i, String body, Map<String, Object> m) {
        DatasetDefParser.Result parsed = DatasetDefParser.parse(body);
        DatasetDef d = parsed.dataset();
        if (d == null) {
            m.put("note", "the dataset's YAML does not parse, so its lineage cannot be drawn: " + parsed.firstError());
            return;
        }
        m.put("dataset", Map.of("name", d.name(), "version", d.version()));
        List<DatasetParser.Problem> problems = new ArrayList<>();
        ResolvedDataset r = catalog.chainOf(d, problems);
        m.put("upstream", r == null ? List.of() : List.of(Map.of("table", d.defaultAlias(), "dataset", d.name(), "version", d.version(),
                "chain", Derivations.lineage(r, catalog.packs(), problems))));
        m.put("problems", problems.stream().map(x -> Map.of("code", x.code(), "path", x.path(), "message", x.message())).toList());
        List<Map<String, Object>> down = new ArrayList<>();
        int hidden = 0;
        for (Reading x : readers(p, d.name())) {
            if (!readable(p, x.item())) {
                hidden++;
                continue;
            }
            Map<String, Object> e = ref(x.item());
            e.put("as", x.alias());
            e.put("range", x.range());
            e.put("reads", x.reads());
            down.add(e);
        }
        m.put("downstream", down);
        m.put("hidden", hidden);
    }

    /** A model that reads a dataset (under an alias, at a range), or a dataset that narrows it ({@code extends}); {@code reads}: the version it resolves now. */
    private record Reading(BiStore.Item item, String alias, String range, String reads) {}

    /** The models (published, and the caller's drafts) and the narrowing datasets that read the dataset {@code name}, as their texts are now. */
    private List<Reading> readers(Principal p, String name) {
        List<Reading> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<BiStore.Item> pool = new ArrayList<>(bi.publishedOfKind("model"));
        pool.addAll(bi.draftsOfKind(p, "model"));
        pool.addAll(bi.publishedOfKind("dataset"));
        pool.addAll(bi.draftsOfKind(p, "dataset"));
        for (BiStore.Item it : pool) {
            if (!seen.add(it.id())) {
                continue;
            }
            String text = readable(p, it) ? bi.bodyFor(p, it) : bi.publishedBody(it);
            if ("model".equals(it.kind())) {
                ModelDef md = ModelDefParser.parse(text).model();
                if (md == null) {
                    continue;
                }
                for (ModelDef.Use u : md.datasets().values()) {
                    if (u.dataset().equals(name)) {
                        Map<String, ResolvedDataset> now = catalog.resolveAll(md, new ArrayList<>());
                        ResolvedDataset r = now.get(u.alias());
                        out.add(new Reading(it, u.alias(), u.range().text(), r == null ? "none in range" : r.def().version()));
                    }
                }
            } else if (DatasetDefParser.isVersion2(text)) {
                DatasetDef dd = DatasetDefParser.parse(text).dataset();
                if (dd != null && dd.extendsRef() != null && com.ash.drishti.server.bi.layer.Semver.Ref.parse(dd.extendsRef()) != null
                        && name.equals(com.ash.drishti.server.bi.layer.Semver.Ref.parse(dd.extendsRef()).name())) {
                    out.add(new Reading(it, "extends", com.ash.drishti.server.bi.layer.Semver.Ref.parse(dd.extendsRef()).range().text(), dd.version()));
                }
            }
        }
        return out;
    }

    // ---- impact ------------------------------------------------------------------------------------------------------

    @Override
    public Map<String, Object> impact(Principal p, BiStore.Item i, List<String> remove) {
        String body = bi.bodyFor(p, i);
        if (!"model".equals(i.kind()) && !("dataset".equals(i.kind()) && DatasetDefParser.isVersion2(body))) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("applies", true);
        m.put("layer", true);
        m.put("hypothetical", remove != null);
        String published = i.isPublished() || i.publishedVersion() > 0 ? bi.publishedBody(i) : null;
        List<String> breaking = new ArrayList<>();
        List<String> lost = new ArrayList<>();
        if ("model".equals(i.kind())) {
            if (remove != null) {
                lost.addAll(remove);
            } else if (published != null && !published.isBlank()) {
                ModelDef was = ModelDefParser.parse(published).model();
                ModelDef now = ModelDefParser.parse(body).model();
                if (was != null && now != null) {
                    Contracts.Change c = Contracts.models(was, now, catalog.resolveAll(was, new ArrayList<>()), catalog.resolveAll(now, new ArrayList<>()));
                    breaking.addAll(c.breaking());
                    m.put("versionProblem", c.versionProblem());
                    lost.addAll(lostNames(c.breaking()));
                }
            }
            m.put("model", ModelDefParser.parse(body).model() == null ? null : ModelDefParser.parse(body).model().name());
            m.put("affected", affected(p, i.id(), lost));
        } else {
            DatasetDef now = DatasetDefParser.parse(body).dataset();
            List<String> columns = new ArrayList<>();
            if (remove != null) {
                columns.addAll(remove);
            } else if (published != null && DatasetDefParser.isVersion2(published) && now != null) {
                DatasetDef was = DatasetDefParser.parse(published).dataset();
                if (was != null) {
                    Contracts.Change c = Contracts.datasets(was, now);
                    breaking.addAll(c.breaking());
                    m.put("versionProblem", c.versionProblem());
                    was.columns().keySet().stream().filter(col -> !now.columns().containsKey(col)).forEach(columns::add);
                }
            }
            m.put("dataset", now == null ? null : now.name());
            List<Map<String, Object>> affected = new ArrayList<>();
            if (now != null) {
                for (Reading x : readers(p, now.name())) {
                    if (!"model".equals(x.item().kind()) || !readable(p, x.item())) {
                        continue;
                    }
                    List<String> fields = columns.stream().map(c -> x.alias() + "." + c.substring(c.lastIndexOf('.') + 1)).toList();
                    Map<String, Object> a = ref(x.item());
                    a.put("as", x.alias());
                    List<String> measures = measuresReading(x.item(), p, fields);
                    a.put("measures", measures);                               // the model's measures that read a column: their consumers are affected too
                    List<String> names = new ArrayList<>(fields);
                    names.addAll(measures);
                    a.put("consumers", affected(p, x.item().id(), names));
                    affected.add(a);
                }
            }
            lost.addAll(columns);
            m.put("affected", affected);
        }
        m.put("breaking", breaking);
        m.put("remove", lost);
        return m;
    }

    /** The names a change takes away, from the contract's lines ("the measure 'X' is removed", "the field a.b is removed", "... no longer exposes ..."). */
    static List<String> lostNames(List<String> breaking) {
        List<String> out = new ArrayList<>();
        java.util.regex.Pattern measure = java.util.regex.Pattern.compile("the measure '([^']+)'");
        java.util.regex.Pattern field = java.util.regex.Pattern.compile("the field ([A-Za-z0-9_]+\\.[A-Za-z0-9_]+)");
        for (String b : breaking) {
            java.util.regex.Matcher a = measure.matcher(b);
            java.util.regex.Matcher f = field.matcher(b);
            if (a.find()) {
                out.add(a.group(1));
            } else if (f.find()) {
                out.add(f.group(1));
            }
        }
        return out.stream().distinct().toList();
    }

    /** For each name, the consumers of the model whose envelope uses it (the model's own impact answer, as the caller may see it). */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> affected(Principal p, String model, List<String> names) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String n : names) {
            Map<String, Object> one;
            try {
                one = catalog.impact(p, model, n);
            } catch (DrishtiException e) {
                continue;                                                      // a model that does not resolve now has no consumer analysis
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("target", n);
            e.put("consumers", one.get("consumers"));
            e.put("unknown", ((List<Object>) one.get("unknown")).size());
            out.add(e);
        }
        return out;
    }

    /** The measures of a model whose expression reads one of {@code fields}. */
    private List<String> measuresReading(BiStore.Item model, Principal p, List<String> fields) {
        ModelDef md = ModelDefParser.parse(bi.bodyFor(p, model)).model();
        if (md == null) {
            return List.of();
        }
        return md.measures().values().stream().filter(x -> x.expr() != null && fields.stream().anyMatch(f -> java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(f) + "\\b")
                .matcher(x.expr()).find())).map(ModelDef.Meas::name).toList();
    }

    private boolean readable(Principal p, BiStore.Item x) {
        try {
            bi.readableItem(p, x.id());
            return true;
        } catch (DrishtiException e) {
            return false;
        }
    }

    private static Map<String, Object> ref(BiStore.Item x) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", x.id());
        m.put("title", x.title());
        m.put("kind", x.kind());
        m.put("status", x.status());
        m.put("endorsement", x.endorsement());
        m.put("publishedVersion", x.publishedVersion());
        return m;
    }
}
