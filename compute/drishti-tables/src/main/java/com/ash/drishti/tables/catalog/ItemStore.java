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
package com.ash.drishti.tables.catalog;

import java.util.List;

/**
 * Where stored definitions live (Q16): the server's item store ({@code drishti_bi_item}, no schema change) with the kinds {@code table} and
 * {@code model}, governed as BI's items are (workspaces, review, versions). The server implements it over its {@code BiStore}; the
 * command line and the tests read files only ({@link #NONE}). Writing goes through the item store's own governance, never through this
 * interface.
 */
public interface ItemStore {

    /** No stored items. */
    ItemStore NONE = List::of;

    /** The published version of every stored table and model the caller may read. */
    List<StoredItem> list();

    /**
     * One stored definition.
     *
     * @param id the item's stable id
     * @param kind {@code table} or {@code model}
     * @param workspace the workspace it is published in
     * @param version the item's version number in the store
     * @param yaml the definition's text
     */
    record StoredItem(String id, String kind, String workspace, int version, String yaml) {

        public static final String TABLE = "table";
        public static final String MODEL = "model";

        /** How problems name it: {@code item:<id>@<version>}. */
        public String label() {
            return "item:" + id + "@" + version;
        }
    }
}
