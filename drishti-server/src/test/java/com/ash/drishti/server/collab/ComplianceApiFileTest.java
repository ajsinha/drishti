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

import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.FileThreadStore;
import com.ash.drishti.identity.collab.ThreadStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/** The same compliance contract with the record as files ({@code drishti.collab.store=file}); holds are {@code holds.json}. */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance,logistics",
        "drishti.identity.database-url=jdbc:sqlite:target/compliance-file-${random.uuid}/identity.db",
        "drishti.packs.overlay=target/compliance-file-overlay/added.yaml", "drishti.collab.limits.comments-per-minute=1000",
        "drishti.collab.limits.shares-per-minute=1000", "drishti.collab.limits.shares-per-day=1000", "drishti.collab.retention.keep-days=1",
        "drishti.collab.retention.interval=1h", "drishti.collab.inbox.poll=100ms",
        "drishti.collab.store=file", "drishti.collab.dir=target/compliance-file-${random.uuid}/collab"})
@AutoConfigureMockMvc
class ComplianceApiFileTest extends ComplianceApiContract {

    @Autowired CollabProperties collab;
    @Autowired ThreadStore store;

    @Override
    void tamper(String commentId, String originalText) {
        Path root = Path.of(collab.dir()).toAbsolutePath().normalize().resolve("threads");
        try (var files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".jsonl")).toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                if (text.contains(originalText)) {
                    Files.writeString(f, text.replace(originalText, "TAMPERED"), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ((FileThreadStore) com.ash.drishti.identity.collab.Seals.unwrap(store)).reload();
    }

}
