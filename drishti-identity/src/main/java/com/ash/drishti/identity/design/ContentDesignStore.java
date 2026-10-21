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
package com.ash.drishti.identity.design;

import com.ash.drishti.identity.content.ContentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Designs in the content store (docs/architecture/CONTENT_STORE.md section 5), so every node of a cluster reaches them:
 * {@code designs/<escaped owner>/<id>/design.json} and {@code designs/<escaped owner>/<id>/samples/<hash of name>.json}. A read carries the
 * store version in {@link StoredDesign#storeVersion}; a save is a compare-and-set against it ({@code 409 DRS-5057} when another node changed
 * the Design meanwhile) and a new Design (no version) must not exist yet. Sample documents are last-writer-wins, as before.
 */
public final class ContentDesignStore implements DesignStore {

    static final String ROOT = "designs/";
    private final ContentStore store;
    private final ObjectMapper json = new ObjectMapper();

    public ContentDesignStore(ContentStore store) {
        this.store = store;
    }

    public ContentStore content() {
        return store;
    }

    static String prefix(String user) {
        return ROOT + FileDesignStore.escape(user) + "/";
    }

    static String designKey(String user, String id) {
        return prefix(user) + id + "/design.json";
    }

    static String sampleKey(String user, String id, String name) {
        return prefix(user) + id + "/samples/" + FileDesignStore.fileOf(name);
    }

    @Override
    public List<StoredDesign> list(String user) {
        List<StoredDesign> out = new ArrayList<>();
        String p = prefix(user);
        for (String k : store.list(p)) {
            String rest = k.substring(p.length());
            int slash = rest.indexOf('/');
            if (slash > 0 && rest.substring(slash).equals("/design.json")) {
                get(user, rest.substring(0, slash)).ifPresent(out::add);
            }
        }
        return out;
    }

    @Override
    public Optional<StoredDesign> get(String user, String id) {
        if (!FileDesignStore.ID.matcher(id).matches()) {
            return Optional.empty();
        }
        return store.get(designKey(user, id)).map(it -> {
            try {
                StoredDesign d = json.readValue(it.bytes(), StoredDesign.class);
                d.storeVersion = it.version();
                return d;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Override
    public void save(StoredDesign d) {
        try {
            d.storeVersion = store.put(designKey(d.owner, d.id), json.writeValueAsBytes(d), d.storeVersion);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean delete(String user, String id) {
        if (!FileDesignStore.ID.matcher(id).matches()) {
            return false;
        }
        String design = designKey(user, id);
        boolean had = store.deleteLatest(design);
        for (String k : store.list(prefix(user) + id + "/")) {
            store.deleteLatest(k);
        }
        return had;
    }

    @Override
    public void putSample(String user, String id, String name, String jsonText) {
        store.putLatest(sampleKey(user, id, name), jsonText.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Optional<String> sample(String user, String id, String name) {
        if (!FileDesignStore.ID.matcher(id).matches()) {
            return Optional.empty();
        }
        return store.get(sampleKey(user, id, name)).map(ContentStore.Item::text);
    }

    @Override
    public void removeSample(String user, String id, String name) {
        if (FileDesignStore.ID.matcher(id).matches()) {
            store.deleteLatest(sampleKey(user, id, name));
        }
    }

    @Override
    public List<String> users() {
        Set<String> out = new LinkedHashSet<>();
        for (String k : store.list(ROOT)) {
            String rest = k.substring(ROOT.length());
            int slash = rest.indexOf('/');
            if (slash > 0) {
                out.add(FileDesignStore.unescape(rest.substring(0, slash)));
            }
        }
        return new ArrayList<>(out);
    }

    @Override
    public void forget(String user) {
        for (String k : store.list(prefix(user))) {
            store.deleteLatest(k);
        }
    }
}
