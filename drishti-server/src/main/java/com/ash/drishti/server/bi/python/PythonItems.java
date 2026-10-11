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
package com.ash.drishti.server.bi.python;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.bi.BiAccess;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.bi.report.BiReportProperties;
import com.ash.drishti.server.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Python items for the console's runner (RUPAKA.md section 20.8): finds an item by catalogue id, shipped file name or title, as the caller may read it, and
 * answers its parsed manifest and source. The server never runs Python: it stores, versions, reviews and publishes the item like any BI item (publishing
 * checks the manifest), and tells the console what to run and on which columns.
 */
public final class PythonItems {

    private final BiService bi;
    private final BiReportProperties props;

    public PythonItems(BiService bi, BiReportProperties props) {
        this.bi = bi;
        this.props = props;
        bi.pythonCheck(body -> parse(body).firstError());
    }

    PythonManifest.Result parse(String body) {
        return PythonManifest.parse(body, props.pythonPackages(), props.pythonMaxSeconds(), props.pythonMaxMemoryMb());
    }

    /** Checks a manifest (the item page's Check button and the designer): every problem found. */
    public Map<String, Object> validate(Principal p, String yaml) {
        bi.requirePower(p, BiAccess.BI);
        PythonManifest.Result r = parse(yaml);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", r.ok());
        out.put("problems", r.problems().stream().map(PythonManifest.Problem::toMap).toList());
        out.put("manifest", r.manifest() == null ? null : r.manifest().toMap());
        out.put("packages", props.pythonPackages());
        return out;
    }

    /**
     * The item {@code ref} as the caller reads it: the published version, or the working draft for those who edit the item. A viewer of a published report runs
     * what was published, whoever they are in the item's workspace ({@code viaReport}: the report is what names it; asked directly, the caller must be able to read it).
     */
    public Map<String, Object> describe(Principal p, String ref, boolean viaReport) {
        bi.requirePower(p, BiAccess.BI);
        BiStore.Item item = find(p, ref);
        if (!viaReport && !bi.mayRead(p, item)) {
            throw new DrishtiException(ErrorCode.BI_NOT_FOUND, "no item '" + ref + "'");
        }
        String body = bi.bodyFor(p, item);
        PythonManifest.Result r = parse(body);
        if (!r.ok()) {
            throw new DrishtiException(ErrorCode.PYTHON_INVALID, "Python item '" + item.title() + "' is not valid: " + r.firstError());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", item.id());
        out.put("title", item.title());
        out.put("version", item.publishedVersion());
        out.put("status", item.status());
        out.put("draft", !body.equals(bi.publishedBody(item)));
        out.put("manifest", r.manifest().toMap());
        return out;
    }

    private BiStore.Item find(Principal p, String ref) {
        if (ref == null || ref.isBlank()) {
            throw new DrishtiException(ErrorCode.PYTHON_INVALID, "no Python item named");
        }
        List<BiStore.Item> pool = bi.allPythons();
        for (BiStore.Item i : pool) {
            if (i.id().equals(ref) || i.id().equals("file:python:" + ref)) {
                return i;
            }
        }
        for (BiStore.Item i : pool) {
            if (i.title().equalsIgnoreCase(ref)) {
                return i;
            }
        }
        BiStore.Item mine = bi.readableItem(p, ref);
        if (!"python".equals(mine.kind())) {
            throw new DrishtiException(ErrorCode.PYTHON_INVALID, "'" + ref + "' is a " + mine.kind() + ", not a Python item");
        }
        return mine;
    }
}
