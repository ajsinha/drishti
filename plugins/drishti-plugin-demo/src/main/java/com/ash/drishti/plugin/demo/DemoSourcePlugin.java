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
package com.ash.drishti.plugin.demo;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.HitIndex;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves the reference entities of the Drishti mockups from classpath fixtures ({@code demo/<kind>/<id>.json},
 * listed in {@code demo/catalog.json}). Each fixture's {@code _meta} block becomes the provenance, so views
 * show the source systems of the mockups ({@code aero-risk}, {@code aero-fx}, {@code eod-futures}).
 */
public final class DemoSourcePlugin implements SourcePlugin {

    static final String NAME = "demo";
    private static final String ROOT = "demo/";

    private final Map<EntityRef, EntityDocument> documents = new ConcurrentHashMap<>();
    private final HitIndex index = new HitIndex();

    @Override
    public PluginManifest manifest() {
        return new PluginManifest(NAME, "1.0", Set.of(), new SourceCapabilities(false, true, true));
    }

    @Override
    public void start(SourceContext context) throws IOException {
        DataNode catalog = read(context, ROOT + "catalog.json");
        for (int i = 0; i < catalog.size(); i++) {
            DataNode e = catalog.get(i);
            EntityRef ref = EntityRef.of(e.get("kind").asText(), e.get("id").asText());
            DataNode raw = read(context, ROOT + ref.kind() + "/" + ref.id() + ".json");
            documents.put(ref, toDocument(ref, raw));
            index.add(new EntityHit(ref, e.get("title").asText(), e.get("subtitle").asText()));
        }
    }

    private static DataNode read(SourceContext context, String resource) throws IOException {
        try (InputStream in = DemoSourcePlugin.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing demo resource " + resource);
            }
            return context.parseJson(in);
        }
    }

    static EntityDocument toDocument(EntityRef ref, DataNode raw) {
        DataNode meta = raw.get("_meta");
        Map<String, DataNode> fields = new LinkedHashMap<>(((DataNode.Obj) raw).fields());
        fields.remove("_meta");
        Provenance p = new Provenance(meta.get("source").asText(), (long) meta.get("generation").asDouble(),
                Instant.now(), meta.get("live").asBoolean());
        return new EntityDocument(ref, new DataNode.Obj(fields), p);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) {
        EntityDocument d = documents.get(ref);
        return d == null ? Optional.empty()
                : Optional.of(new EntityDocument(ref, d.data(), new Provenance(d.provenance().source(),
                        d.provenance().generation(), Instant.now(), d.provenance().live())));
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind) {
        List<EntityRef> out = new ArrayList<>();
        documents.forEach((ref, doc) -> {
            if (ref.kind().equals(kind) && references(doc.data(), target.id())) {
                out.add(ref);
            }
        });
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    private static boolean references(DataNode node, String id) {
        if (node instanceof DataNode.Obj o) {
            for (DataNode v : o.fields().values()) {
                if (v instanceof DataNode.Val val && id.equals(val.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return index.search(kind, text, limit);
    }

    int size() {
        return documents.size();
    }
}
