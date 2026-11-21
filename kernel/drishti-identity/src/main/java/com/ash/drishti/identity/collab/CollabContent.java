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

import com.ash.drishti.identity.content.TxContentStore;

/**
 * Collaboration kept in the content store (DATA_ARCHITECTURE.md, the content store: user content every node reaches): the stores
 * over it, built once and shared by the beans that serve threads, shares, inbox, outbox, holds and seals.
 */
public final class CollabContent {

    /** The content stores of collaboration. */
    public record Target(TxContentStore store, ContentThreadStore threads, ContentShareStore shares, ContentInboxStore inbox,
            ContentOutboxStore outbox, ContentHoldStore holds, ContentSealStore seals) {

        public static Target of(TxContentStore store) {
            return new Target(store, new ContentThreadStore(store), new ContentShareStore(store), new ContentInboxStore(store, Integer.MAX_VALUE),
                    new ContentOutboxStore(store), new ContentHoldStore(store), new ContentSealStore(store));
        }
    }

    /** The content stores when collaboration is kept in the content store, else none (a Spring bean either way). */
    public record Content(Target target) {

        public boolean isPresent() {
            return target != null;
        }

        public Target get() {
            return target;
        }
    }

    private CollabContent() {}
}
