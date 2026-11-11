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
package com.ash.drishti.server.bi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The title of a body is read and written the same way the item's is: one source of truth. */
class BiTitlesTest {

    @Test
    void readsPlainAndQuotedTitlesAtTheTopLevelOnly() {
        assertThat(BiTitles.of("notebook: a\ntitle: Desk P&L\ncells: []\n")).isEqualTo("Desk P&L");
        assertThat(BiTitles.of("title: \"Say \\\"hi\\\"\"\n")).isEqualTo("Say \"hi\"");
        assertThat(BiTitles.of("title: 'it''s'\n")).isEqualTo("it's");
        assertThat(BiTitles.of("pages:\n  - title: Page 1\n")).isNull();
        assertThat(BiTitles.of("title: Plain # a comment\n")).isEqualTo("Plain");
        assertThat(BiTitles.of(null)).isNull();
    }

    @Test
    void writesTheTitleBackOnlyWhereThereIsOne() {
        assertThat(BiTitles.with("notebook: a\ntitle: Old\ncells: []\n", "New \"one\"")).isEqualTo("notebook: a\ntitle: \"New \\\"one\\\"\"\ncells: []\n");
        assertThat(BiTitles.with("notebook: a\n", "New")).isEqualTo("notebook: a\n");
        assertThat(BiTitles.of(BiTitles.with("title: Old\n", "A \\ B"))).isEqualTo("A \\ B");
    }
}
