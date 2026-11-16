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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.db.IdentityRepositories;
import com.ash.drishti.identity.db.PreferenceEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Per-user documents in the identity database. Writes for one user are serialised (the namespace limit is a check
 * then an insert), different users never contend, and each change is one transaction.
 */
public final class JpaPreferenceStore implements PreferenceStore {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}");
    private final IdentityRepositories.Preferences prefs;
    private final TransactionTemplate tx;
    private final int maxDocBytes;
    private final int maxPerNamespace;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public JpaPreferenceStore(IdentityRepositories.Preferences prefs, TransactionTemplate tx, int maxDocBytes, int maxPerNamespace) {
        this.prefs = prefs;
        this.tx = tx;
        this.maxDocBytes = maxDocBytes;
        this.maxPerNamespace = maxPerNamespace;
    }

    @Override
    public List<String> keys(String user, String namespace) {
        return prefs.findByKeyUsernameAndKeyNamespaceOrderByKeyName(user, namespace).stream().map(p -> p.key.name)
                .sorted(String::compareToIgnoreCase).toList();
    }

    @Override
    public Optional<JsonNode> get(String user, String namespace, String key) {
        return prefs.findById(new PreferenceEntity.Key(user, namespace, key)).map(p -> {
            try {
                return json.readTree(p.document);
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Override
    public void put(String user, String namespace, String key, JsonNode value) {
        if (!KEY.matcher(key).matches()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "names are 1-64 of letters, digits, space . _ -");
        }
        String doc;
        try {
            doc = json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        if (doc.length() > maxDocBytes) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "document larger than " + maxDocBytes + " bytes");
        }
        ReentrantLock lock = locks.computeIfAbsent(user, u -> new ReentrantLock());
        lock.lock();
        try {
            tx.executeWithoutResult(s -> {
                PreferenceEntity.Key k = new PreferenceEntity.Key(user, namespace, key);
                PreferenceEntity e = prefs.findById(k).orElse(null);
                if (e == null && prefs.countByKeyUsernameAndKeyNamespace(user, namespace) >= maxPerNamespace) {
                    throw new DrishtiException(ErrorCode.BAD_REQUEST, "at most " + maxPerNamespace + " " + namespace);
                }
                if (e == null) {
                    e = new PreferenceEntity();
                    e.key = k;
                }
                e.document = doc;
                e.updatedAt = Instant.now();
                prefs.save(e);
            });
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean delete(String user, String namespace, String key) {
        ReentrantLock lock = locks.computeIfAbsent(user, u -> new ReentrantLock());
        lock.lock();
        try {
            return Boolean.TRUE.equals(tx.execute(s -> {
                PreferenceEntity.Key k = new PreferenceEntity.Key(user, namespace, key);
                if (!prefs.existsById(k)) {
                    return false;
                }
                prefs.deleteById(k);
                return true;
            }));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<String> users() {
        return prefs.findAll().stream().map(p -> p.key.username).distinct().sorted().toList();
    }

    @Override
    public void forget(String user) {
        ReentrantLock lock = locks.computeIfAbsent(user, u -> new ReentrantLock());
        lock.lock();
        try {
            tx.executeWithoutResult(s -> prefs.deleteByKeyUsername(user));
        } finally {
            lock.unlock();
        }
    }
}
