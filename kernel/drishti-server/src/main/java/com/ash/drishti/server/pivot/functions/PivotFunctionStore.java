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
package com.ash.drishti.server.pivot.functions;

import com.ash.drishti.bicompute.custom.FunctionDef;
import com.ash.drishti.bicompute.custom.FunctionStore;
import com.ash.drishti.identity.content.ContentDocs;
import com.ash.drishti.identity.content.ContentStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom pivot functions in the content store ({@code pivot/functions/<owner>/<name>.json}, every version in one document), so a function
 * follows its author across a cluster and its history is kept (BI_GUIDE.md, custom functions).
 */
public final class PivotFunctionStore implements FunctionStore {

    private static final String PREFIX = "pivot/functions/";
    private final ContentDocs docs;

    public PivotFunctionStore(ContentStore store) {
        this.docs = new ContentDocs(store);
    }

    private static String key(String owner, String name) {
        return PREFIX + ContentDocs.name(owner) + "/" + ContentDocs.name(name) + ".json";
    }

    @SuppressWarnings("unchecked")
    private static List<FunctionDef> read(Map<String, Object> doc) {
        List<FunctionDef> out = new ArrayList<>();
        if (doc != null && doc.get("versions") instanceof List<?> l) {
            for (Object o : l) {
                out.add(FunctionDef.fromMap((Map<String, Object>) o));
            }
        }
        out.sort(Comparator.comparingInt(FunctionDef::version));
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<FunctionDef> versions(String owner, String name) {
        return docs.value(key(owner, name), Map.class).map(m -> read((Map<String, Object>) m)).orElse(List.of());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void put(FunctionDef def) {
        docs.update(key(def.owner(), def.name()), Map.class, old -> {
            List<FunctionDef> vs = new ArrayList<>(read((Map<String, Object>) old));
            vs.removeIf(d -> d.version() == def.version());
            vs.add(def);
            vs.sort(Comparator.comparingInt(FunctionDef::version));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", def.id());
            m.put("versions", vs.stream().map(FunctionDef::toMap).toList());
            return m;
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<FunctionDef> latest() {
        List<FunctionDef> out = new ArrayList<>();
        for (String k : docs.store().list(PREFIX)) {
            docs.value(k, Map.class).map(m -> read((Map<String, Object>) m)).filter(l -> !l.isEmpty()).ifPresent(l -> out.add(l.get(l.size() - 1)));
        }
        out.sort(Comparator.comparing(FunctionDef::id));
        return out;
    }
}
