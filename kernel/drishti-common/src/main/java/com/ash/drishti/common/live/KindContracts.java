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
package com.ash.drishti.common.live;

import java.util.List;
import java.util.Map;

/**
 * The loaded kinds' contracts as the live intake reads them: each kind's live rules and its contract (fields, key). The server's
 * {@code LiveContracts} answers it from the loaded packs; a process without packs uses {@link #NONE}.
 */
public interface KindContracts {

    /** The kind's live rules (its contract's block, else the defaults). */
    LiveRules rules(String kind);

    /** The kind's contract as read, or an empty map when no loaded pack has one. */
    Map<String, Object> contract(String kind);

    /** No contracts: every kind with the defaults and no key of its own (the transport's message key, or the connector's id-field). */
    KindContracts NONE = new KindContracts() {
        @Override
        public LiveRules rules(String kind) {
            return LiveRules.defaults(kind, List.of(), false);
        }

        @Override
        public Map<String, Object> contract(String kind) {
            return Map.of();
        }
    };
}
