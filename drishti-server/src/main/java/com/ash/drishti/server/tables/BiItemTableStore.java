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
package com.ash.drishti.server.tables;

import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.tables.catalog.ItemStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The table layer's stored definitions over BI's item store (BI_FOUNDATION_2911E.md Q16): items of the kinds {@code table} and
 * {@code model} in {@code drishti_bi_item}, governed as BI's items are (workspaces, review, versions; no schema change). The catalogue reads
 * the published version of each in the layer's form; a draft is its author's until it is published. BI's models share the kind
 * {@code model} until the first-start migration ({@link StoredTablesMigration}) rewrites them, so a text in BI's form is not listed.
 */
public final class BiItemTableStore implements ItemStore {

    private final BiStore store;

    public BiItemTableStore(BiStore store) {
        this.store = store;
    }

    @Override
    public List<StoredItem> list() {
        List<StoredItem> out = new ArrayList<>();
        for (BiStore.Item i : store.published()) {
            if (!StoredItem.TABLE.equals(i.kind()) && !StoredItem.MODEL.equals(i.kind())) {
                continue;
            }
            Optional<BiStore.Version> v = store.version(i.id(), i.publishedVersion());
            v.filter(x -> layerForm(x.body())).ifPresent(x -> out.add(new StoredItem(i.id(), i.kind(), i.workspace(), x.version(), x.body())));
        }
        return out;
    }

    /** True for a text in the layer's form ({@code rachana: 3}): BI's own models share the kind {@code model} until they are migrated. */
    static boolean layerForm(String text) {
        return text != null && text.lines().anyMatch(l -> l.strip().equals("rachana: 3"));
    }
}
