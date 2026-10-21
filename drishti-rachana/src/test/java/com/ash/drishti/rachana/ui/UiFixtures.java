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
package com.ash.drishti.rachana.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** The documents of UI_BOARD.md 2.1 and 3.1 and the two chips 3.1 uses, as test resources. */
final class UiFixtures {

    private UiFixtures() {}

    static String text(String name) {
        try (InputStream in = UiFixtures.class.getResourceAsStream("/ui/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("no test resource ui/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static ChipDoc table() {
        return UiDocuments.readChip(text("filterable-table.v2.chip.yaml"));
    }

    static ChipDoc card() {
        return UiDocuments.readChip(text("entity-card.v1.chip.yaml"));
    }

    static ChipDoc ladder() {
        return UiDocuments.readChip(text("cashflow-ladder.v1.chip.yaml"));
    }

    /** The three chips, published. */
    static ChipCatalog chips() {
        return ChipCatalog.of(ChipState.PUBLISHED, List.of(table(), card(), ladder()));
    }

    static SupplierCatalog suppliers() {
        return SupplierCatalog.fromDocument(UiDocuments.tree(text("suppliers.yaml")));
    }
}
