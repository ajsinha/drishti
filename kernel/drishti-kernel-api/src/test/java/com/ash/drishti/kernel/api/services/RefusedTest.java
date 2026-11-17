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
package com.ash.drishti.kernel.api.services;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link Kernel.Refused}: the words a panel says when a typed call is refused. */
class RefusedTest {

    @Test
    void sayGivesTheCodeAndTheWordsOrTheStatusWithoutACode() {
        assertThat(new Kernel.Refused(403, "DRS-1020", "the module banking is off").say()).isEqualTo("DRS-1020 the module banking is off");
        assertThat(new Kernel.Refused(503, null, "busy").say()).isEqualTo("503 busy");
        assertThat(new Kernel.Refused(404, "DRS-4040", null).getMessage()).isEmpty();
    }
}
