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
package com.ash.drishti.server.collab.thread;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.identity.collab.Pin;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionException;

/**
 * The stored (unmasked) document a comment was written against, read at its pin: the date and, when the source keeps versions, the
 * instant it was known. A source that answers {@code knownAt} with {@code DRS-1007} is asked again for the date alone. Used to find
 * the values to scrub from old text and to render value quotes; the document never leaves the server unmasked, and is kept in memory
 * for a minute. When it cannot be read the answer is empty and callers fail safe.
 */
public final class PinnedDocs {

    private final SourceRouter router;
    private final Cache<String, DataNode> cache = Caffeine.newBuilder().maximumSize(200).expireAfterWrite(Duration.ofMinutes(1)).build();

    public PinnedDocs(SourceRouter router) {
        this.router = router;
    }

    /** The document at the pin, or empty when the source cannot answer. */
    public Optional<DataNode> at(String kind, String id, Pin pin) {
        String key = kind + '\u0000' + id + '\u0000' + (pin == null ? "" : pin.businessDate() + "|" + pin.live() + "|" + pin.knownAt());
        DataNode hit = cache.getIfPresent(key);
        if (hit != null) {
            return Optional.of(hit);
        }
        boolean latest = pin == null || pin.live() || pin.businessDate() == null;
        AsOf asOf = latest ? AsOf.LATEST : new AsOf(pin.businessDate(), pin.knownAt());
        DataNode node = read(kind, id, asOf);
        if (node == null && !latest && pin.knownAt() != null) {
            node = read(kind, id, new AsOf(pin.businessDate(), null));
        }
        if (node == null) {
            return Optional.empty();
        }
        cache.put(key, node);
        return Optional.of(node);
    }

    private DataNode read(String kind, String id, AsOf asOf) {
        try {
            EntityDocument d = router.fetch(EntityRef.of(kind, id), asOf).join();
            return d.data();
        } catch (CompletionException | DrishtiException e) {
            return null;
        }
    }
}
