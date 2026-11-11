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
package com.ash.drishti.server.bi.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A column of a kind served by several connectors keeps a type only when every connector agrees: ENDUR's numeric TRADE_ID and MUREX's text one are text. */
class PackModelsWiderTest {

    @Test
    void aColumnIsANumberOnlyWhenEveryConnectorSaysSo() {
        assertThat(PackModels.wider("decimal", "decimal")).isEqualTo("decimal");
        assertThat(PackModels.wider("decimal", "string")).isEqualTo("string");
        assertThat(PackModels.wider("date", "string")).isEqualTo("string");
        assertThat(PackModels.wider("?", "decimal")).isEqualTo("decimal");      // a connector that could not be read says nothing
        assertThat(PackModels.wider("date", "?")).isEqualTo("date");
    }
}
