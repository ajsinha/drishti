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
package com.ash.drishti.server.collab;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.identity.collab.Share.Span;
import java.util.List;
import org.junit.jupiter.api.Test;

/** S3-07: copies of a masked value are found through case, spacing, zero-width characters, compatibility forms and common look-alikes. */
class NoteTextFoldTest {

    private static final List<String> SECRETS = List.of("Summit Clearing LLC", "TRDR-RCASTILLO");

    private static String masked(String text) {
        return NoteText.render(text, NoteText.spans(text, SECRETS), true);
    }

    @Test
    void variantsOfAMaskedValueAreMasked() {
        assertThat(masked("see summit clearing llc now")).isEqualTo("see ••• now");
        assertThat(masked("see Summit  Clearing LLC now")).isEqualTo("see ••• now");
        assertThat(masked("see TRDR​-RCASTILLO now")).isEqualTo("see ••• now");
        assertThat(masked("see S\u03c5mmit \u0421learing LLC now")).as("Greek and Cyrillic look-alikes").isEqualTo("see ••• now");
        assertThat(masked("see ＴＲＤＲ-RCASTILLO now")).as("full-width forms (NFKC)").isEqualTo("see ••• now");
    }

    @Test
    void theRangesPointAtTheOriginalText() {
        String text = "x Summit  Clearing LLC y";
        assertThat(NoteText.spans(text, SECRETS)).containsExactly(new Span(2, 22));
    }

    @Test
    void anUnrelatedTextIsLeftAlone() {
        assertThat(masked("Zzz Clearing LLC and nothing else")).isEqualTo("Zzz Clearing LLC and nothing else");
    }
}
