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
package com.ash.drishti.memlake;

import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.memlake.store.MemoryStores;
import java.util.Locale;

/**
 * The owner node binds the pack (HOW_IT_FITS.md section D9): a memory store is held by one node; on the others the kinds its connectors
 * serve are not answered here but forwarded over Drishti*net to the owner, as any pack a peer holds, and never cached on their disks. The
 * net routing asks {@link #remoteHere(SourceRegistry, String)}.
 */
public final class MemoryBinding {

    private static volatile MemoryLake lake;
    private static volatile SourceRegistry registry;

    private MemoryBinding() {}

    public static void install(MemoryLake l, SourceRegistry r) {
        lake = l;
        registry = r;
    }

    /** As {@link #remoteHere(SourceRegistry, String)} with the server's connectors. */
    public static boolean remoteHere(String kind) {
        return remoteHere(registry, kind);
    }

    /**
     * True when {@code kind} is served here by a connector reading a memory store this node does not own: the request goes to the owner.
     */
    public static boolean remoteHere(SourceRegistry registry, String kind) {
        MemoryLake l = lake;
        if (l == null || registry == null || kind == null || !l.configured()) {
            return false;
        }
        for (String name : registry.connectorStatuses().keySet()) {
            var applied = registry.applied(name).orElse(null);
            if (applied == null || !applied.kinds().contains(kind)) {
                continue;
            }
            String root = applied.settings().getOrDefault("root", "").trim();
            if (MemoryStores.isMemory(root)) {
                return !l.owns(MemoryStores.storeName(root).toLowerCase(Locale.ROOT));
            }
        }
        return false;
    }

    /** What a screen says while its memory table is not ready (refilling, evicted), or null. */
    public static String words(String root, String kind) {
        MemoryLake l = lake;
        return l == null ? null : l.words(root, kind);
    }
}
