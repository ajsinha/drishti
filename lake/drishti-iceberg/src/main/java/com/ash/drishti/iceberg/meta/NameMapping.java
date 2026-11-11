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
package com.ash.drishti.iceberg.meta;

import org.apache.iceberg.mapping.MappedField;

/** {@code schema.name-mapping.default}: names to field ids for data files written without ids, as Apache Iceberg parses it. */
public final class NameMapping {

    private final org.apache.iceberg.mapping.NameMapping mapping;

    NameMapping(org.apache.iceberg.mapping.NameMapping mapping) {
        this.mapping = mapping;
    }

    /** The field id of a dotted path, or null. */
    public Integer id(String path) {
        MappedField f = mapping.find(path.split("\\."));
        return f == null ? null : f.id();
    }
}
