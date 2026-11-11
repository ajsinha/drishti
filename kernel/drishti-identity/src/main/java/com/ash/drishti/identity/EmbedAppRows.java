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
package com.ash.drishti.identity;

import com.ash.drishti.identity.content.ContentDocs;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.identity.db.EmbedAppEntity;
import com.ash.drishti.identity.db.IdentityRepositories;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where embed applications ({@link EmbedAppStore}) are kept: the identity database ({@code drishti_embed_app}, {@code drishti.embed.store=jpa},
 * one node) or the content store ({@code content}, the default): {@code admin/embed-apps/<id>.json} (DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 9).
 */
public interface EmbedAppRows {

    List<EmbedAppEntity> findAll();

    Optional<EmbedAppEntity> findById(String id);

    void save(EmbedAppEntity e);

    boolean existsById(String id);

    void deleteById(String id);

    static EmbedAppRows of(IdentityRepositories.EmbedApps apps) {
        return new EmbedAppRows() {
            @Override
            public List<EmbedAppEntity> findAll() {
                return apps.findAll();
            }

            @Override
            public Optional<EmbedAppEntity> findById(String id) {
                return apps.findById(id);
            }

            @Override
            public void save(EmbedAppEntity e) {
                apps.save(e);
            }

            @Override
            public boolean existsById(String id) {
                return apps.existsById(id);
            }

            @Override
            public void deleteById(String id) {
                apps.deleteById(id);
            }
        };
    }

    /**
     * The content store. A save is a compare-and-set against the version this thread last read of that application (none: it must be
     * new), so two nodes editing one application give one success and one {@code 409 DRS-5057}.
     */
    final class Content implements EmbedAppRows {

        static final String ROOT = "admin/embed-apps/";
        private final ContentStore store;
        private final ContentDocs docs;
        private final ThreadLocal<Map<String, String>> seen = ThreadLocal.withInitial(HashMap::new);

        public Content(ContentStore store) {
            this.store = store;
            this.docs = new ContentDocs(store);
        }

        private static String key(String id) {
            return ROOT + ContentDocs.name(id) + ".json";
        }

        @Override
        public List<EmbedAppEntity> findAll() {
            List<EmbedAppEntity> out = new ArrayList<>();
            for (String k : store.list(ROOT)) {
                docs.read(k, EmbedAppEntity.class).ifPresent(d -> {
                    seen.get().put(d.value().id, d.version());
                    out.add(d.value());
                });
            }
            return out;
        }

        @Override
        public Optional<EmbedAppEntity> findById(String id) {
            Optional<ContentDocs.Doc<EmbedAppEntity>> d = docs.read(key(id), EmbedAppEntity.class);
            if (d.isPresent()) {
                seen.get().put(id, d.get().version());
            } else {
                seen.get().remove(id);
            }
            return d.map(ContentDocs.Doc::value);
        }

        @Override
        public void save(EmbedAppEntity e) {
            String v = store.put(key(e.id), ContentDocs.encode(e), seen.get().get(e.id));
            seen.get().put(e.id, v);
        }

        @Override
        public boolean existsById(String id) {
            return store.get(key(id)).isPresent();
        }

        @Override
        public void deleteById(String id) {
            store.deleteLatest(key(id));
            seen.get().remove(id);
        }

        /** The one-time import: each row of the table not yet in the store (an application deleted since stays deleted: the marker). */
        public int importFrom(IdentityRepositories.EmbedApps apps) {
            String marker = "system/imports/embed-apps-jpa.json";
            if (store.get(marker).isPresent()) {
                return 0;
            }
            int n = 0;
            for (EmbedAppEntity e : apps.findAll()) {
                if (docs.create(key(e.id), e)) {
                    n++;
                }
            }
            docs.create(marker, Map.of("imported", n, "at", System.currentTimeMillis()));
            return n;
        }
    }
}
