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
package com.ash.drishti.identity.collab;

import com.ash.drishti.identity.db.IdentityRepositories;
import com.ash.drishti.identity.db.SealEntity;
import java.util.Optional;
import org.springframework.transaction.support.TransactionTemplate;

/** Seals in the identity database. */
public final class JpaSealStore implements Seal.Store {

    private final IdentityRepositories.Seals seals;
    private final TransactionTemplate tx;

    public JpaSealStore(IdentityRepositories.Seals seals, TransactionTemplate tx) {
        this.seals = seals;
        this.tx = tx;
    }

    @Override
    public Optional<Seal> get(String key) {
        return tx.execute(s -> seals.findById(key).map(e -> new Seal(e.count, e.hash)));
    }

    @Override
    public void put(String key, Seal seal) {
        tx.executeWithoutResult(s -> {
            SealEntity e = seals.findById(key).orElseGet(SealEntity::new);
            e.key = key;
            e.count = seal.count();
            e.hash = seal.hash();
            seals.save(e);
        });
    }
}
