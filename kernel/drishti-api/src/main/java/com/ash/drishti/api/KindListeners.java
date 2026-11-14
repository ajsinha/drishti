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
package com.ash.drishti.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The listeners of a pushing source, by entity (an open view) and by kind (a live result set, {@link SourcePlugin#subscribeKind}): one
 * implementation for every source that pushes (Kafka, RabbitMQ, ActiveMQ). A listener that throws does not stop the others. Thread-safe.
 */
public final class KindListeners {

    private final Map<EntityRef, List<Consumer<EntityDocument>>> byEntity = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<EntityDocument>>> byKind = new ConcurrentHashMap<>();

    /** Listens to one entity; the entry goes with its last listener. */
    public Subscription entity(EntityRef ref, Consumer<EntityDocument> listener) {
        return add(byEntity, ref, listener);
    }

    /** Listens to every entity of a kind. */
    public Subscription kind(String kind, Consumer<EntityDocument> listener) {
        return add(byKind, kind, listener);
    }

    /** Whether anyone listens to the entity or its kind (a source may skip parsing what nobody wants). */
    public boolean wanted(EntityRef ref) {
        return byEntity.containsKey(ref) || byKind.containsKey(ref.kind());
    }

    /** Whether anyone listens to the entity itself. */
    public boolean watched(EntityRef ref) {
        return byEntity.containsKey(ref);
    }

    /** Whether anyone listens to the kind as a whole. */
    public boolean watchedKind(String kind) {
        return byKind.containsKey(kind);
    }

    /** Hands a document (or a deletion) to the entity's listeners and its kind's. */
    public void tell(EntityRef ref, EntityDocument d) {
        for (List<Consumer<EntityDocument>> subs : java.util.Arrays.asList(byEntity.get(ref), byKind.get(ref.kind()))) {
            if (subs == null) {
                continue;
            }
            subs.forEach(l -> {
                try {
                    l.accept(d);
                } catch (RuntimeException ignored) {
                    // one broken view must not stop the others
                }
            });
        }
    }

    private static <K> Subscription add(Map<K, List<Consumer<EntityDocument>>> m, K key, Consumer<EntityDocument> listener) {
        m.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> m.computeIfPresent(key, (k, subs) -> {
            subs.remove(listener);
            return subs.isEmpty() ? null : subs;
        });
    }
}
