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

import java.util.Objects;

/**
 * Address of an entity: its kind (trade, netting-set, curve, ...) and its identifier.
 *
 * @param kind the entity kind, lower-case kebab (for example {@code netting-set})
 * @param id the identifier within the kind (for example {@code NS-NORTH-01})
 */
public record EntityRef(String kind, String id) {

    public EntityRef {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        if (kind.isBlank() || id.isBlank()) {
            throw new IllegalArgumentException("kind and id must not be blank");
        }
    }

    public static EntityRef of(String kind, String id) {
        return new EntityRef(kind, id);
    }

    @Override
    public String toString() {
        return kind + "/" + id;
    }
}
