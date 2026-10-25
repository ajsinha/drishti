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

import com.ash.drishti.identity.content.ContentStore;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/** The compliance contract with the record in the content store (the default store, CONTENT_STORE.md section 9). */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance,logistics",
        "drishti.collab.store=content", "drishti.store.root=${drishti.test.dir}/compliance-content-${random.uuid}/fstore",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/compliance-content-${random.uuid}/identity.db",
        "drishti.packs.overlay=${drishti.test.dir}/compliance-content-overlay/added.yaml", "drishti.collab.limits.comments-per-minute=1000",
        "drishti.collab.limits.shares-per-minute=1000", "drishti.collab.limits.shares-per-day=1000", "drishti.collab.retention.keep-days=1",
        "drishti.collab.retention.interval=1h", "drishti.collab.inbox.poll=100ms"})
@AutoConfigureMockMvc
class ComplianceApiContentTest extends ComplianceApiContract {

    @Autowired ContentStore content;

    @Override
    void tamper(String commentId, String originalText) {
        for (String k : content.list("collab/threads/")) {
            String text = content.get(k).orElseThrow().text();
            if (text.contains(originalText)) {
                content.putLatest(k, text.replace(originalText, "TAMPERED").getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
