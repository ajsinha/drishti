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

import com.ash.drishti.identity.db.DesignEntity;
import com.ash.drishti.identity.db.DesignSampleEntity;
import com.ash.drishti.identity.db.IdentityRepositories;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Designs in the identity database: a row per Design (its metadata as JSON text) and a row per sample document. Each change
 * is one transaction; deleting a Design deletes its sample rows in the same transaction.
 */
public final class JpaDesignStore implements DesignStore {

    private final IdentityRepositories.Designs designs;
    private final IdentityRepositories.DesignSamples samples;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();

    public JpaDesignStore(IdentityRepositories.Designs designs, IdentityRepositories.DesignSamples samples, TransactionTemplate tx) {
        this.designs = designs;
        this.samples = samples;
        this.tx = tx;
    }

    @Override
    public List<StoredDesign> list(String user) {
        return designs.findByKeyUsername(user).stream().map(this::parse).toList();
    }

    @Override
    public Optional<StoredDesign> get(String user, String id) {
        return designs.findById(new DesignEntity.Key(user, id)).map(this::parse);
    }

    @Override
    public void save(StoredDesign d) {
        String doc;
        try {
            doc = json.writeValueAsString(d);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        tx.executeWithoutResult(s -> {
            DesignEntity e = designs.findById(new DesignEntity.Key(d.owner, d.id)).orElseGet(() -> {
                DesignEntity n = new DesignEntity();
                n.key = new DesignEntity.Key(d.owner, d.id);
                return n;
            });
            e.document = doc;
            e.updatedAt = Instant.ofEpochMilli(d.updated);
            designs.save(e);
        });
    }

    @Override
    public boolean delete(String user, String id) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            DesignEntity.Key k = new DesignEntity.Key(user, id);
            if (!designs.existsById(k)) {
                return false;
            }
            samples.deleteByKeyUsernameAndKeyDesignId(user, id);
            designs.deleteById(k);
            return true;
        }));
    }

    @Override
    public void putSample(String user, String id, String name, String jsonText) {
        tx.executeWithoutResult(s -> {
            DesignSampleEntity.Key k = new DesignSampleEntity.Key(user, id, name);
            DesignSampleEntity e = samples.findById(k).orElseGet(() -> {
                DesignSampleEntity n = new DesignSampleEntity();
                n.key = k;
                return n;
            });
            e.content = jsonText;
            samples.save(e);
        });
    }

    @Override
    public Optional<String> sample(String user, String id, String name) {
        return samples.findById(new DesignSampleEntity.Key(user, id, name)).map(e -> e.content);
    }

    @Override
    public void removeSample(String user, String id, String name) {
        tx.executeWithoutResult(s -> {
            DesignSampleEntity.Key k = new DesignSampleEntity.Key(user, id, name);
            if (samples.existsById(k)) {
                samples.deleteById(k);
            }
        });
    }

    @Override
    public List<String> users() {
        return designs.findAll().stream().map(e -> e.key.username).distinct().sorted().toList();
    }

    @Override
    public void forget(String user) {
        tx.executeWithoutResult(s -> {
            samples.deleteByKeyUsername(user);
            designs.deleteByKeyUsername(user);
        });
    }

    private StoredDesign parse(DesignEntity e) {
        try {
            return json.readValue(e.document, StoredDesign.class);
        } catch (JsonProcessingException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
