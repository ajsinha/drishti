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

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/** The thread contract with threads in the content store (the default store, DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 9). */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance,logistics",
        "drishti.collab.store=content", "drishti.store.root=${drishti.test.dir}/threadapi-content-${random.uuid}/fstore",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/threadapi-content-${random.uuid}/identity.db",
        "drishti.packs.overlay=${drishti.test.dir}/threadapi-content-overlay/added.yaml", "drishti.collab.limits.comments-per-minute=1000",
        "drishti.collab.inbox.poll=100ms", "drishti.collab.threads.edit-window=3s", "drishti.security.registered-users-only=true"})
@AutoConfigureMockMvc
class ThreadApiContentTest extends ThreadApiContract {}
