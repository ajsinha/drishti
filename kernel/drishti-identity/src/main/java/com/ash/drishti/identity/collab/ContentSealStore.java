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

import com.ash.drishti.identity.content.ContentDocs;
import com.ash.drishti.identity.content.TxContentStore;
import java.util.Optional;

/**
 * Tamper-evidence seals in the content store: {@code collab/seals/<name of the seal key>.json} (DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 9). A seal is
 * written in the transaction of the row it covers when there is one, so the two move together.
 */
public final class ContentSealStore implements Seal.Store {

    static final String SEALS = "collab/seals/";
    private final ContentDocs docs;

    public ContentSealStore(TxContentStore store) {
        this.docs = new ContentDocs(store);
    }

    static String key(String sealKey) {
        return SEALS + ContentDocs.name(sealKey) + "-" + ContentDocs.hash(sealKey).substring(0, 12) + ".json";
    }

    @Override
    public Optional<Seal> get(String key) {
        return docs.value(key(key), Seal.class);
    }

    @Override
    public void put(String key, Seal seal) {
        docs.update(key(key), Seal.class, s -> seal);
    }
}
