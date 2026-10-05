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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What a message says and never says: escaping, subjects on one line, closed template variables, link-only, template overrides. */
class MailRenderTest {

    private final MailRenderer renderer = new MailRenderer(new MailTemplates(""), "Drishti", "bank.example");

    private MailRenderer.Content share(String note, boolean linkOnly) {
        return new MailRenderer.Content("share", "Ann shared a view with you", linkOnly ? null : "Trade", linkOnly ? null : "IRS-48213",
                linkOnly ? null : "cashflows", linkOnly ? null : "2026-09-30", note, "https://drishti.example/share/sh_1");
    }

    @Test
    void aMessageHasTheKindTheIdTheNoteAndTheLinkAndNothingElse() {
        RenderedMail m = renderer.render(share("please look at the fixing", false), "ravi@desk.test", 7, "sh_1");
        assertThat(m.subject()).isEqualTo("Drishti: Ann shared a view with you");
        assertThat(m.text()).contains("Trade IRS-48213", "Panel: cashflows", "As of: 2026-09-30", "please look at the fixing",
                "https://drishti.example/share/sh_1", "no figures");
        assertThat(m.html()).contains("Trade IRS-48213", "please look at the fixing", "href=\"https://drishti.example/share/sh_1\"")
                .doesNotContain("<img", "<script", "http://", "src=");
        assertThat(m.messageId()).isEqualTo("<7.sh_1@bank.example>");
    }

    @Test
    void linkOnlyCarriesNeitherTheIdNorTheNote() {
        RenderedMail m = renderer.render(share(null, true), "ravi@desk.test", 1, "sh_1");
        assertThat(m.text()).contains("Ann shared a view with you", "https://drishti.example/share/sh_1").doesNotContain("IRS-48213", "Trade", "Note:");
        assertThat(m.html()).doesNotContain("IRS-48213", "blockquote");
    }

    @Test
    void everyInsertedValueIsEscapedForHtmlAndTemplateSyntaxInANoteStaysLiteral() {
        RenderedMail m = renderer.render(share("<script>alert(1)</script> ${product} ${link} & \"q\"", false), "ravi@desk.test", 1, "sh_1");
        assertThat(m.html()).contains("&lt;script&gt;alert(1)&lt;/script&gt;", "&amp;", "&quot;q&quot;").doesNotContain("<script>");
        assertThat(m.html()).contains("${product} ${link}").as("variables are not expanded inside a note").doesNotContain("Drishti} ");
        assertThat(m.text()).contains("${product} ${link}");
    }

    @Test
    void aSubjectIsOneLineWhateverTheSenderIsCalled() {
        MailRenderer.Content c = new MailRenderer.Content("share", "Ann\r\nBcc: evil@x.test shared a view with you", null, null, null, null, null, "https://x/s");
        RenderedMail m = renderer.render(c, "ravi@desk.test\r\nBcc: evil@x.test", 1, "sh_1\r\n");
        assertThat(m.subject()).doesNotContain("\r").doesNotContain("\n");
        assertThat(m.to()).doesNotContain("\r").doesNotContain("\n");
        assertThat(m.messageId()).doesNotContain("\r").doesNotContain("\n").doesNotContain(" ");
    }

    @Test
    void theProductNameComesFromConfiguration() {
        RenderedMail m = new MailRenderer(new MailTemplates(""), "Acme Insight", "bank.example").render(share("n", false), "a@b.test", 1, "sh_1");
        assertThat(m.subject()).startsWith("Acme Insight:");
        assertThat(m.text()).contains("Open it in Acme Insight");
    }

    @Test
    void aTemplateDirectoryReplacesTheBundledFile() throws Exception {
        Path dir = Files.createTempDirectory("mail-templates");
        Files.writeString(dir.resolve("share.txt"), "custom ${headline} ${link}");
        MailRenderer r = new MailRenderer(new MailTemplates(dir.toString()), "Drishti", "x");
        RenderedMail m = r.render(share("n", false), "a@b.test", 1, "sh_1");
        assertThat(m.text()).isEqualTo("custom Ann shared a view with you https://drishti.example/share/sh_1");
        assertThat(m.html()).as("files not overridden stay bundled").contains("Open it in Drishti");
    }

    @Test
    void fillIsOnePassAndUnknownNamesAreEmpty() {
        assertThat(MailTemplates.fill("a ${x} b ${nope} c ${y", Map.of("x", "${y}", "y", "Y"))).isEqualTo("a ${y} b  c ${y");
    }
}
