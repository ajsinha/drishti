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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** A pack whose identifiers are sensitive sets {@code email.content: link-only}: the message carries neither the id nor the note. */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance,logistics",
        "drishti.collab.limits.shares-per-minute=1000", "drishti.collab.email.enabled=true", "drishti.collab.console-url=https://drishti.test",
        "drishti.collab.outbox.tick=100ms", "spring.mail.host=127.0.0.1", "drishti.collab.packs.finance.email.content=link-only",
        "drishti.identity.database-url=jdbc:sqlite:target/sharemail-lo-${random.uuid}/identity.db",
        "drishti.packs.overlay=target/sharemail-lo-overlay/added.yaml"})
@AutoConfigureMockMvc
class ShareMailLinkOnlyTest extends ShareMailSupport {
    static final int PORT = freePort();

    @RegisterExtension
    static final GreenMailExtension SMTP = new GreenMailExtension(new ServerSetup(PORT, "127.0.0.1", ServerSetup.PROTOCOL_SMTP))
            .withPerMethodLifecycle(false);

    @DynamicPropertySource
    static void mail(DynamicPropertyRegistry r) {
        r.add("spring.mail.port", () -> PORT);
    }

    @Override
    GreenMailExtension smtp() {
        return SMTP;
    }

    @Test
    void aLinkOnlyPackMailsNeitherTheIdNorTheNoteNorAValue() throws Exception {
        smtp().purgeEmailFromAllMailboxes();
        String res = share(SECRET + " says the curve moved", "ravi", "rng").andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String link = CONSOLE + "/share/" + json.readTree(res).get("id").asText();
        for (String who : List.of("ravi@desk.test", "rng@desk.test")) {
            MimeMessage m = mailTo(who, 1).get(0);
            String all = part(m, "text/plain") + part(m, "text/html") + m.getSubject();
            assertThat(all).contains("ANN Person shared a view with you", link).doesNotContain(TRADE, SECRET, "curve moved", "Note");
        }
    }
}
