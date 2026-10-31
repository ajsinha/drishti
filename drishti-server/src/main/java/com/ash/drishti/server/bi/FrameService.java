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
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.engine.Frames;
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
 * The Python Transform of a {@code rachana: 2} Python dataset (docs/architecture/BI_DESIGN.md › The model layer: the specification of wave W1, section 1.2 C): its inputs, model queries already asked as
 * the reader, are handed to the Transform in the console's sandbox, and the frame it returns is kept for that reader until the inputs change. (The 2.1
 * composed and derived datasets this service once joined and ran were removed in 2.2: BI_DESIGN.md › The model layer: the specification of wave W1, section 8; a {@code rachana: 1} text with
 * {@code from:} or {@code derived:} parses with an error and never reaches it.)
 */
public final class FrameService implements DatasetRefresher.FramedHooks {

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

    /** Wired once at start. */
    public void attach(DatasetService datasets) {
        this.datasets = datasets;
    }

    public TransformService transforms() {
        return transforms;
    }

    /** No dataset is built from frames since 2.2 (composed and derived datasets were removed): always null. */
    public Frames framesFor(Principal p, QueryService.Loaded ds, QueryRequest outer) {
        return null;
    }

    /**
     * The rows of a {@code rachana: 2} dataset whose source is a Python Transform (docs/architecture/BI_DESIGN.md › The model layer: the specification of wave W1, section 1.2 C): its inputs are model
     * queries already asked as the reader, handed to the Transform in the order the dataset names them (columns by their last name part), and its result is
     * kept per reader and inputs as a derived dataset's is.
     *
     * @param fingerprint what the inputs are now (their models' versions and data generations)
     */
    public QueryService.Result transformOf(Principal p, String datasetKey, String dataset, String python, Map<String, QueryService.Result> inputs, Map<String, Object> params,
            Map<String, String> declared, String fingerprint) {
        PythonItems.TransformItem t = pythons.transform(python);
        if (t.manifest().inputs().size() != inputs.size()) {
            throw new DrishtiException(ErrorCode.TRANSFORM_FAILED, "the Transform '" + t.title() + "' reads " + t.manifest().inputs().size() + " input(s) (" + t.manifest().inputs()
                    + ") but dataset '" + dataset + "' gives it " + inputs.size() + " (" + inputs.keySet() + ")");
        }
        Map<String, Object> all = new LinkedHashMap<>(t.manifest().params());
        all.putAll(params);
        String key = readerKey(p) + "|" + datasetKey + "|" + fingerprint + "|" + t.id() + "@" + t.version() + "#" + hash(t.manifest().source() + t.manifest().params() + params);
        TransformService.Output out = transforms.ensure(datasetKey, dataset, key, declared, () -> {
            List<byte[]> frames = new ArrayList<>();
            for (QueryService.Result r : inputs.values()) {
                if (r.rows() > props.maxInputRows()) {
                    throw new DrishtiException(ErrorCode.PYTHON_LIMIT, "an input of '" + dataset + "' has more than " + props.maxInputRows() + " rows (drishti.bi.transform.max-input-rows)");
                }
                List<ArrowIpcWriter.Column> cols = new ArrayList<>();
                for (ArrowIpcWriter.Column c : r.columns()) {
                    cols.add(new ArrowIpcWriter.Column(c.name().substring(c.name().lastIndexOf('.') + 1), c.kind(), c.values()));
                }
                frames.add(ArrowIpcWriter.write(cols, r.rows()));
            }
            return new TransformService.Prepared(t.manifest().toMap(), frames, all);
        });
        return queries.readParquet(out.file(), queries.properties().maxRows());
    }

    @Override
    public java.util.Set<String> kinds(Dataset d) {
        return java.util.Set.of();                                             // nothing is composed or derived since 2.2
    }

    @Override
    public void refreshed(QueryService.Loaded ds) {
        transforms.invalidate(ds.key());
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
