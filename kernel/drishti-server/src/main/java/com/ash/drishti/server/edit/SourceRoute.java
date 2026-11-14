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
package com.ash.drishti.server.edit;

import java.util.List;
import java.util.Optional;

/**
 * Where a change to a source goes when several trading systems feed one kind (docs/guides/DATA_LAKE_GUIDE.md, "Several source systems"): the
 * record's own system ({@code SOURCE_SYSTEM}, as its connector's {@code where} or {@code set} produced it) and that system's connectors. A
 * blotter edit goes to the write path of the system the record came from, never another system's.
 */
public interface SourceRoute {

    /** No source systems: a change goes where the form or the kind's binding says. */
    SourceRoute NONE = new SourceRoute() {
        @Override
        public Optional<String> systemOf(String kind, String entity) {
            return Optional.empty();
        }

        @Override
        public List<String> connectorsOf(String system) {
            return List.of();
        }
    };

    /** The trading system the record came from, if a source system feeds its kind. */
    Optional<String> systemOf(String kind, String entity);

    /** The running connectors of the system, in name order. */
    List<String> connectorsOf(String system);
}
