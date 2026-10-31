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
package com.ash.drishti.server.connectors;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.packs.ConnectorFiles;
import com.ash.drishti.packs.ConnectorOverlay;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The shared connector overlay in the content store (DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 11):
 * {@code config/connectors/<name>.yaml} holds the text saved through Admin (exactly as saved: placeholders unresolved),
 * {@code config/connectors/<name>.hidden} hides a file deleted through Admin, and {@code config/connectors-history/<name>/<stamp>.yaml}
 * keeps every earlier effective text. Reads come from a cache that {@link #refresh(Collection)} brings up to date from the store's
 * changes; every write is a compare-and-set, so two nodes editing one connector give one {@code 409}.
 */
public final class ContentConnectorOverlay implements ConnectorOverlay {

    public static final String PREFIX = "config/connectors/";
    static final String HISTORY = "config/connectors-history/";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC);
    private static final byte[] HIDDEN = "hidden through Admin\n".getBytes(StandardCharsets.UTF_8);

    private final ContentStore store;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public ContentConnectorOverlay(ContentStore store) {
        this.store = store;
        for (String k : store.list(PREFIX)) {
            String n = nameOf(k);
            if (n != null) {
                reload(n);
            }
        }
    }

    private static String nameOf(String key) {
        if (!key.startsWith(PREFIX)) {
            return null;
        }
        String rest = key.substring(PREFIX.length());
        String n = rest.endsWith(".yaml") ? rest.substring(0, rest.length() - 5) : rest.endsWith(".hidden") ? rest.substring(0, rest.length() - 7) : null;
        return ConnectorFiles.validName(n) ? n : null;
    }

    private static String yamlKey(String name) {
        return PREFIX + name + ".yaml";
    }

    private static String hiddenKey(String name) {
        return PREFIX + name + ".hidden";
    }

    /** Re-reads the connectors behind these changed keys (the content watch hands them over); returns the names. */
    public List<String> refresh(Collection<String> keys) {
        List<String> names = new ArrayList<>();
        for (String k : keys) {
            String n = nameOf(k);
            if (n != null && !names.contains(n)) {
                reload(n);
                names.add(n);
            }
        }
        return names;
    }

    private void reload(String name) {
        Optional<ContentStore.Item> y = store.get(yamlKey(name));
        if (y.isPresent()) {
            Entry old = cache.get(name);
            String text = y.get().text();
            cache.put(name, new Entry(text, false, old != null && !old.hidden() && text.equals(old.text()) ? old.at() : Instant.now()));
        } else if (store.get(hiddenKey(name)).isPresent()) {
            cache.put(name, new Entry(null, true, Instant.now()));
        } else {
            cache.remove(name);
        }
    }

    @Override
    public Entry get(String name) {
        return cache.get(name);
    }

    @Override
    public Map<String, Entry> all() {
        return new TreeMap<>(cache);
    }

    private static DrishtiException conflict(String name) {
        return new DrishtiException(ErrorCode.CONNECTOR_CONFLICT, "connector '" + name
                + "' was changed on another node since you read it; reload it and apply your edit again");
    }

    @Override
    public void put(String name, String text, String expectedEtag, String fileText) {
        Optional<ContentStore.Item> item = store.get(yamlKey(name));
        boolean hidden = item.isEmpty() && store.get(hiddenKey(name)).isPresent();
        String effective = item.map(ContentStore.Item::text).orElse(hidden ? null : fileText);
        if (expectedEtag != null && (effective == null || !ConnectorFiles.etag(effective).equals(expectedEtag))) {
            reload(name);
            throw conflict(name);
        }
        try {
            store.put(yamlKey(name), text.getBytes(StandardCharsets.UTF_8), item.map(ContentStore.Item::version).orElse(null));
        } catch (DrishtiException e) {
            if (e.errorCode() == ErrorCode.CONTENT_CONFLICT) {
                reload(name);
                throw conflict(name);
            }
            throw e;
        }
        keep(name, effective);
        if (hidden) {
            store.deleteLatest(hiddenKey(name));
        }
        cache.put(name, new Entry(text, false, Instant.now()));
    }

    @Override
    public void hide(String name, String effectiveText) {
        keep(name, effectiveText);
        store.putLatest(hiddenKey(name), HIDDEN);
        store.deleteLatest(yamlKey(name));
        cache.put(name, new Entry(null, true, Instant.now()));
    }

    @Override
    public boolean remove(String name, String effectiveText) {
        boolean had = store.get(yamlKey(name)).isPresent() || store.get(hiddenKey(name)).isPresent();
        if (had && effectiveText != null) {
            keep(name, effectiveText);
        }
        store.deleteLatest(yamlKey(name));
        store.deleteLatest(hiddenKey(name));
        cache.remove(name);
        return had;
    }

    /** Keeps an earlier effective text in the shared history. */
    private void keep(String name, String text) {
        if (text == null) {
            return;
        }
        String stamp = STAMP.format(Instant.now());
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 1000; i++) {
            String id = i == 0 ? stamp : stamp + "-" + i;
            try {
                store.put(HISTORY + name + "/" + id + ".yaml", bytes, null);
                return;
            } catch (DrishtiException e) {
                if (e.errorCode() != ErrorCode.CONTENT_CONFLICT) {
                    throw e;
                }
            }
        }
    }

    @Override
    public List<ConnectorFiles.Revision> history(String name) {
        Map<String, ConnectorFiles.Revision> out = new LinkedHashMap<>();
        String p = HISTORY + name + "/";
        for (String k : store.list(p)) {
            String id = k.substring(p.length());
            if (!id.endsWith(".yaml")) {
                continue;
            }
            id = id.substring(0, id.length() - 5);
            if (!id.matches("\\d{8}T\\d{9}(-\\d+)?")) {
                continue;
            }
            long bytes = store.get(k).map(i -> (long) i.bytes().length).orElse(0L);
            Instant at = java.time.LocalDateTime.parse(id.substring(0, 18), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS")).toInstant(ZoneOffset.UTC);
            out.put(id, new ConnectorFiles.Revision(id, at, bytes));
        }
        List<ConnectorFiles.Revision> l = new ArrayList<>(out.values());
        l.sort((a, b) -> b.id().compareTo(a.id()));
        return l;
    }

    @Override
    public String revisionText(String name, String id) {
        if (id == null || !id.matches("\\d{8}T\\d{9}(-\\d+)?")) {
            return null;
        }
        return store.get(HISTORY + name + "/" + id + ".yaml").map(ContentStore.Item::text).orElse(null);
    }
}
