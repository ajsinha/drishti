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
package com.ash.drishti.server.collab.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** S3-08: the licence comment at the top of a template is not part of the message. */
class MailTemplatesStripTest {

    @Test
    void theBundledHtmlTemplatesLoadWithoutTheirLicenceComment() {
        MailTemplates t = new MailTemplates("");
        for (String name : new String[] {"share", "mention", "reply", "digest", "test"}) {
            assertThat(t.load(name, "html")).as(name).doesNotContain("{#").doesNotContain("Copyright").startsWith("<!doctype html>");
            assertThat(t.load(name, "txt")).as(name).doesNotContain("{#");
        }
    }

    @Test
    void aShareFooterNoLongerClaimsItCarriesNoFigures() {
        assertThat(new MailTemplates("").load("share", "txt")).doesNotContain("carries no figures");
    }
}
