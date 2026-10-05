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

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The same email contract with shares, the inbox and the outbox as files. */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance,logistics",
        "drishti.collab.limits.shares-per-minute=1000", "drishti.collab.inbox.poll=100ms",
        "drishti.collab.email.enabled=true", "drishti.collab.console-url=https://drishti.test", "drishti.collab.email.from=drishti@localhost",
        "drishti.collab.outbox.tick=100ms", "drishti.collab.outbox.backoff=300ms", "drishti.collab.outbox.max-backoff=1s",
        "drishti.collab.outbox.max-attempts=50", "spring.mail.host=127.0.0.1",
        "spring.mail.properties.mail.smtp.connectiontimeout=2000", "spring.mail.properties.mail.smtp.timeout=2000",
        "drishti.identity.database-url=jdbc:sqlite:target/sharemail-file-${random.uuid}/identity.db",
        "drishti.packs.overlay=target/sharemail-file-overlay/added.yaml", "drishti.collab.store=file",
        "drishti.collab.dir=target/sharemail-file-${random.uuid}/collab"})
@AutoConfigureMockMvc
class ShareMailFileTest extends ShareMailContract {
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
}
