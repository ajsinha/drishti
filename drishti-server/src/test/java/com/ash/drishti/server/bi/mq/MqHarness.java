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

import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.UserAttributes;
import com.ash.drishti.server.bi.engine.Layers;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.engine.SqlCompiler;
import com.ash.drishti.server.bi.layer.DatasetDefParser;
import com.ash.drishti.server.bi.layer.Flattener;
import com.ash.drishti.server.bi.layer.ModelChecker;
import com.ash.drishti.server.bi.layer.ModelDef;
import com.ash.drishti.server.bi.layer.ModelDefParser;
import com.ash.drishti.server.bi.layer.ResolvedDataset;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Binds models to readers over an engine without the server: what the catalogue and the dataset service do in the server (the caller's masks as
 * {@code drishti.security} would give them: a column listed as masked is {@code •••} without the raw power), in a few lines for the tests.
 */
final class MqHarness {

    record Reader(String user, boolean raw, String desk, List<String> roles) {}

    static final Reader RITA = new Reader("rita", true, "", List.of("risk"));
    static final Reader ANN = new Reader("ann", false, "", List.of("analyst"));

    static Reader deskHead(String desk) {
        return new Reader("hana", true, desk, List.of("desk-head"));
    }

    private final QueryService service;
    private final LocalDate today;

    MqHarness(QueryService service, LocalDate today) {
        this.service = service;
        this.today = today;
    }

    /** A model over {@code rachana: 2} datasets given by their YAML, checked (no error may be left). */
    static ModelView model(String modelYaml, String... datasetYamls) {
        ModelDefParser.Result r = ModelDefParser.parse(modelYaml);
        if (!r.ok()) {
            throw new IllegalArgumentException(r.problems().toString());
        }
        Map<String, ResolvedDataset> byName = new LinkedHashMap<>();
        for (String y : datasetYamls) {
            DatasetDefParser.Result d = DatasetDefParser.parse(y);
            if (!d.ok()) {
                throw new IllegalArgumentException(d.problems().toString());
            }
            byName.put(d.dataset().name(), ResolvedDataset.of(d.dataset()));
        }
        Map<String, ResolvedDataset> resolved = new LinkedHashMap<>();
        for (ModelDef.Use u : r.model().datasets().values()) {
            resolved.put(u.alias(), byName.get(u.dataset()));
        }
        var problems = ModelChecker.check(r.model(), resolved).stream().filter(com.ash.drishti.server.bi.dataset.DatasetParser.Problem::error).toList();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(problems.toString());
        }
        Flattener.Flat flat = Flattener.flatten(r.model(), resolved);
        return new ModelView("m:" + r.model().name(), Layers.key("m:" + r.model().name()), "h1", r.model(), flat.dataset(), resolved, flat.unbuilt(), false);
    }

    /**
     * As {@link #model(String, String...)}, with {@code extends} chains folded (a base is one of the given datasets, by name) and the pack kinds a
     * {@code related()} column reads.
     */
    static ModelView model(String modelYaml, java.util.function.Function<String, java.util.Optional<com.ash.drishti.server.bi.layer.DatasetDef>> kinds, String... datasetYamls) {
        ModelDefParser.Result r = ModelDefParser.parse(modelYaml);
        if (!r.ok()) {
            throw new IllegalArgumentException(r.problems().toString());
        }
        Map<String, com.ash.drishti.server.bi.layer.DatasetDef> byName = new LinkedHashMap<>();
        for (String y : datasetYamls) {
            DatasetDefParser.Result d = DatasetDefParser.parse(y);
            if (!d.ok()) {
                throw new IllegalArgumentException(d.problems().toString());
            }
            byName.put(d.dataset().name(), d.dataset());
        }
        Map<String, ResolvedDataset> resolved = new LinkedHashMap<>();
        java.util.List<com.ash.drishti.server.bi.dataset.DatasetParser.Problem> problems = new java.util.ArrayList<>();
        for (ModelDef.Use u : r.model().datasets().values()) {
            resolved.put(u.alias(), fold(byName.get(u.dataset()), byName, kinds, problems));
        }
        problems.addAll(ModelChecker.check(r.model(), resolved, kinds).stream().filter(com.ash.drishti.server.bi.dataset.DatasetParser.Problem::error).toList());
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(problems.toString());
        }
        Flattener.Flat flat = Flattener.flatten(r.model(), resolved, kinds);
        return new ModelView("m:" + r.model().name(), Layers.key("m:" + r.model().name()), "h1", r.model(), flat.dataset(), resolved, flat.unbuilt(), false);
    }

    private static ResolvedDataset fold(com.ash.drishti.server.bi.layer.DatasetDef d, Map<String, com.ash.drishti.server.bi.layer.DatasetDef> byName,
            java.util.function.Function<String, java.util.Optional<com.ash.drishti.server.bi.layer.DatasetDef>> kinds,
            java.util.List<com.ash.drishti.server.bi.dataset.DatasetParser.Problem> problems) {
        if (d.extendsRef() == null) {
            return ResolvedDataset.of(d);
        }
        String base = d.extendsRef().contains("@") ? d.extendsRef().substring(0, d.extendsRef().indexOf('@')) : d.extendsRef();
        com.ash.drishti.server.bi.layer.DatasetDef b = byName.get(base);
        problems.addAll(com.ash.drishti.server.bi.layer.Derivations.check(d, b, kinds));
        return ResolvedDataset.extend(d, fold(b, byName, kinds, problems));
    }

    static SqlCompiler.Masks masks(Dataset ds, boolean raw) {
        return (t, c) -> !raw && (ds.security().masked().contains(c) || ds.security().masked().contains(t + "." + c));
    }

    QueryService.Caller caller(Dataset ds, Reader who) {
        return new QueryService.Caller(who.user(), Set.copyOf(who.roles()), true, UserAttributes.of(who.user(), who.roles(), who.desk(), null), masks(ds, who.raw()));
    }

    /** The model bound to a reader: requests run through the engine as them, against the given loaded key (old and new paths share layers when they share it). */
    MqService.Bound bind(ModelView v, Reader who, String key) {
        QueryService.Caller c = caller(v.flat(), who);
        MqValidator.Context ctx = new MqValidator.Context(c.masks(), c.roles(), c.attributes(), true, today, BusinessCalendar.of("WEEKENDS"));
        return new MqService.Bound(v, (ds, q, explain) -> service.query(new QueryService.Loaded(key, Layers.key(key), ds, "h|" + ds.measures().keySet()), q, caller(ds, who), explain), ctx);
    }
}
