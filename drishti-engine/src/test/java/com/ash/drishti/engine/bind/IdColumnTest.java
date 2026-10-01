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
package com.ash.drishti.engine.bind;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Table columns bound to an identifier field link to the entity without {@code link: true}. */
class IdColumnTest {

    @Test
    void identifierFieldsLinkAndOtherFieldsDoNot() {
        assertThat(Binder.namesAnId("@.tradeId")).isTrue();
        assertThat(Binder.namesAnId("$.counterpartyId")).isTrue();
        assertThat(Binder.namesAnId("@.legs[0].bookRef")).isTrue();
        assertThat(Binder.namesAnId("@.account_id")).isTrue();
        assertThat(Binder.namesAnId("@.product")).isFalse();
        assertThat(Binder.namesAnId("@.id")).isFalse();                       // too vague: an id of what?
        assertThat(Binder.namesAnId("fmt(@.tradeId, 'text')")).isFalse();     // an expression decides for itself
        assertThat(Binder.namesAnId(null)).isFalse();
    }
}
