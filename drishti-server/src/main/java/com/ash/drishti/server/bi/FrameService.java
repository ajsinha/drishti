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
import com.ash.drishti.server.bi.dataset.Composition;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.engine.FrameSql;
import com.ash.drishti.server.bi.engine.Frames;
import com.ash.drishti.server.bi.engine.Layers;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.python.PythonItems;
import com.ash.drishti.server.bi.transform.TransformProperties;
import com.ash.drishti.server.bi.transform.TransformService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The rows of composed and derived datasets (RUPAKA.md section 20.10). A composed dataset asks each of its sources the question it names, <em>as the reader</em>:
 * the source's row rules, masks and entitlements apply inside that query, so by the time the answers are joined everything the reader may not see is already
 * gone. A derived dataset hands the same kind of answers to its Python Transform, in the console's sandbox, and keeps the frame it returns for that reader until
 * the inputs change. A join on a column that is masked for the reader is refused (it would match every row to every row).
 */
public final class FrameService {

    /** How deep a source of a source is followed when the inputs' version is worked out. */
    private static final int DEPTH = 6;

    private final BiService bi;
    private final QueryService queries;
    private final PythonItems pythons;
    private final TransformService transforms;
    private final TransformProperties props;
    private final Entitlements entitlements;
    private volatile DatasetService datasets;

    public FrameService(BiService bi, QueryService queries, PythonItems pythons, TransformService transforms, TransformProperties props, Entitlements entitlements) {
        this.bi = bi;
        this.queries = queries;
        this.pythons = pythons;
        this.transforms = transforms;
        this.props = props;
        this.entitlements = entitlements;
    }

    /** Wired once at start: how a source is queried as the reader. */
    public void attach(DatasetService datasets) {
        this.datasets = datasets;
    }

    public TransformService transforms() {
        return transforms;
    }

    /**
     * The frames of a composed or derived dataset for this reader and this outer query (whose dates the sources are asked for).
     *
     * @return null for an ordinary dataset
     */
    public Frames framesFor(Principal p, QueryService.Loaded ds, QueryRequest outer) {
        Dataset d = ds.dataset();
        if (d.composed()) {
            return new Frames() {
                @Override
                public String fingerprint() {
                    return "c:" + versionOf(d, 0);
                }

                @Override
                public Rows load() {
                    return composed(p, d, outer);
                }
            };
        }
        if (d.derivedFrame()) {
            return new Frames() {
                @Override
                public String fingerprint() {
                    return "d:" + versionOf(d, 0) + ":" + transformVersion(d);
                }

                @Override
                public Rows load() {
                    return derived(p, ds, outer);
                }
            };
        }
        return null;
    }

    // ---- the inputs' version -----------------------------------------------------------------------------------------

    /** What the sources are right now: each one's catalogue id, a hash of its text and the generation of its layers, and theirs in turn. Cheap; part of every cache key. */
    String versionOf(Dataset d, int depth) {
        StringBuilder b = new StringBuilder();
        for (Dataset.Input in : Composition.sources(d)) {
            BiStore.Item item = bi.datasetNamed(in.dataset()).orElse(null);
            b.append(in.alias()).append('=');
            if (item == null) {
                b.append("missing;");
                continue;
            }
            String body = bi.sourceBody(item);
            b.append(item.id()).append('@').append(hash(body)).append('g').append(queries.layers().generation(Layers.key(item.id())));
            if (depth < DEPTH && Composition.dependsOnOthers(body)) {
                Dataset sub = DatasetParser.parse(body).dataset();
                if (sub != null) {
                    b.append('[').append(versionOf(sub, depth + 1)).append(']');
                }
            }
            b.append(';');
        }
        return b.toString();
    }

    private String transformVersion(Dataset d) {
        try {
            PythonItems.TransformItem t = pythons.transform(d.derived().transform());
            return t.id() + "@" + t.version() + "#" + hash(t.manifest().source() + t.manifest().params() + d.derived().params());
        } catch (DrishtiException e) {
            return "unresolved";
        }
    }

    // ---- asking the sources as the reader ----------------------------------------------------------------------------

    private Map<String, QueryService.Result> ask(Principal p, Map<String, Dataset.Input> inputs, QueryRequest outer, Integer limit, List<String> warnings, List<String> dates) {
        Map<String, QueryService.Result> answers = new LinkedHashMap<>();
        for (Dataset.Input in : inputs.values()) {
            BiStore.Item source = bi.datasetNamed(in.dataset()).orElseThrow(() -> new DrishtiException(ErrorCode.DATASET_COMPOSE,
                    "the source dataset '" + in.dataset() + "' of '" + in.alias() + "' no longer exists"));
            QueryService.Result r = datasets.querySource(p, source, FrameSql.requestOf(in, List.of(), outer.from(), outer.to(), limit));
            for (String w : r.meta().warnings()) {
                warnings.add(in.alias() + ": " + w);
            }
            for (String date : r.meta().dates()) {
                if (!dates.contains(date)) {
                    dates.add(date);
                }
            }
            answers.put(in.alias(), r);
        }
        java.util.Collections.sort(dates);
        return answers;
    }

    private Frames.Rows composed(Principal p, Dataset d, QueryRequest outer) {
        Dataset.Compose c = d.compose();
        List<String> warnings = new ArrayList<>();
        List<String> dates = new ArrayList<>();
        int max = props.composeSourceMaxRows();
        Map<String, QueryService.Result> answers = ask(p, c.from(), outer, max + 1, warnings, dates);
        for (Map.Entry<String, QueryService.Result> e : answers.entrySet()) {
            if (e.getValue().rows() > max) {
                throw new DrishtiException(ErrorCode.QUERY_ROWS, "source '" + e.getKey() + "' of '" + d.name() + "' answers more than " + max
                        + " rows (drishti.bi.transform.compose-source-max-rows): group it by less, or filter it in `from`");
            }
        }
        for (Dataset.Join j : c.joins()) {
            for (String on : j.on()) {
                String[] sides = on.contains("=") ? on.split("=", 2) : new String[] {on, on};
                refuseMasked(d, j.left(), sides[0].trim(), c.from().get(j.left()), answers.get(j.left()));
                refuseMasked(d, j.right(), sides[1].trim(), c.from().get(j.right()), answers.get(j.right()));
            }
        }
        return Frames.Rows.ofAnswers(answers, warnings.stream().distinct().toList(), dates);
    }

    /** A masked join key holds one constant for every row: joining on it would match every row to every row, so it is refused. */
    private static void refuseMasked(Dataset d, String alias, String key, Dataset.Input in, QueryService.Result answer) {
        int at = FrameSql.frameNames(in).indexOf(key);
        if (at >= 0 && at < answer.columns().size() && answer.meta().masked().contains(answer.columns().get(at).name())) {
            throw new DrishtiException(ErrorCode.QUERY_MASKED, "the join key '" + key + "' (" + answer.columns().get(at).name() + " of source '" + alias + "' in '" + d.name()
                    + "') is masked for you: a join on a masked column would match every row to every row, so it is refused");
        }
    }

    // ---- derived: the Transform --------------------------------------------------------------------------------------

    private Frames.Rows derived(Principal p, QueryService.Loaded ds, QueryRequest outer) {
        Dataset d = ds.dataset();
        Dataset.Derived dv = d.derived();
        PythonItems.TransformItem t = pythons.transform(dv.transform());
        List<String> listed = t.manifest().inputs();
        if (listed.size() != dv.inputs().size()) {
            throw new DrishtiException(ErrorCode.TRANSFORM_FAILED, "the Transform '" + t.title() + "' reads " + listed.size() + " dataset(s) (" + listed + ") but dataset '" + d.name() + "' gives it "
                    + dv.inputs().size());
        }
        int at = 0;
        for (Dataset.Input in : dv.inputs().values()) {
            BiStore.Item given = bi.datasetNamed(in.dataset()).orElse(null);
            BiStore.Item wanted = bi.datasetNamed(listed.get(at++)).orElse(null);
            if (given != null && wanted != null && !given.id().equals(wanted.id())) {
                throw new DrishtiException(ErrorCode.TRANSFORM_FAILED, "input " + at + " of '" + d.name() + "' is dataset '" + in.dataset() + "' but the Transform '" + t.title() + "' expects '" + listed.get(at - 1) + "'");
            }
        }
        Map<String, Object> params = new LinkedHashMap<>(t.manifest().params());
        params.putAll(dv.params());
        String key = readerKey(p) + "|" + ds.key() + "|" + ds.hash() + "|" + versionOf(d, 0) + "|" + transformVersion(d) + "|" + outer.from() + "|" + outer.to();
        Map<String, String> declared = new LinkedHashMap<>();
        d.tables().get(Dataset.RESULT).columns().values().stream().filter(c -> !Composition.LEARNED.equals(c.description())).forEach(c -> declared.put(c.name(), c.type()));
        List<String> warnings = new ArrayList<>();
        TransformService.Output out = transforms.ensure(ds.key(), d.name(), key, declared, () -> {
            List<String> dates = new ArrayList<>();
            int max = props.maxInputRows();
            Map<String, QueryService.Result> answers = ask(p, dv.inputs(), outer, max + 1, warnings, dates);
            List<byte[]> frames = new ArrayList<>();
            for (Map.Entry<String, QueryService.Result> e : answers.entrySet()) {
                QueryService.Result r = e.getValue();
                if (r.rows() > max) {
                    throw new DrishtiException(ErrorCode.PYTHON_LIMIT, "input '" + e.getKey() + "' of '" + d.name() + "' has more than " + max + " rows (drishti.bi.transform.max-input-rows): group it or filter it");
                }
                List<String> names = FrameSql.frameNames(dv.inputs().get(e.getKey()));
                List<ArrowIpcWriter.Column> cols = new ArrayList<>();
                for (int i = 0; i < r.columns().size(); i++) {
                    cols.add(new ArrowIpcWriter.Column(names.get(i), r.columns().get(i).kind(), r.columns().get(i).values()));
                }
                frames.add(ArrowIpcWriter.write(cols, r.rows()));
            }
            return new TransformService.Prepared(t.manifest().toMap(), frames, params);
        });
        return Frames.Rows.ofParquet(out.file(), warnings.stream().distinct().toList());
    }

    /** Who the kept result is for: the user and what decides their rows and masks (roles, the raw power). */
    private String readerKey(Principal p) {
        return p.user() + "|" + new TreeSet<>(p.roles()) + "|raw=" + entitlements.raw(p);
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.valueOf(text).getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
